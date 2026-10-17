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

check("setLevel: percent sets the step and the picked percentage stays") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice f = child("dreo-SN1")
    f.setLevel(50)
    hub.settle()
    assert sent(from)*.params == [[windlevel: 5]]  // sleep mode stays; only auto is left
    ack("SN1")
    ws([devicesn: "SN1", method: "report", reported: [windlevel: 5]])
    assert f.currentValue("level") == 50 && f.currentValue("speedLevel") == 5
    ws([devicesn: "SN1", method: "report", reported: [windlevel: 7]])
    assert f.currentValue("level") == 78
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
    Closure lastTimeout = { conn().driver.callsNamed("runInMillis").last()[3].data }
    conn().ackTimeout(lastTimeout())
    conn().ackTimeout(lastTimeout())
    assert sent(from)*.params == [[windlevel: 4]] * 3
    int gets = app.callsNamed("asynchttpGet").size()
    conn().ackTimeout(lastTimeout())
    hub.settle()
    assert app.callsNamed("asynchttpGet").size() == gets + 1
    assert conn().currentValue("connection") == "disconnected"
    assert app.callsNamed("runIn").last()[2] == "connectSocket"
    // A stale timeout for an old command is ignored.
    conn().ackTimeout([id: -1])
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
