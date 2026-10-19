/**
 * Real device data through the whole app: each fixture in tests/fixtures/
 * (a device-list entry and a full state, as "Log raw Dreo data" writes them)
 * goes through discovery, device creation, a REST refresh, dreoEvents and
 * every command its driver offers, on a fresh hub each.
 *
 * Fixture files: {"source": "...", "device": {device-list entry}, "state": {flat state}}.
 * hass-dreo/ holds samples from hass-dreo's tests; digital-homesteaders/ holds the maintainers' test devices.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile

// What each model must show after its fixture state (attribute -> value), where we know it.
@Field Map EXPECT = [
    "DR-HAF001S": [maxSpeedLevel: 4, supportedModes: ["normal", "natural", "sleep", "auto"]],
    "DR-HPF004S": [maxSpeedLevel: 9, supportedModes: ["normal", "natural", "sleep", "auto", "turbo", "custom"]],
    "DR-HPF008S": [maxSpeedLevel: 9, supportedModes: ["normal", "natural", "sleep", "auto", "turbo"]],
    "DR-HPF025S": [maxSpeedLevel: 9, supportedModes: ["normal", "auto", "sleep", "natural", "turbo"]],
    "DR-HTF005S": [maxSpeedLevel: 9, supportedModes: ["normal", "natural", "sleep", "auto"]],
    "DR-HTF007S": [maxSpeedLevel: 8, supportedModes: ["normal", "natural", "sleep", "auto"]],
    "DR-HCF002S": [maxSpeedLevel: 12, supportedModes: ["normal", "natural", "sleep", "auto"]],
]

// Commands each driver offers, with the arguments to try ([] = none).
List fanCommands(FakeDevice d, Map s) {
    List c = [["on"], ["off"], ["on"], ["cycleSpeed"]]
    (1..12).each { c << ["setSpeedLevel", "${it}".toString()] }
    ["low", "medium-low", "medium", "medium-high", "high", "auto", "off", "on"].each { c << ["setSpeed", it] }
    json(d.currentValue("supportedModes") ?: "[]").each { c << ["setMode", it] }
    if (d.driverName == "Dreo Fan") {
        json(d.currentValue("supportedOscillation") ?: "[]").each { c << ["setOscillation", it] }
        json(d.currentValue("supportedDisplay") ?: "[]").each { c << ["setDisplay", it] }
        c << ["setHeadPosition", 10, 20] << ["setOscillationRange", -45, 45, 0, 60]
    }
    c << ["setPanelSound", "off"] << ["setPanelSound", "on"] << ["setChildLock", "on"] << ["setChildLock", "off"]
    return c
}

List humidifierCommands(FakeDevice d) {
    List c = [["on"], ["off"], ["on"]]
    json(d.currentValue("supportedModes") ?: "[]").each { c << ["setMode", it] }
    c << ["setTargetHumidity", 45] << ["setMistLevel", 2] << ["setDisplay", "off"] << ["setDisplay", "on"]
    c << ["setPanelSound", "off"] << ["setAmbientLight", "off"]
    return c
}

List lightCommands() {
    [["on"], ["setLevel", 40], ["setColor", [hue: 50, saturation: 100]], ["setHue", 20], ["setSaturation", 60],
     ["setEffect", 2], ["setNextEffect"], ["setPreviousEffect"], ["setColorTemperature", 4000], ["off"]]
}

def json(String s) { new JsonSlurper().parseText(s) }

/** Wrap a flat state as Dreo's REST answer does. */
Map restState(Map flat) { [mixed: flat.collectEntries { k, v -> [(k): [state: v]] }] }

new File(root, "tests/fixtures").eachFileRecurse { File f ->
    if (!f.name.endsWith(".json")) return
    Map fx = json(f.text)
    String model = fx.device.model
    String name = "${f.parentFile.name}/${f.name}"

    check("${name}: discovery, events and every command") {
        Hub hub = new Hub(root)
        def app = hub.app()
        app.settings.email = "me@example.com"
        app.state.emailUsed = "me@example.com"
        app.settings.passwordMd5 = "x"
        app.state.passwordHash = "x"
        app.state.token = "tok"

        // Discovery reads the entry as Dreo sends it (plus the personal fields the dump leaves out).
        app.discoverDevices([list: [fx.device + [deviceId: "1", sn: "FX1", deviceName: "Fixture"]]], [:])
        app.discoverRooms(null, [:])
        hub.settle()
        Map limits = app.fanLimits(fx.device.controlsConf)
        app.settings.selectedFans = ["FX1"]
        app.updated()
        hub.settle()
        FakeDevice d = app.getChildDevice("dreo-FX1")
        FakeDevice conn = app.getChildDevice("dreo-connection-${app.app.id}")
        assert d && conn
        boolean humidifier = d.driverName == "Dreo Humidifier"
        if (!humidifier) limits.each { k, v -> assert d.getDataValue(k) == v }
        conn.webSocketStatus("status: open")
        hub.settle()

        app.fanStateDone(restState(fx.state), [sn: "FX1"])
        hub.settle()
        assert d.currentValue("switch") in ["on", "off"]
        assert d.currentValue("healthStatus") == "online"
        (EXPECT[model] ?: [:]).each { attr, v ->
            def got = d.currentValue(attr)
            assert (got instanceof String && got.startsWith("[") ? json(got) : got) == v, "${attr}"
        }
        if (!humidifier) assert d.currentValue("speed") && d.currentValue("supportedFanSpeeds")

        // Every command: no exception, and only keys this fan itself reports are sent.
        Set known = fx.state.keySet()
        List modeValues = d.currentValue("supportedModes") ? (app.fanLimits(fx.device.controlsConf).modes ?: "")
            .split(",").findAll().collect { it.split("=")[1] as int } : []
        List cmds = humidifier ? humidifierCommands(d) : fanCommands(d, fx.state)
        Closure run = { FakeDevice target, List c ->
            int from = conn.driver.callsNamed("ws.send").size()
            target."${c[0]}"(*c.drop(1))
            hub.settle()
            List msgs = conn.driver.callsNamed("ws.send").drop(from).collect { it[1] }.findAll { it != "2" }
                .collect { json(it) }.findAll { it.method == "control" }
            msgs.each { m ->
                assert known.containsAll(m.params.keySet()), "${c} sent ${m.params} (fan reports ${known.sort()})"
                if (m.params.containsKey("windlevel") && !humidifier) {
                    assert m.params.windlevel >= 1 && m.params.windlevel <= (d.currentValue("maxSpeedLevel") as int)
                }
                ["mode", "windtype"].each { k ->
                    if (!humidifier && modeValues && m.params.containsKey(k)) assert m.params[k] in modeValues, "${c} sent ${k} ${m.params[k]}"
                }
            }
            conn.parse(JsonOutput.toJson([devicesn: "FX1", method: "control-reply", reported: [:]]))
            hub.settle()
        }
        cmds.each { run(d, it) }
        d.driver.getChildDevices().each { FakeDevice light ->
            lightCommands().each { c ->
                if (light.driver.metaClass.respondsTo(light.driver, c[0])) run(light, c)
            }
        }
        // The fan's state still holds only what it reports (commands fold in their params).
        assert known.containsAll(d.driver.state.dreo.keySet())
    }
}

Check.finish()
