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
    app.settings.passwordMd5 = "x"
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

check("Look again lists the devices again, once at a time") {
    int gets = app.callsNamed("asynchttpGet").size()
    app.appButtonHandler("rediscover")
    assert app.state.discovering
    assert app.callsNamed("asynchttpGet").size() == gets + 1
    app.appButtonHandler("rediscover")     // already looking: no second request
    assert app.callsNamed("asynchttpGet").size() == gets + 1
    app.discoverDevices([list: [
        [deviceId: "1", sn: "SN1", model: "DR-HPF008S", deviceName: "Office Fan", seriesName: "PolyFan 508S"],
        [deviceId: "2", sn: "SN2", model: "DR-HCF007S", deviceName: "Bedroom Fan"],
    ]], [:])
    app.discoverRooms([familyRooms: [[rooms: [[roomName: "Office", devices: [[sn: "SN1"]]]]]]], [:])
    hub.settle()
    assert !app.state.discovering && app.state.discovered.keySet() == ["SN1", "SN2"] as Set
}

check("pages render") {
    app.mainPage()
    app.fansPage()
}

check("Choose devices names the drivers the ticked devices need") {
    String note = app.driversNote(app.state.discovered, ["SN1", "SN2"])
    assert note.contains("<b>Dreo Fan</b>: Office Fan")
    assert note.contains("<b>Dreo Ceiling Fan</b>: Bedroom Fan")
    assert note.contains("<b>Dreo Ceiling Fan Light</b>: Bedroom Fan")
    assert note.contains("<b>Dreo Fan Light Ring</b>: Office Fan, Bedroom Fan")
    assert app.driversNote(app.state.discovered, []) == null
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
    assert ring?.driverName == "Dreo Fan Light Ring" && ring.name == "Dreo Fan Light Ring"
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

check("speed number from the 1-12 dropdown: above the top speed uses the top one") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice f = child("dreo-SN1")
    f.setSpeedLevel("5")
    hub.settle()
    ack("SN1")
    f.setSpeedLevel("12")                  // DR-HPF008S tops out at 9
    hub.settle()
    ack("SN1")
    assert sent(from)*.params == [[windlevel: 5], [windlevel: 9]]  // sleep mode stays; only auto is left
    assert f.currentValue("speedLevel") == 9 && f.currentValue("speed") == "high"
    assert f.currentValue("level") == null
}

