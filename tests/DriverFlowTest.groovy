/**
 * End-to-end flows through the app and the real drivers, with the hub faked:
 * discovery, child creation, WebSocket reports, commands and their
 * acknowledgement, light children, and REST refresh. State goes through JSON
 * after each step (hub.settle()), as it does on a hub.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Hub hub = new Hub(root)
@Field def app = hub.app()

FakeDevice child(String dni) { app.getChildDevice(dni) }
FakeDevice conn() { child("dreo-connection-${app.app.id}") }

/** Messages sent on the WebSocket since `from`, parsed. */
List sent(int from = 0) {
    conn().driver.callsNamed("ws.send").drop(from).collect { it[1] }.findAll { it != "2" }.collect { new JsonSlurper().parseText(it) }
}

void ws(Map msg) {
    conn().parse(JsonOutput.toJson(msg))
    hub.settle()
}

void ack(String sn) { ws([devicesn: sn, method: "control-reply", reported: [:]]) }

check("discovery lists fans, skips other devices, adds rooms") {
    app.settings.email = "me@example.com"
    app.state.emailUsed = "me@example.com"  // as after the main page
    app.state.passwordHash = "x"
    app.state.token = "tok"
    app.discoverDevices([list: [
        [deviceId: "1", sn: "SN1", model: "DR-HPF008S", deviceName: "Office Fan", seriesName: "PolyFan 508S"],
        [deviceId: "2", sn: "SN2", model: "DR-HCF007S", deviceName: "Bedroom Fan"],
        [deviceId: "3", sn: "SN3", model: "DR-HAP001S", deviceName: "Purifier"],
        [deviceId: null, sn: "SN4", model: "DR-HTF011S"],
    ]], [:])
    assert app.state.discovered.keySet() == ["SN1", "SN2"] as Set
    assert app.state.unsupported == ["Purifier (DR-HAP001S)"]
    assert app.callsNamed("asynchttpGet").last()[1] == "apiGetDone"
    app.discoverRooms([familyRooms: [[rooms: [[roomName: "Office", devices: [[sn: "SN1"]]]]]]], [:])
    hub.settle()
    assert app.state.discovered.SN1.room == "Office"
    assert !app.state.discovered.SN2.room
    assert !app.state.discovering && app.state.discoveredAt
}

check("pages render") {
    app.mainPage()
    app.fansPage()
}

check("Done creates fan devices and the connection, then connects") {
    app.settings.selectedFans = ["SN1", "SN2"]
    app.updated()
    hub.settle()
    assert child("dreo-SN1").driverName == "Dreo Fan"
    assert child("dreo-SN1").getDataValue("model") == "DR-HPF008S"
    assert child("dreo-SN1").displayName == "Office Fan"
    assert child("dreo-SN2").driverName == "Dreo Ceiling Fan"
    assert conn().driverName == "Dreo Cloud Connection"
    assert app.callsNamed("runEvery30Minutes")
    // Each new fan asked for a REST refresh (installed -> refresh).
    assert app.callsNamed("asynchttpGet").count { it[3]?.cb == "fanStateDone" } >= 2
    List c = conn().driver.callsNamed("ws.connect")
    assert c.size() == 1 && c[0][1].startsWith("wss://wsb-us.dreo-tech.com/websocket?accessToken=tok&timestamp=")
    assert c[0][2].pingInterval == 15
    assert conn().currentValue("connection") == "connecting"
}

check("socket open: connected, keepalive, catch-up poll") {
    conn().webSocketStatus("status: open")
    hub.settle()
    assert conn().currentValue("connection") == "connected"
    assert conn().driver.callsNamed("ws.send").last()[1] == "2"
    assert app.callsNamed("runIn").last()[2] == "pollAll"
}