check("saved head positions: save, recall by slot or name, delete") {
    FakeDevice f = child("dreo-SN1")
    int from = conn().driver.callsNamed("ws.send").size()
    f.savePosition("1")                    // nothing reported yet
    assert f.driver.log.at("warn").any { it.contains("no head position reported") }
    ws([devicesn: "SN1", method: "report", reported: [fixedconf: "-15,10", cruiseconf: "90,60,-30,-60"]])
    assert f.currentValue("headVertical") == -15 && f.currentValue("headHorizontal") == 10
    assert f.currentValue("oscillationLeft") == -60
    f.savePosition("1", "Desk")
    hub.settle()
    f.setHeadPosition(30, -40)
    hub.settle()
    ack("SN1")
    assert f.currentValue("headVertical") == 30
    f.savePosition("2")
    hub.settle()
    assert new JsonSlurper().parseText(f.currentValue("savedPositions")) ==
        ["1": [name: "Desk", vertical: -15, horizontal: 10], "2": [name: "Position 2", vertical: 30, horizontal: -40]]
    f.goToPosition("1")
    hub.settle()
    ack("SN1")
    f.goToPosition("position 2")           // a rule may use the name
    hub.settle()
    ack("SN1")
    f.goToPosition("3")
    hub.settle()
    assert f.driver.log.at("warn").any { it.contains("no saved position 3") }
    f.deletePosition("1")
    hub.settle()
    assert new JsonSlurper().parseText(f.currentValue("savedPositions")).keySet() == ["2"] as Set
    // The fan is oscillating (oscmode 1 from the first report): positions stop it.
    assert sent(from)*.params == [[fixedconf: "30,-40", oscmode: 0], [fixedconf: "-15,10"], [fixedconf: "30,-40"]]
    f.setOscillationRange(-45, 45)
    hub.settle()
    ack("SN1")
    assert sent(from).last().params == [cruiseconf: "90,45,-30,-45"]
    assert f.currentValue("oscillationRight") == 45
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
    conn().connect("wss://x")
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
    int timers = conn().driver.callsNamed("runIn").size()
    f.on()
    hub.settle()
    assert conn().driver.callsNamed("runIn").drop(timers)*.getAt(2).contains("expireQueue")
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

check("commands waiting for the same device merge into one") {
    int from = conn().driver.callsNamed("ws.send").size()
    FakeDevice f = child("dreo-SN1")
    f.setSpeedLevel("3")                   // goes out (and switches the fan back on)
    hub.settle()
    f.setSpeedLevel("4")                   // waits
    hub.settle()
    child("dreo-SN2").setMode("natural")   // waits, another device
    hub.settle()
    f.setMode("normal")                    // joins SN1's waiting command
    f.setSpeedLevel("6")                   // and replaces its speed
    hub.settle()
    assert sent(from)*.params == [[poweron: true, windlevel: 3]]
    ack("SN1")
    assert sent(from)[1].devicesn == "SN1" && sent(from)[1].params == [windlevel: 6, mode: 1]
    ack("SN1")
    assert sent(from)[2].devicesn == "SN2" && sent(from).size() == 3
    ack("SN2")
    assert f.currentValue("speedLevel") == 6 && f.currentValue("mode") == "normal"
}

check("no acknowledgement while Dreo still sends other messages: no reconnect") {
    FakeDevice f = child("dreo-SN1")
    int gets = app.callsNamed("asynchttpGet").size()
    f.setSpeedLevel("2")
    hub.settle()
    hub.advance(10)
    ws([devicesn: "SN2", method: "report", reported: [windlevel: 4]])  // the link works
    3.times {
        hub.advance(2100)
        conn().ackTimeout()
    }
    hub.settle()
    assert conn().driver.log.at("warn").last().contains("did not acknowledge command [windlevel:2] for SN1")
    assert conn().currentValue("connection") == "connected"
    assert app.callsNamed("asynchttpGet").size() == gets + 1  // SN1 is re-read
}

check("ceiling fan: report creates the light child; gate closed shows everything off") {
    ws([devicesn: "SN2", method: "report", reported: [poweron: false, fanon: true, windlevel: 7, mode: 1,
        lighton: true, brightness: 60, colortemp: 100]])
    FakeDevice f = child("dreo-SN2")
    assert f.currentValue("switch") == "off"
    FakeDevice light = f.driver.getChildDevice("dreo-SN2-light")
    assert light?.driverName == "Dreo Ceiling Fan Light"
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

/** A REST state answer for a fan, as fanStateDone gets it. */
Map restState(Map s) { [mixed: s.collectEntries { k, v -> [(k): [state: v]] }] }

check("an offline device: the command waits for a refresh, and goes out if the device is online after all") {
    ws([devicesn: "SN2", method: "device-offline"])
    int from = conn().driver.callsNamed("ws.send").size()
    int gets = app.callsNamed("asynchttpGet").size()
    FakeDevice f = child("dreo-SN2")
    f.setSpeedLevel("3")
    f.setSpeedLevel("4")                   // a second command doesn't re-read again
    hub.settle()
    assert sent(from).isEmpty()
    assert app.callsNamed("asynchttpGet").drop(gets)*.get(3)*.data*.sn == ["SN2"]
    assert app.callsNamed("runIn").last()[2] == "expireHeld"
    // The device-online push was missed: the refresh shows it online.
    app.fanStateDone(restState([poweron: true, fanon: true, windlevel: 1, connected: true]), [sn: "SN2"])
    hub.settle()
    assert f.currentValue("healthStatus") == "online"
    assert f.currentValue("speedLevel") == 4
    // Both go out, in order, the second after the first's ack.
    assert sent(from).size() == 1 && sent(from)[0].devicesn == "SN2" && sent(from)[0].params.windlevel == 3
    ack("SN2")
    assert sent(from).size() == 2 && sent(from)[1].params.windlevel == 4
    ack("SN2")
    // Nothing is left to send by a later refresh.
    app.fanStateDone(restState([windlevel: 4, connected: true]), [sn: "SN2"])
    hub.settle()
    assert sent(from).size() == 2
}

check("an offline device: the command is dropped with a warning if it is still offline") {
    ws([devicesn: "SN2", method: "device-offline"])
    int from = conn().driver.callsNamed("ws.send").size()
    child("dreo-SN2").setSpeedLevel("2")
    hub.settle()
    app.fanStateDone(restState([windlevel: 4, connected: false]), [sn: "SN2"])
    hub.settle()
    assert sent(from).isEmpty()
    assert app.log.at("warn").last() == "Dreo: Bedroom Fan is offline in Dreo's cloud; setSpeedLevel not sent"
    assert child("dreo-SN2").currentValue("healthStatus") == "offline"
    // A failed refresh drops it too.
    child("dreo-SN2").setSpeedLevel("2")
    hub.settle()
    app.fanStateDone(null, [sn: "SN2"])
    assert app.log.at("warn").last() == "Dreo: could not check whether Bedroom Fan is online; setSpeedLevel not sent"
    assert sent(from).isEmpty()
}

check("an offline device: a held command whose refresh never answers expires") {
    int from = conn().driver.callsNamed("ws.send").size()
    int gets = app.callsNamed("asynchttpGet").size()
    child("dreo-SN2").setSpeedLevel("5")
    hub.settle()
    assert app.callsNamed("asynchttpGet").size() == gets + 1
    app.expireHeld([sn: "SN2"])            // too early: kept
    assert !app.log.at("warn").last().contains("no answer")
    hub.advance(31000)
    app.expireHeld([sn: "SN2"])
    assert app.log.at("warn").last() == "Dreo: no answer from Dreo for Bedroom Fan; setSpeedLevel not sent"
    // A late answer sends nothing, and the next command re-reads again.
    app.fanStateDone(restState([connected: true]), [sn: "SN2"])
    hub.settle()
    assert sent(from).isEmpty()
    assert child("dreo-SN2").currentValue("healthStatus") == "online"
}

check("cloud connection down for 2 minutes: every device offline until it reconnects") {
    FakeDevice f1 = child("dreo-SN1"), f2 = child("dreo-SN2")
    ws([devicesn: "SN1", method: "device-online", reported: [:]])
    assert f1.currentValue("healthStatus") == "online" && f2.currentValue("healthStatus") == "online"
    conn().webSocketStatus("failure: connection reset")
    hub.settle()
    assert app.callsNamed("runIn").find { it[2] == "cloudCheck" }?.get(1) == 120
    // A short drop changes nothing.
    hub.advance(60000)
    conn().connect("wss://x")               // (pretend the reconnect worked)
    conn().webSocketStatus("status: open")
    hub.settle()
    hub.advance(61000)
    app.cloudCheck()
    hub.settle()
    assert f1.currentValue("healthStatus") == "online"
    // A long one does, also when the reconnects keep failing.
    conn().webSocketStatus("failure: connection reset")
    hub.settle()
    hub.advance(90000)
    conn().connect("wss://x")
    conn().webSocketStatus("failure: still down")
    hub.settle()
    assert f1.currentValue("healthStatus") == "online"
    hub.advance(31000)
    app.cloudCheck()
    hub.settle()
    assert f1.currentValue("healthStatus") == "offline" && f2.currentValue("healthStatus") == "offline"
    assert app.log.at("warn").last().startsWith("Dreo: cloud connection down (not connected for 2 minutes)")
    // A refresh while it is down keeps the device offline.
    app.fanStateDone(restState([connected: true]), [sn: "SN1"])
    hub.settle()
    assert f1.currentValue("healthStatus") == "offline"
    // Back: the catch-up poll restores each device's real status.
    conn().connect("wss://x")
    conn().webSocketStatus("status: open")
    hub.settle()
    assert !app.state.cloudDown && app.callsNamed("runIn").last()[2] == "pollAll"
    app.fanStateDone(restState([connected: true]), [sn: "SN1"])
    app.fanStateDone(restState([connected: false]), [sn: "SN2"])
    hub.settle()
    assert f1.currentValue("healthStatus") == "online" && f2.currentValue("healthStatus") == "offline"
    ws([devicesn: "SN2", method: "device-online", reported: [:]])
}

check("Dreo rejecting the sign-in while disconnected marks every device offline") {
    conn().webSocketStatus("failure: unauthorized")
    hub.settle()
    app.loginDone(new FakeResp(json: [code: 10002, msg: "wrong password"]), [region: "NA", depth: 0])
    hub.settle()
    assert child("dreo-SN1").currentValue("healthStatus") == "offline"
    assert child("dreo-SN2").currentValue("healthStatus") == "offline"
    app.state.remove("authFailedAt")
    conn().connect("wss://x")
    conn().webSocketStatus("status: open")
    hub.settle()
    assert !app.state.cloudDown
    ws([devicesn: "SN1", method: "device-online", reported: [:]])
    ws([devicesn: "SN2", method: "device-online", reported: [:]])
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

check("devices keep no network identifiers or noisy keys; firmware becomes a data value") {
    app.fanStateDone([mixed: [wifi_ssid: [state: "home"], module_hardware_mac: [state: "aa:bb"], wifi_rssi: [state: -43],
        network_latency: [state: 30], module_firmware_version: [state: "3.2.2"], mcu_firmware_version: [state: "1.0.5"],
        ip: [state: "10.0.0.5"], windlevel: [state: 3]]], [sn: "SN1"])
    hub.settle()
    FakeDevice f = child("dreo-SN1")
    Map s = f.driver.state.dreo
    assert s.windlevel == 3 && s.poweron == true
    assert !s.keySet().any { it in ["wifi_ssid", "module_hardware_mac", "wifi_rssi", "network_latency", "ip",
        "module_firmware_version", "mcu_firmware_version"] }
    assert f.getDataValue("firmware") == "3.2.2 / 1.0.5"
    // Keys stored by an earlier version go at the next update.
    f.driver.state.dreo = s + [wifi_ssid: "home"]
    ws([devicesn: "SN1", method: "report", reported: [windlevel: 4]])
    assert !f.driver.state.dreo.containsKey("wifi_ssid")
}

check("a leftover percentage level from before 0.2.0 is cleared") {
    FakeDevice f = child("dreo-SN1")
    f.current.level = 56
    ws([devicesn: "SN1", method: "report", reported: [windlevel: 2]])  // back to step 2 for the next check
    assert !f.current.containsKey("level")
}

check("debug logging turns itself off after 30 minutes") {
    app.settings.logEnable = true
    app.updated()
    hub.settle()
    assert app.callsNamed("runIn").any { it[1] == 1800 && it[2] == "logsOff" }
    app.logsOff()
    assert app.settings.logEnable.toString() == "false"
    FakeDevice f = child("dreo-SN1")
    f.driver.settings.logEnable = true
    f.updated()
    assert f.driver.callsNamed("runIn").any { it[1] == 1800 && it[2] == "logsOff" }
    f.logsOff()
    assert f.driver.settings.logEnable.toString() == "false"
    conn().driver.settings.logEnable = true
    conn().updated()
    assert conn().driver.callsNamed("runIn").any { it[2] == "logsOff" }
}

check("version check: matching drivers pass; an older driver is named on the main page") {
    app.updated()
    hub.settle()
    assert app.state.driverVersions == []
    assert child("dreo-SN1").getDataValue("driverVersion") == app.VERSION
    assert child("dreo-SN1").driver.getChildDevice("dreo-SN1-ring").getDataValue("driverVersion") == app.VERSION
    // A device whose driver predates dreoHello.
    FakeDevice old = new FakeDevice(id: 999, deviceNetworkId: "dreo-OLD", name: "Old Fan", data: [sn: "OLD"])
    app.children << old
    app.settings.selectedFans = (app.settings.selectedFans ?: []) + ["OLD"]
    app.updated()
    hub.settle()
    assert app.state.driverVersions == ["Old Fan (older)"]
    assert app.log.at("warn").any { it.contains("Old Fan (older)") }
    app.mainPage()
    app.children.remove(old)
    app.settings.selectedFans = app.settings.selectedFans - ["OLD"]
    app.updated()
    hub.settle()
    assert app.state.driverVersions == []
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
    // New limits from a later discovery reach the existing device on Done.
    Map found = app.state.discovered
    found.SN5.limits = [humidityRange: "35-85", mistRange: "1-6"]
    app.state.discovered = found
    app.updated()
    hub.settle()
    assert h.getDataValue("humidityRange") == "35-85"
    found.SN5.limits = [humidityRange: "30-80", mistRange: "1-6"]
    app.state.discovered = found
    app.updated()
    hub.settle()
    assert h.getDataValue("humidityRange") == "30-80"
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
    String tag = "#" + java.security.MessageDigest.getInstance("MD5").digest("SN0005".bytes).encodeHex().toString().take(6)
    assert info.any { it == "Dreo raw device DR-HHM001S ${tag}: " + '{"model":"DR-HHM001S","seriesName":"HM311S","controlsConf":{"template":"DR-HHM001S"}}' }
    assert info.any { it == "Dreo raw state DR-HHM001S ${tag}: " + '{"filtertime":75,"worktime":12}' }
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
    f.setSpeedLevel(null)
    f.setMode(null)
    hub.settle()
    assert sent(from).isEmpty()
    assert app.log.at("warn").any { it.contains("speed must be a number, not null") }
    assert app.log.at("warn").any { it.contains("unsupported mode null") }
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

check("the password hash is kept in a password-type setting, not in state") {
    String h = "5ebe2294ecd0e0f08eab7690d2a6ee69"  // MD5 of "secret"
    app.settings.password = "secret"
    app.mainPage()
    hub.settle()
    assert app.settings.passwordMd5 == h && !app.settings.password
    assert app.app.settingTypes.passwordMd5 == "password"
    assert !JsonOutput.toJson(app.state).contains(h)
}

check("a hash left in state by 0.2 moves to the setting") {
    app.app.removeSetting("passwordMd5")
    app.state.passwordHash = "oldhash"
    hub.settle()
    app.updated()
    hub.settle()
    assert app.settings.passwordMd5 == "oldhash" && app.app.settingTypes.passwordMd5 == "password"
    assert !app.state.containsKey("passwordHash")
    app.finishLogin()
    app.state.remove("authFailedAt")
    app.login(null, [:])
    assert app.callsNamed("asynchttpPost").last()[2].body.password == "oldhash"
}

check("Done notes a device whose driver isn't installed, and clears it once it is") {
    File saved = hub.driverFiles.remove("Dreo Ceiling Fan")
    app.settings.selectedFans = ["SN2"]
    app.updated()
    hub.settle()
    assert !child("dreo-SN2")
    assert app.state.missingDrivers == ["Bedroom Fan needs 'Dreo Ceiling Fan'"]
    hub.driverFiles["Dreo Ceiling Fan"] = saved
    app.updated()
    hub.settle()
    assert child("dreo-SN2") && !app.state.missingDrivers
    app.settings.selectedFans = []
    app.updated()
    hub.settle()
    assert !child("dreo-SN2")
}

// ---- socket handling (the hub's memory-leak hunt, 2026-10-23): never leave an old socket open

/** Calls made by the connection driver since `from`, by name. */
List connCalls(int from) { conn().driver.calls.drop(from).collect { it[0] } }
Map connMem() { conn().driver.getClass().MEM[conn().idAsLong] }
/** Drops reported to the app so far. */
int drops() { app.log.at("info").count { it.contains("cloud connection closed") } }
int polls() { app.callsNamed("runIn").count { it[2] == "pollAll" } }

/**
 * Every check below starts from the same place, whatever the earlier checks
 * left: a socket the app wants, just connected and opened, and past the grace
 * for the old socket's close notice.
 */
void opened() {
    conn().driver.getClass().MEM.remove(conn().idAsLong)
    conn().connect("wss://x")
    conn().webSocketStatus("status: open")
    hub.settle()
    hub.advance(3000)
    assert conn().currentValue("connection") == "connected" && connMem().phase == "open"
}

/** From opened(): the socket has dropped and the app's reconnect is pending. */
void dropped() {
    opened()
    conn().webSocketStatus("failure: connection reset")
    hub.settle()
    assert conn().currentValue("connection") == "disconnected"
}

check("connect always closes any old socket first, even after new code emptied the driver's memory") {
    opened()
    conn().driver.getClass().MEM.clear()          // new code or a restart: phase unknown, socket maybe open
    int from = conn().driver.calls.size()
    conn().connect("wss://x")
    List c = connCalls(from)
    assert c.indexOf("ws.close") >= 0 && c.indexOf("ws.close") < c.indexOf("ws.connect")
    assert conn().driver.callsNamed("runIn").last()[1..2] == [30, "connectTimeout"]
}

check("the old socket's close notice is ignored while connecting; the new socket's own then counts") {
    opened()
    conn().driver.getClass().MEM.clear()
    conn().connect("wss://x")
    int before = drops()
    conn().webSocketStatus("status: closing")
    hub.settle()
    assert conn().currentValue("connection") == "connecting"
    assert drops() == before, "no reconnect scheduled"
    conn().webSocketStatus("status: open")
    hub.settle()
    assert conn().currentValue("connection") == "connected"
    conn().webSocketStatus("status: closing")      // the old one's was used up: this is the new socket's
    hub.settle()
    assert conn().currentValue("connection") == "disconnected" && drops() == before + 1
}

check("the old socket's close notice delivered just after the new socket opened is ignored, once") {
    opened()
    conn().driver.getClass().MEM.clear()           // new code: connect() closes the live socket first
    conn().connect("wss://x")
    hub.settle()
    int before = drops()
    conn().webSocketStatus("status: open")
    conn().webSocketStatus("status: closing")      // the old socket's notice, delivered late
    hub.settle()
    assert conn().currentValue("connection") == "connected" && drops() == before
    conn().webSocketStatus("status: closing")      // a second one can only be the new socket's
    hub.settle()
    assert conn().currentValue("connection") == "disconnected" && drops() == before + 1
}

check("with nothing to close, a close right after the open is the new socket's: a drop, not ignored") {
    dropped()                                      // the failed socket is closed and gone
    conn().connect("wss://x")
    int before = drops()
    conn().webSocketStatus("status: open")
    conn().webSocketStatus("status: closing")      // e.g. Dreo rejecting the token after the handshake
    hub.settle()
    assert conn().currentValue("connection") == "disconnected" && drops() == before + 1
}

check("new code while a connect was out: the open is taken, and the old socket's late close notice ignored") {
    opened()
    conn().connect("wss://x")                      // closes the live socket, opens a new one
    conn().driver.getClass().MEM.clear()           // driver code saved meanwhile
    int before = drops()
    conn().webSocketStatus("status: open")
    hub.settle()
    assert conn().currentValue("connection") == "connected" && connMem().phase == "open"
    conn().webSocketStatus("status: closing")      // the socket connect() closed
    hub.settle()
    assert conn().currentValue("connection") == "connected" && drops() == before
}

check("a connect while connected, or while another is in progress, opens nothing new") {
    opened()
    int from = conn().driver.calls.size()
    conn().connect("wss://x")
    assert !connCalls(from).contains("ws.connect")
    conn().webSocketStatus("failure: connection reset")
    hub.settle()
    conn().connect("wss://x")
    from = conn().driver.calls.size()
    conn().connect("wss://x")                      // still within the connect timeout
    assert !connCalls(from).contains("ws.connect")
}

check("a connect asked for during another attempt opens nothing; the attempt's failure is reported as usual") {
    dropped()
    conn().connect("wss://a")
    int from = conn().driver.calls.size()
    conn().connect("wss://b")                      // e.g. Done while the first attempt is still out
    assert !connCalls(from).contains("ws.connect")
    int before = drops()
    conn().connectTimeout()
    hub.settle()
    assert conn().currentValue("connection") == "disconnected"
    assert drops() == before + 1, "counted and backed off like any failure"
    assert app.callsNamed("runIn").last()[2] == "connectSocket", "the app's reconnect builds a URL from the current token"
}

check("a failure closes the socket and is reported once, even with a close notice after it") {
    opened()
    int before = drops()
    int from = conn().driver.calls.size()
    conn().webSocketStatus("failure: connection reset")
    conn().webSocketStatus("status: closing")
    hub.settle()
    assert connCalls(from).contains("ws.close"), "the failed socket is released"
    assert drops() == before + 1
}

check("a connect that never answers times out and is handled as a drop; an answer after that is closed again") {
    dropped()
    conn().connect("wss://x")
    hub.settle()
    int before = drops()
    conn().connectTimeout()
    hub.settle()
    assert conn().currentValue("connection") == "disconnected"
    assert app.log.at("info").last().contains("no answer from Dreo in 30 s")
    assert drops() == before + 1
    int from = conn().driver.calls.size()
    int polled = polls()
    conn().webSocketStatus("status: open")         // Dreo answered after all
    hub.settle()
    assert connCalls(from).contains("ws.close")
    assert conn().currentValue("connection") == "disconnected" && connMem().phase == "closed"
    assert !conn().driver.calls.drop(from).any { it[0] == "runIn" && it[2] == "keepAlive" }
    assert polls() == polled
}

check("disconnect while a connect is still out: the socket is closed when it opens, not kept") {
    dropped()
    conn().connect("wss://x")
    conn().disconnect()
    hub.settle()
    int from = conn().driver.calls.size()
    int polled = polls()
    conn().webSocketStatus("status: open")
    hub.settle()
    assert connCalls(from).contains("ws.close")
    assert conn().currentValue("connection") == "disconnected"
    assert polls() == polled
    assert conn().driver.state.wanted == false
}

check("new code with the socket still up: incoming data takes it over, with the app told as on an open") {
    opened()
    conn().driver.state.failures = 2
    hub.settle()
    conn().driver.getClass().MEM.clear()
    int from = conn().driver.callsNamed("ws.send").size()
    int polled = polls()
    ws([devicesn: "SN1", method: "report", reported: [windlevel: 3]])
    assert connMem().phase == "open"
    assert conn().currentValue("connection") == "connected"
    assert conn().driver.callsNamed("ws.send").drop(from)*.getAt(1).contains("2"), "keepalive running again"
    assert conn().driver.state.failures == 0
    assert polls() == polled + 1
}

check("a command after new code asks for a socket, even while the attribute still says connecting") {
    dropped()
    conn().connect("wss://x")                      // attribute: connecting
    conn().driver.getClass().MEM.clear()           // new code during the attempt
    int from = conn().driver.calls.size()
    app.state.token = "tok"                        // (an earlier check dropped it)
    conn().sendCommand("SN1", [poweron: true])
    hub.settle()
    assert connCalls(from).contains("ws.connect")
    assert connMem().phase == "connecting"
}

// ---- light children and their drivers

check("a light ring created before 0.3 gets today's name on Done") {
    app.settings.selectedFans = ["SN1"]
    app.updated()
    hub.settle()
    ws([devicesn: "SN1", method: "report", reported: [atmon: true]])
    FakeDevice ring = child("dreo-SN1").driver.getChildDevice("dreo-SN1-ring")
    ring.name = "Dreo Light Ring"                  // as created by 0.2
    app.updated()
    hub.settle()
    assert ring.name == "Dreo Fan Light Ring"
}

check("a fan whose light ring driver is missing says so once, and on the app's page, until Done after it is installed") {
    FakeDevice f = child("dreo-SN1")
    f.driver.deleteChildDevice("dreo-SN1-ring")
    File saved = hub.driverFiles.remove("Dreo Fan Light Ring")
    int errors = app.log.at("error").size()
    int adds = f.driver.callsNamed("addChildDevice").size()
    ws([devicesn: "SN1", method: "report", reported: [atmon: true, atmbri: 2]])
    ws([devicesn: "SN1", method: "report", reported: [atmon: false]])
    assert app.log.at("error").size() == errors + 1, "logged once, not on every report"
    assert app.log.at("error").last().contains("could not add the 'Dreo Fan Light Ring' device for Office Fan")
    assert f.getDataValue("missingDrivers") == "Dreo Fan Light Ring"
    assert app.missingChildDrivers() == ["Office Fan needs 'Dreo Fan Light Ring'"]
    hub.driverFiles["Dreo Fan Light Ring"] = saved
    ws([devicesn: "SN1", method: "report", reported: [atmon: true]])
    assert !f.driver.getChildDevice("dreo-SN1-ring"), "not tried again until Done"
    assert f.driver.callsNamed("addChildDevice").size() == adds
    app.updated()                                  // Done
    hub.settle()
    assert !f.getDataValue("missingDrivers") && app.missingChildDrivers() == []
    ws([devicesn: "SN1", method: "report", reported: [atmon: false]])
    assert f.driver.getChildDevice("dreo-SN1-ring")
}

// ---- parity with the Homey app (2026-10-26): room pages, remembered humidifier levels, spread-out polls

check("rooms: every page of the list is read, up to a limit") {
    Hub h2 = new Hub(root)
    def a2 = h2.app()
    a2.settings.email = "me@example.com"
    a2.state.emailUsed = "me@example.com"
    a2.settings.passwordMd5 = "x"
    a2.state.token = "tok"
    a2.discoverDevices([list: [
        [deviceId: "1", sn: "SN1", model: "DR-HPF008S", deviceName: "Office Fan"],
        [deviceId: "2", sn: "SN2", model: "DR-HTF004S", deviceName: "Bedroom Fan"],
    ]], [:])
    Map rooms = a2.callsNamed("asynchttpGet").last()[3]
    assert rooms.cb == "discoverRooms" && rooms.data == [page: 1] && rooms.query.pageNo == 1
    a2.state.discovering = a2.now()
    a2.discoverRooms([totalPage: 2, familyRooms: [[rooms: [[roomName: "Office", devices: [[sn: "SN1"]]]]]]], [page: 1])
    assert a2.state.discovering                       // not done yet: page 2 is on its way
    rooms = a2.callsNamed("asynchttpGet").last()[3]
    assert rooms.cb == "discoverRooms" && rooms.data == [page: 2] && rooms.query.pageNo == 2
    a2.discoverRooms([totalPage: 2, familyRooms: [[rooms: [[roomName: "Bedroom", devices: [[sn: "SN2"]]]]]]], [page: 2])
    h2.settle()
    assert !a2.state.discovering && a2.state.discovered.SN1.room == "Office" && a2.state.discovered.SN2.room == "Bedroom"
    // A missing totalPage means one page; a failed page still ends discovery.
    int gets = a2.callsNamed("asynchttpGet").size()
    a2.state.discovering = a2.now()
    a2.discoverRooms([familyRooms: []], [page: 1])
    assert !a2.state.discovering && a2.callsNamed("asynchttpGet").size() == gets
    a2.state.discovering = a2.now()
    a2.discoverRooms(null, [page: 3])
    assert !a2.state.discovering
    // Never more than ROOM_PAGES_MAX pages, whatever the list claims.
    a2.state.discovering = a2.now()
    a2.discoverRooms([totalPage: 99, familyRooms: []], [page: 10])
    assert !a2.state.discovering && a2.callsNamed("asynchttpGet").size() == gets
}

check("humidifier: display and ambient light switch back on at the level they had") {
    Hub h2 = new Hub(root)
    def a2 = h2.app()
    a2.settings.email = "me@example.com"
    a2.state.emailUsed = "me@example.com"
    a2.settings.passwordMd5 = "x"
    a2.state.token = "tok"
    a2.discoverDevices([list: [[deviceId: "5", sn: "SN5", model: "DR-HHM001S", deviceName: "Humidifier"]]], [:])
    a2.discoverRooms(null, [:])
    a2.settings.selectedFans = ["SN5"]
    a2.updated()
    h2.settle()
    FakeDevice hm = a2.getChildDevice("dreo-SN5")
    FakeDevice c2 = a2.getChildDevice("dreo-connection-${a2.app.id}")
    c2.webSocketStatus("status: open")
    h2.settle()
    a2.fanStateDone([mixed: [poweron: [state: true], mode: [state: 1], rh: [state: 47], rhautolevel: [state: 60],
        foglevel: [state: 3], ledlevel: [state: 3], rgblevel: [state: 1], connected: [state: true]]], [sn: "SN5"])
    h2.settle()
    assert hm.driver.state.ledlevelOn == 3 && hm.driver.state.rgblevelOn == 1
    Closure lastSent = {
        new JsonSlurper().parseText(c2.driver.callsNamed("ws.send").collect { it[1] }.findAll { it != "2" }.last()).params
    }
    hm.setDisplay("off")
    h2.settle()
    assert lastSent() == [ledlevel: 0] && hm.currentValue("display") == "off"
    c2.parse(JsonOutput.toJson([devicesn: "SN5", method: "control-reply", reported: [:]]))
    h2.settle()
    assert hm.driver.state.ledlevelOn == 3                // a zero doesn't overwrite the remembered level
    hm.setDisplay("on")
    h2.settle()
    assert lastSent() == [ledlevel: 3] && hm.currentValue("display") == "on"
    c2.parse(JsonOutput.toJson([devicesn: "SN5", method: "control-reply", reported: [:]]))
    hm.setAmbientLight("off")
    h2.settle()
    c2.parse(JsonOutput.toJson([devicesn: "SN5", method: "control-reply", reported: [:]]))
    // The Dreo app changed the level meanwhile: that is what comes back.
    c2.parse(JsonOutput.toJson([devicesn: "SN5", method: "report", reported: [rgblevel: 2]]))
    h2.settle()
    c2.parse(JsonOutput.toJson([devicesn: "SN5", method: "report", reported: [rgblevel: 0]]))
    h2.settle()
    hm.setAmbientLight("on")
    h2.settle()
    assert lastSent() == [rgblevel: 2]
    // Settings any device may have.
    c2.parse(JsonOutput.toJson([devicesn: "SN5", method: "control-reply", reported: [:]]))
    c2.parse(JsonOutput.toJson([devicesn: "SN5", method: "report", reported: [timeroff: [du: 0, ts: 1], scheon: false, lightsensoron: true]]))
    h2.settle()
    assert hm.currentValue("timerOff") == 0 && hm.currentValue("schedules") == "off" && hm.currentValue("adaptiveBrightness") == "on"
    hm.setTimerOff(20)
    h2.settle()
    assert lastSent() == [timeroff: 20] && hm.currentValue("timerOff") == 20
}

check("the half-hourly poll reads devices a few seconds apart") {
    Hub h2 = new Hub(root)
    def a2 = h2.app()
    a2.settings.email = "me@example.com"
    a2.state.emailUsed = "me@example.com"
    a2.settings.passwordMd5 = "x"
    a2.state.token = "tok"
    a2.discoverDevices([list: [
        [deviceId: "1", sn: "SN1", model: "DR-HPF008S", deviceName: "A"],
        [deviceId: "2", sn: "SN2", model: "DR-HTF004S", deviceName: "B"],
        [deviceId: "3", sn: "SN3", model: "DR-HAF001S", deviceName: "C"],
    ]], [:])
    a2.discoverRooms(null, [:])
    a2.settings.selectedFans = ["SN1", "SN2", "SN3"]
    a2.updated()
    h2.settle()
    int gets = a2.callsNamed("asynchttpGet").size()
    int timers = a2.callsNamed("runIn").size()
    a2.pollAll()
    List states = a2.callsNamed("asynchttpGet").drop(gets).findAll { it[3]?.cb == "fanStateDone" }
    assert states.collect { it[3].data.sn } == ["SN1"]  // the first straight away
    List later = a2.callsNamed("runIn").drop(timers).findAll { it[2] == "pollOne" }
    assert later.collect { [it[1], it[3].data.sn] } == [[2, "SN2"], [4, "SN3"]]
    assert later.every { it[3].overwrite == false }
    a2.pollOne(later[0][3].data)
    assert a2.callsNamed("asynchttpGet").last()[3].data == [sn: "SN2"]
}

/** A fresh app with one fan (DR-HTF004S) added and its connection device, not connected. */
def appWithFan(Hub h, Map limits = [:]) {
    def a = h.app()
    a.settings.email = "me@example.com"
    a.state.emailUsed = "me@example.com"
    a.settings.passwordMd5 = "x"
    a.state.token = "tok"
    a.discoverDevices([list: [[deviceId: "1", sn: "SN1", model: "DR-HTF004S", deviceName: "A"] + limits]], [:])
    a.discoverRooms(null, [:])
    a.settings.selectedFans = ["SN1"]
    a.updated()
    h.settle()
    return a
}

check("a sign-in that fails while reconnecting tries the cloud connection again later") {
    Hub h2 = new Hub(root)
    def a2 = appWithFan(h2)
    FakeDevice c2 = a2.getChildDevice("dreo-connection-${a2.app.id}")
    c2.driver.webSocketStatus("failure: connection reset")
    h2.settle()
    // The token is gone (e.g. after three failures): reconnecting needs a sign-in first.
    a2.state.remove("token")
    h2.advance(61000)
    int timers = a2.callsNamed("runIn").size()
    a2.connectSocket()
    Map ctx = a2.callsNamed("asynchttpPost").last()[3]
    a2.loginDone(new FakeResp(status: 503), ctx)
    h2.settle()
    assert a2.callsNamed("runIn").drop(timers).find { it[2] == "connectSocket" }?.get(1) == 60
    // Rejected: tried again once the 30-minute wait is over.
    h2.advance(61000)
    timers = a2.callsNamed("runIn").size()
    a2.connectSocket()
    ctx = a2.callsNamed("asynchttpPost").last()[3]
    a2.loginDone(new FakeResp(json: [code: 10002, msg: "busy"]), ctx)
    h2.settle()
    assert a2.callsNamed("runIn").drop(timers).find { it[2] == "connectSocket" }?.get(1) == 1801
    // The half-hourly poll also asks for the connection while it is down.
    a2.state.remove("authFailedAt")
    a2.state.token = "tok"
    int connects = c2.driver.callsNamed("ws.connect").size()
    a2.pollAll()
    h2.settle()
    assert c2.driver.callsNamed("ws.connect").size() == connects + 1
    // Connected: nothing more is scheduled after a failed sign-in, and the poll leaves the socket alone.
    c2.driver.webSocketStatus("status: open")
    h2.settle()
    timers = a2.callsNamed("runIn").size()
    a2.state.remove("token")
    h2.advance(61000)
    a2.refreshFan("SN1")
    a2.loginDone(new FakeResp(status: 503), a2.callsNamed("asynchttpPost").last()[3])
    a2.state.token = "tok"
    a2.pollAll()
    h2.settle()
    assert !a2.callsNamed("runIn").drop(timers).find { it[2] == "connectSocket" }
    assert c2.driver.callsNamed("ws.connect").size() == connects + 1
}

check("Done re-reads the device list even after an earlier discovery never finished") {
    Hub h2 = new Hub(root)
    def a2 = appWithFan(h2)
    a2.state.discovering = a2.now()       // a discovery whose sign-in failed
    h2.settle()
    int gets = a2.callsNamed("asynchttpGet").size()
    a2.updated()
    h2.settle()
    assert !a2.callsNamed("asynchttpGet").drop(gets).find { it[3]?.cb == "discoverDevices" }
    h2.advance(46000)
    a2.updated()
    h2.settle()
    assert a2.callsNamed("asynchttpGet").drop(gets).find { it[3]?.cb == "discoverDevices" }
}

check("a limit the device list no longer gives is removed from the device; unreadable limits keep theirs") {
    Hub h2 = new Hub(root)
    Map conf = [controlsConf: [control: [[type: "Speed", items: (1..12).collect { [value: "${it}"] }],
        [type: "Oscillation", cmd: "shakehorizon", items: [[value: 30], [value: 60], [value: 90]]]]]]
    def a2 = appWithFan(h2, conf)
    FakeDevice f = a2.getChildDevice("dreo-SN1")
    assert f.getDataValue("speedRange") == "1-12" && f.getDataValue("oscillationAngles") == "30,60,90"
    // The device list stops giving the angles: the device drops them.
    a2.discoverDevices([list: [[deviceId: "1", sn: "SN1", model: "DR-HTF004S", deviceName: "A",
        controlsConf: [control: [conf.controlsConf.control[0]]]]]], [:])
    h2.settle()
    assert f.getDataValue("speedRange") == "1-12" && f.getDataValue("oscillationAngles") == null
    // A device missing from the list, or with no limits read, keeps its own.
    a2.applyLimits([:])
    a2.applyLimits([SN1: [name: "A", model: "DR-HTF004S"]])
    assert f.getDataValue("speedRange") == "1-12"
}

Check.finish()