check("pushed report updates the fan and creates the light ring child") {
    ws([devicesn: "SN1", method: "report", reported: [poweron: true, windlevel: 5, mode: 1, oscmode: 1,
        lightmode: 1, muteon: false, temperature: 72, tempunit: 1, atmon: true, atmbri: 3, atmcolor: 0x0000FF, atmmode: 1]])
    FakeDevice f = child("dreo-SN1")
    assert f.currentValue("switch") == "on"
    assert f.currentValue("speed") == "medium"
    assert f.currentValue("speedLevel") == 5
    assert f.currentValue("oscillation") == "horizontal"
    assert f.currentValue("temperature") == 72.0G
    FakeDevice ring = f.driver.getChildDevice("dreo-SN1-ring")
    assert ring?.driverName == "Dreo Light Ring"
    assert ring.currentValue("switch") == "on"
    assert ring.currentValue("level") == 100
    assert ring.currentValue("hue") == 67
    assert ring.currentValue("effectName") == "constant"
    assert f.driver.log.at("info").any { it.contains("speed is medium") }
}

check("fan command: sent once, shown straight away, cleared by the ack") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice f = child("dreo-SN1")
    f.setSpeed("high")
    hub.settle()
    assert f.currentValue("speed") == "high"
    List msgs = sent(from)
    assert msgs.size() == 1
    assert msgs[0].subMap(["devicesn", "method", "params"]) == [devicesn: "SN1", method: "control", params: [windlevel: 9]]
    // A second command waits for the first one's ack.
    f.setMode("sleep")
    hub.settle()
    assert sent(from).size() == 1
    ack("SN1")
    assert sent(from).size() == 2 && sent(from)[1].params == [mode: 3]
    ack("SN1")
    assert f.currentValue("mode") == "sleep"
}

check("setLevel: percent sets the nearest step and level snaps to that step") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice f = child("dreo-SN1")
    f.setLevel(50)
    hub.settle()
    assert sent(from)*.params == [[windlevel: 5]]  // sleep mode stays; only auto is left
    ack("SN1")
    ws([devicesn: "SN1", method: "report", reported: [windlevel: 5]])
    assert f.currentValue("level") == 56 && f.currentValue("speedLevel") == 5
    ws([devicesn: "SN1", method: "report", reported: [windlevel: 7]])
    assert f.currentValue("level") == 78
}

check("setLevel between steps: level snaps back to the fan's step") {
    FakeDevice f = child("dreo-SN1")
    assert f.currentValue("level") == 78  // step 7 of 9
    int events = f.events.size()
    f.setLevel(75)                         // nearest step is still 7
    hub.settle()
    ack("SN1")
    Map last = f.events.drop(events).findAll { it.name == "level" }[-1]
    assert last.value == 78 && last.isStateChange == true
    f.setLevel(35)                         // nearest step 3 = 33 %
    hub.settle()
    ack("SN1")
    assert f.currentValue("level") == 33 && f.currentValue("speedLevel") == 3
}

check("bad command input: warning, nothing sent") {
    int from = conn().driver.callsNamed("ws.send").size()
    child("dreo-SN1").setMode("reverse")
    hub.settle()
    assert sent(from).isEmpty()
    assert app.log.at("warn").any { it.contains("unsupported mode reverse") }
}

check("light ring child commands go through the fan") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice ring = child("dreo-SN1").driver.getChildDevice("dreo-SN1-ring")
    ring.setLevel(34)
    hub.settle()
    ack("SN1")
    ring.setEffect(2)
    hub.settle()
    ack("SN1")
    ring.off()
    hub.settle()
    ack("SN1")
    assert sent(from)*.params == [[atmbri: 1], [atmmode: 2], [atmon: false]]
    assert ring.currentValue("switch") == "off"
    assert ring.currentValue("effectName") == "circle"
}

check("rejected command (error_code) re-reads the fan") {
    int gets = app.callsNamed("asynchttpGet").size()
    child("dreo-SN1").on()
    hub.settle()
    ws([devicesn: "SN1", method: "control-report", reported: [error_code: 3, error_msg: "busy"]])
    assert conn().driver.log.at("warn").any { it.contains("busy") }
    assert app.callsNamed("asynchttpGet").size() == gets + 1
}

check("unacknowledged command: two retries, then refresh and reconnect") {
    int from = conn().driver.callsNamed("ws.send").size()
    child("dreo-SN1").setSpeedLevel(4)
    hub.settle()
    // A timer firing early (another command's schedule) only re-arms itself.
    int timers = conn().driver.callsNamed("runInMillis").size()
    conn().ackTimeout()
    assert sent(from)*.params == [[windlevel: 4]]
    assert conn().driver.callsNamed("runInMillis").size() == timers + 1
    hub.advance(2001)
    conn().ackTimeout()
    hub.advance(2001)
    conn().ackTimeout()
    assert sent(from)*.params == [[windlevel: 4]] * 3
    int gets = app.callsNamed("asynchttpGet").size()
    hub.advance(2001)
    conn().ackTimeout()
    hub.settle()
    assert app.callsNamed("asynchttpGet").size() == gets + 1
    assert conn().currentValue("connection") == "disconnected"
    assert app.callsNamed("runIn").last()[2] == "connectSocket"
    // Nothing in flight: a late timer does nothing.
    conn().ackTimeout()
    assert sent(from).size() == 3
}

check("ack must mention a key that was sent: a button-press report doesn't count") {
    conn().webSocketStatus("status: open")
    hub.settle()
    int from = conn().driver.callsNamed("ws.send").size()
    child("dreo-SN1").setSpeedLevel(3)
    child("dreo-SN1").setMode("sleep")
    hub.settle()
    assert sent(from)*.params == [[windlevel: 3]]
    ws([devicesn: "SN1", method: "control-report", reported: [childlockon: true]])
    assert sent(from).size() == 1               // still waiting for windlevel's ack
    ws([devicesn: "SN1", method: "control-report", reported: [windlevel: 3]])
    assert sent(from)*.params == [[windlevel: 3], [mode: 3]]
    ack("SN1")
}

check("rejected command: refresh, and the error isn't merged into state") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice f = child("dreo-SN1")
    f.setChildLock("off")
    hub.settle()
    int gets = app.callsNamed("asynchttpGet").size()
    ws([devicesn: "SN1", method: "control-report", reported: [error_code: 500003, error_msg: "instruction validate failed"]])
    assert app.callsNamed("asynchttpGet").size() == gets + 1
    assert !f.driver.state.dreo.containsKey("error_code")
    assert conn().driver.log.at("warn").any { it.contains("500003") }
}

check("commands queued while disconnected expire and refresh even if the socket never opens") {
    conn().disconnect()
    hub.settle()
    FakeDevice f = child("dreo-SN1")
    f.on()
    hub.settle()
    assert conn().driver.callsNamed("runIn").last()[2] == "expireQueue"
    int gets = app.callsNamed("asynchttpGet").size()
    conn().expireQueue()                        // too early: kept
    assert app.callsNamed("asynchttpGet").size() == gets
    hub.advance(31000)
    conn().expireQueue()
    hub.settle()
    assert app.callsNamed("asynchttpGet").size() == gets + 1
    assert conn().driver.log.at("warn").any { it.contains("not connected in time") }
}

check("reconnect, then a queued command goes out") {
    app.connectSocket()
    hub.settle()
    assert conn().currentValue("connection") == "connecting"
    int from = conn().driver.callsNamed("ws.send").size()
    child("dreo-SN1").off()
    hub.settle()
    assert sent(from).isEmpty()
    conn().webSocketStatus("status: open")
    hub.settle()
    assert sent(from)*.params == [[poweron: false]]
    ack("SN1")
}

check("ceiling fan: report creates the light child; gate closed shows everything off") {
    ws([devicesn: "SN2", method: "report", reported: [poweron: false, fanon: true, windlevel: 7, mode: 1,
        lighton: true, brightness: 60, colortemp: 100]])
    FakeDevice f = child("dreo-SN2")
    assert f.currentValue("switch") == "off"
    FakeDevice light = f.driver.getChildDevice("dreo-SN2-light")
    assert light?.driverName == "Dreo Ceiling Light"
    assert light.displayName == "Bedroom Fan Light"
    assert light.currentValue("switch") == "off"
    assert light.currentValue("colorTemperature") == 6500
    assert f.driver.getChildDevice("dreo-SN2-ring") == null
}

check("ceiling light on opens the gate and keeps the fan off") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice light = child("dreo-SN2").driver.getChildDevice("dreo-SN2-light")
    light.on()
    hub.settle()
    ack("SN2")
    assert sent(from)*.params == [[lighton: true, poweron: true, fanon: false]]
    assert light.currentValue("switch") == "on"
    assert child("dreo-SN2").currentValue("switch") == "off"
    light.setColorTemperature(2700, 50)
    hub.settle()
    ack("SN2")
    assert sent(from)[1].params == [colortemp: 0, brightness: 50]
    assert light.currentValue("level") == 50
    // Switching off the last live load closes the gate.
    light.off()
    hub.settle()
    ack("SN2")
    assert sent(from)[2].params == [lighton: false, poweron: false]
}

check("offline and online messages set healthStatus") {
    ws([devicesn: "SN2", method: "device-offline"])
    assert child("dreo-SN2").currentValue("healthStatus") == "offline"
    ws([devicesn: "SN2", method: "device-online", reported: [:]])
    assert child("dreo-SN2").currentValue("healthStatus") == "online"
    // Messages for unknown fans are ignored.
    ws([devicesn: "SN9", method: "report", reported: [poweron: true]])
}

check("REST refresh flattens mixed state") {
    app.fanStateDone([mixed: [poweron: [state: true], windlevel: [state: 2], mode: [state: 2],
        connected: [state: false]]], [sn: "SN1"])
    hub.settle()
    FakeDevice f = child("dreo-SN1")
    assert f.currentValue("speedLevel") == 2 && f.currentValue("mode") == "natural"
    assert f.currentValue("healthStatus") == "offline"
    app.fanStateDone(null, [sn: "SN1"])  // failed request: no change, no error
}

check("speed-step preference re-maps named speeds") {
    FakeDevice f = child("dreo-SN1")
    f.driver.settings.speedSteps = 4
    f.updated()
    hub.settle()
    assert f.currentValue("maxSpeedLevel") == 4
    assert f.currentValue("speed") == "medium-low"
}

check("humidifier: discovered with its limits, added with the humidifier driver") {
    app.discoverDevices([list: [
        [deviceId: "1", sn: "SN1", model: "DR-HPF008S", deviceName: "Office Fan", seriesName: "PolyFan 508S"],
        [deviceId: "2", sn: "SN2", model: "DR-HCF007S", deviceName: "Bedroom Fan"],
        [deviceId: "5", sn: "SN5", model: "DR-HHM001S", deviceName: "Nursery Humidifier", seriesName: "HM311S",
         controlsConf: [schedule: [modes: [
             [cmd: "mode", value: 1, controls: [[cmd: ["rhautolevel"], startValue: 30, endValue: 80]]],
             [cmd: "mode", value: 0, controls: [[cmd: ["foglevel"], startValue: 0, validValue: 1, endValue: 6]]],
         ]]]],
    ]], [:])
    app.discoverRooms(null, [:])
    hub.settle()
    assert app.state.discovered.SN5.limits == [humidityRange: "30-80", mistRange: "1-6"]
    app.settings.selectedFans = ["SN1", "SN2", "SN5"]
    app.updated()
    hub.settle()
    FakeDevice h = child("dreo-SN5")
    assert h.driverName == "Dreo Humidifier"
    assert h.displayName == "Nursery Humidifier"
    assert h.data.subMap(["model", "kind", "humidityRange", "mistRange"]) ==
        [model: "DR-HHM001S", kind: "humidifier", humidityRange: "30-80", mistRange: "1-6"]
    app.mainPage()
}

check("humidifier: REST state and pushed reports become events") {
    app.fanStateDone([mixed: [poweron: [state: true], mode: [state: 1], rh: [state: 47], rhautolevel: [state: 60],
        rhsleeplevel: [state: 70], foglevel: [state: 6], wrong: [state: 0], ledlevel: [state: 2],
        muteon: [state: false], rgblevel: [state: 2], suspend: [state: false], connected: [state: true]]], [sn: "SN5"])
    hub.settle()
    FakeDevice h = child("dreo-SN5")
    assert h.currentValue("humidity") == 47 && h.currentValue("targetHumidity") == 60
    assert h.currentValue("mode") == "auto" && h.currentValue("operatingState") == "humidifying"
    assert h.currentValue("waterTank") == "ok" && h.currentValue("healthStatus") == "online"
    ws([devicesn: "SN5", method: "control-report", reported: [rh: 55, suspend: true]])
    assert h.currentValue("humidity") == 55 && h.currentValue("operatingState") == "idle"
    ws([devicesn: "SN5", method: "report", reported: [wrong: 1]])
    assert h.currentValue("waterTank") == "empty"
    assert h.driver.log.at("info").any { it.contains("waterTank is empty") }
}

check("humidifier commands: sent, shown straight away, acked") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice h = child("dreo-SN5")
    h.setTargetHumidity(50)
    hub.settle()
    assert h.currentValue("targetHumidity") == 50
    ack("SN5")
    h.setMistLevel(3)
    hub.settle()
    assert h.currentValue("mode") == "manual" && h.currentValue("mistLevel") == 3
    ack("SN5")
    h.setMode("sleep")
    hub.settle()
    ack("SN5")
    assert h.currentValue("targetHumidity") == 70  // sleep has its own target
    h.setTargetHumidity(65)
    hub.settle()
    ack("SN5")
    h.setDisplay("off")
    hub.settle()
    ack("SN5")
    h.off()
    hub.settle()
    ack("SN5")
    assert sent(from)*.params == [[rhautolevel: 50], [foglevel: 3, mode: 0], [mode: 2], [rhsleeplevel: 65],
                                  [ledlevel: 0], [poweron: false]]
    assert sent(from)*.devicesn.unique() == ["SN5"]
    assert h.currentValue("switch") == "off" && h.currentValue("operatingState") == "off"
    h.setTargetHumidity(90)  // above the HM311S's 80
    hub.settle()
    assert sent(from).size() == 6
    assert app.log.at("warn").any { it.contains("target humidity must be 30-80") }
}

check("raw data button logs model data only: no owner, home, room, mac, ssid or full serial") {
    app.appButtonHandler("dumpRaw")
    Map req = app.callsNamed("asynchttpGet").last()[3]
    assert req.cb == "dumpDevices"
    app.dumpDevices([list: [[sn: "SN0005", model: "DR-HHM001S", mac: "aa:bb", owner: "owner@example.com",
        familyName: "Example Home", roomName: "Nursery", deviceName: "Nursery Humidifier",
        seriesName: "HM311S", controlsConf: [template: "DR-HHM001S"]]]], [:])
    assert app.callsNamed("asynchttpGet").last()[3].data == [sn: "SN0005", model: "DR-HHM001S"]
    app.dumpState([mixed: [worktime: [state: 12], filtertime: [state: 75], wifi_ssid: [state: "home"],
        module_hardware_mac: [state: "aa:bb"], ip: [state: "10.0.0.9"], module_ipaddr: [state: "10.0.0.9"]]],
        [sn: "SN0005", model: "DR-HHM001S"])
    List info = app.log.at("info")
    assert info.any { it == 'Dreo raw device DR-HHM001S ...0005: {"model":"DR-HHM001S","seriesName":"HM311S","controlsConf":{"template":"DR-HHM001S"}}' }
    assert info.any { it == 'Dreo raw state DR-HHM001S ...0005: {"filtertime":75,"worktime":12}' }
    assert !info.findAll { it.startsWith("Dreo raw") }.any { it =~ /aa:bb|home|Example Home|owner@|Nursery|10\.0\.0\.9|SN0005/ }
}

/** A login response as asynchttpPost hands it to loginDone. */
class FakeResp {
    int status = 200
    Map json
    Map getJson() { json }
    boolean hasError() { status != 200 }
    String getErrorMessage() { "HTTP ${status}" }
}

check("token expiry: concurrent 401s share one sign-in, then every request is replayed") {
    hub.advance(61000)                        // any earlier sign-in is long over
    app.state.region = "NA"
    int posts = app.callsNamed("asynchttpPost").size()
    int gets = app.callsNamed("asynchttpGet").size()
    // Two fan refreshes come back 401 with the same (expired) token.
    Map r1 = [path: "/api/user-device/device/state", query: [deviceSn: "SN1"], cb: "fanStateDone", data: [sn: "SN1"], retried: false, tokenAt: app.state.tokenAt]
    Map r2 = [path: "/api/user-device/device/state", query: [deviceSn: "SN2"], cb: "fanStateDone", data: [sn: "SN2"], retried: false, tokenAt: app.state.tokenAt]
    app.apiGetDone(new FakeResp(status: 401), r1)
    app.apiGetDone(new FakeResp(status: 401), r2)
    assert app.callsNamed("asynchttpPost").size() == posts + 1
    Map ctx = app.callsNamed("asynchttpPost").last()[3]
    app.loginDone(new FakeResp(json: [code: 0, data: [access_token: "tok2", region: "NA"]]), ctx)
    hub.settle()
    List replays = app.callsNamed("asynchttpGet").drop(gets).collect { it[3] }
    assert replays*.data == [[sn: "SN1"], [sn: "SN2"]]
    assert app.callsNamed("asynchttpGet").drop(gets).every { it[2].headers.Authorization == "Bearer tok2" }
    // A third 401 sent with the old token just replays with the new one.
    Map r3 = r1 + [tokenAt: 1L]
    app.apiGetDone(new FakeResp(status: 401), r3)
    assert app.callsNamed("asynchttpPost").size() == posts + 1
    assert app.callsNamed("asynchttpGet").last()[3].retried == true
}

check("a command with an empty value warns instead of throwing") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice f = child("dreo-SN2")
    f.setLevel(null)
    f.setSpeedLevel(null)
    hub.settle()
    assert sent(from).isEmpty()
    assert app.log.at("warn").count { it.contains("needs a value") } == 2
}

check("unchanged values in a report send no events") {
    FakeDevice f = child("dreo-SN2")
    ws([devicesn: "SN2", method: "report", reported: [windlevel: 5]])
    int n = f.events.size()
    ws([devicesn: "SN2", method: "report", reported: [windlevel: 5]])
    assert f.events.size() == n
    FakeDevice light = f.driver.getChildDevice("dreo-SN2-light")
    int ln = light.events.size()
    ws([devicesn: "SN2", method: "report", reported: [brightness: light.currentValue("level")]])
    assert light.events.size() == ln
}

check("unticking a fan removes it") {
    app.settings.selectedFans = ["SN2"]
    app.updated()
    hub.settle()
    assert child("dreo-SN1") == null && child("dreo-SN2") && conn()
    app.settings.selectedFans = []
    app.updated()
    hub.settle()
    assert child("dreo-SN2") == null
    assert conn().driver.state.wanted == false
}

Check.finish()
