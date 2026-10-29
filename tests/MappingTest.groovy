/**
 * Unit tests for the Dreo mapping functions in apps/dreo-connect.groovy:
 * speed names and steps, command params, the ceiling-fan power gate,
 * temperature, colour conversion, and the events for sample fan states.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check
import static Check.throwsIAE

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Hub hub = new Hub(root)
@Field def app = hub.app()
@Field List FIVE = ["low", "medium-low", "medium", "medium-high", "high"]

Map prof(String model, Map s = [:], Map opts = [:]) { app.profile(model, s, opts) }
Map cmd(String model, Map s, String name, List args = [], Map opts = [:]) {
    app.buildCommand(prof(model, s, opts), s, name, args, opts)
}

// A device as the fan driver passes it to the app.
FakeDevice fan(String model) {
    new FakeDevice(id: 1, deviceNetworkId: "dreo-SN1", name: model, data: [model: model, sn: "SN1"])
}

/** Events as attr -> value (child events as "child.attr"). */
Map events(String model, Map s, Map opts = [:]) {
    List ev = app.dreoEvents(fan(model), s, opts)
    Map out = [:]
    ev.each { e ->
        String k = e.child ? "${e.child}.${e.name}" : e.name
        assert !out.containsKey(k), "duplicate event ${k}"
        out[k] = e.value
    }
    return out
}

def json(String s) { new JsonSlurper().parseText(s) }

// ---------------------------------------------------------------- speeds

check("speedNames by step count") {
    assert app.speedNames(1) == ["high"]
    assert app.speedNames(2) == ["low", "high"]
    assert app.speedNames(3) == ["low", "medium", "high"]
    assert app.speedNames(4) == ["low", "medium-low", "medium-high", "high"]
    [5, 8, 9, 10, 12].each { assert app.speedNames(it) == FIVE }
}

// Named speed -> step, for each step count (min step 1).
Map LEVELS = [
    4: [1, 2, 3, 4],
    8: [2, 3, 5, 6, 8],
    9: [2, 4, 5, 7, 9],
    10: [2, 4, 6, 8, 10],
    12: [2, 5, 7, 10, 12],
]

LEVELS.each { int steps, List levels ->
    check("levelForSpeed, ${steps} steps") {
        assert (0..<levels.size()).collect { app.levelForSpeed(it, 1, steps) } == levels
    }
    check("speedNameFor covers every step and round-trips, ${steps} steps") {
        Map p = [min: 1, max: steps, steps: steps]
        List names = app.speedNames(steps)
        // Each step maps to the first name whose step is at or above it.
        (1..steps).each { int lvl ->
            int i = levels.findIndexOf { it >= lvl }
            assert app.speedNameFor(p, lvl) == names[i]
        }
        names.eachWithIndex { String n, int i -> assert app.speedNameFor(p, levels[i]) == n }
    }
}

check("levelForSpeed honours a min step above 1") {
    assert app.levelForSpeed(0, 2, 4) == 2
    assert app.levelForSpeed(3, 2, 4) == 5
}

check("profile: model table, family default, override, reported step") {
    assert prof("DR-HAF001S").subMap(["kind", "min", "max", "steps"]) == [kind: "circulator", min: 1, max: 4, steps: 4]
    assert prof("DR-HTF004S").max == 9  // not in the table: family default
    assert prof("DR-HCF001S").kind == "ceiling"
    assert prof("DR-HCF001S").max == 12
    assert prof("DR-HTF007S").max == 8
    assert prof("DR-HPF005S").max == 10
    assert prof("DR-HTF007S", [:], [speedSteps: 5]).max == 5
    assert prof("DR-HTF007S", [windlevel: 11]).max == 11
    assert prof("DR-HPF008S").modes*.get(0) == ["normal", "natural", "sleep", "auto", "turbo"]
    assert prof("DR-HPF017S").modes.find { it[0] == "auto" }[1] == 2
    assert prof("DR-HCF001S").modes*.get(0) == ["normal", "natural", "sleep", "reverse"]
    assert prof("DR-HPF008S").ring == [1, 3]
    assert prof("DR-HTF011S").ring == [1, 5]
}

check("profile: power and mode keys") {
    assert prof("DR-HTF011S", [poweron: true]).powerKey == "poweron"
    assert prof("DR-HTF011S", [fanon: true]).powerKey == "fanon"
    assert prof("DR-HTF011S", [:]).powerKey == "poweron"
    assert prof("DR-HCF001S", [poweron: true, fanon: true]).powerKey == "fanon"
    assert prof("DR-HTF011S", [windtype: 1]).modeKey == "windtype"
    assert prof("DR-HTF011S", [mode: 1]).modeKey == "mode"
}

// ---------------------------------------------------------------- buildCommand: speed

Map ON9 = [poweron: true, windlevel: 3, mode: 1]       // DR-HTF011S, 9 steps
Map OFF9 = [poweron: false, windlevel: 3, mode: 1]
Map AUTO9 = [poweron: true, windlevel: 3, mode: 4]

check("on/off") {
    assert cmd("DR-HTF011S", ON9, "on") == [poweron: true]
    assert cmd("DR-HTF011S", ON9, "off") == [poweron: false]
    assert cmd("DR-HCF001S", [poweron: true, fanon: false], "on") == [fanon: true]
}

check("setSpeed: named speeds") {
    assert cmd("DR-HTF011S", ON9, "setSpeed", ["low"]) == [windlevel: 2]
    assert cmd("DR-HTF011S", ON9, "setSpeed", ["medium"]) == [windlevel: 5]
    assert cmd("DR-HTF011S", ON9, "setSpeed", ["high"]) == [windlevel: 9]
    assert cmd("DR-HAF001S", [poweron: true, mode: 1], "setSpeed", ["medium-high"]) == [windlevel: 3]
    assert cmd("DR-HCF001S", [poweron: true, fanon: true], "setSpeed", ["medium"]) == [windlevel: 7]
}

check("setSpeed: standard names a fan doesn't list go to the nearest step") {
    // Hubitat's own setSpeed list (and dashboards) always offer all five names.
    Map on4 = [poweron: true, mode: 1]
    assert ["low", "medium-low", "medium", "medium-high", "high"].collect {
        cmd("DR-HAF001S", on4, "setSpeed", [it]).windlevel
    } == [1, 2, 2, 3, 4]
    Map p3 = [kind: "tower", min: 1, max: 3, steps: 3, modes: null, powerKey: "poweron", modeKey: "mode"]
    assert ["medium-low", "medium-high"].collect { app.buildCommand(p3, on4, "setSpeed", [it], [:]).windlevel } == [1, 2]
    Map p1 = p3 + [max: 1, steps: 1]
    assert ["low", "medium", "high"].collect { app.buildCommand(p1, on4, "setSpeed", [it], [:]).windlevel } == [1, 1, 1]
    assert cmd("DR-HAF001S", [poweron: false, mode: 4], "setSpeed", ["medium"]) == [poweron: true, windlevel: 2, mode: 1]
}

check("setSpeed: on/off and waking a fan that is off") {
    assert cmd("DR-HTF011S", ON9, "setSpeed", ["off"]) == [poweron: false]
    assert cmd("DR-HTF011S", OFF9, "setSpeed", ["on"]) == [poweron: true]
    assert cmd("DR-HTF011S", OFF9, "setSpeed", ["high"]) == [poweron: true, windlevel: 9]
}

check("setSpeed: auto selects auto mode") {
    assert cmd("DR-HTF011S", ON9, "setSpeed", ["auto"]) == [mode: 4]
    assert cmd("DR-HTF011S", OFF9, "setSpeed", ["auto"]) == [poweron: true, mode: 4]
    assert cmd("DR-HTF011S", [poweron: true, windtype: 1], "setSpeed", ["auto"]) == [windtype: 4]
    assert cmd("DR-HPF017S", [poweron: true, mode: 1], "setSpeed", ["auto"]) == [mode: 2]
    throwsIAE { cmd("DR-HCF001S", [poweron: true, fanon: true, mode: 1], "setSpeed", ["auto"]) }
}

check("setSpeed / setSpeedLevel leave auto mode") {
    assert cmd("DR-HTF011S", AUTO9, "setSpeed", ["medium"]) == [windlevel: 5, mode: 1]
    assert cmd("DR-HTF011S", AUTO9, "setSpeedLevel", [6]) == [windlevel: 6, mode: 1]
    assert cmd("DR-HTF011S", [poweron: true, windtype: 4], "setSpeedLevel", [6]) == [windlevel: 6, windtype: 1]
    // "auto-regular" style variants count as auto.
    assert cmd("DR-HTF011S", [poweron: true, mode: "auto-regular"], "setSpeedLevel", [6]) == [windlevel: 6, mode: 1]
}

check("setSpeed: unsupported names") {
    throwsIAE { cmd("DR-HTF011S", ON9, "setSpeed", ["warp"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setSpeed", [null]) }
}

check("setSpeedLevel bounds") {
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [0]) == [poweron: false]
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [-1]) == [poweron: false]
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [1]) == [windlevel: 1]
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [9]) == [windlevel: 9]
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [4.0G]) == [windlevel: 4]
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", ["4"]) == [windlevel: 4]
    assert cmd("DR-HTF011S", OFF9, "setSpeedLevel", [4]) == [poweron: true, windlevel: 4]
    // The dropdown offers 1-12 for every fan: above the top speed means the top speed.
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [10]) == [windlevel: 9]
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", ["12"]) == [windlevel: 9]
    assert cmd("DR-HAF001S", [poweron: true], "setSpeedLevel", [5]) == [windlevel: 4]
    assert cmd("DR-HCF001S", [poweron: true, fanon: true], "setSpeedLevel", ["12"]) == [windlevel: 12]
    throwsIAE { cmd("DR-HTF011S", ON9, "setSpeedLevel", ["fast"]) }
}

check("setSpeedLevel with a speed-step override") {
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [5], [speedSteps: 4]) == [windlevel: 4]
    assert cmd("DR-HTF011S", ON9, "setSpeedLevel", [4], [speedSteps: 4]) == [windlevel: 4]
}

check("no percent level on fans: speed is a number") {
    Map e = events("DR-HTF011S", [poweron: true, windlevel: 5, mode: 1])
    assert !e.containsKey("level") && e.speedLevel == 5
    throwsIAE { cmd("DR-HTF011S", ON9, "setLevel", [50]) }
}

check("cycleSpeed") {
    // 9 steps: names at 2, 4, 5, 7, 9.
    assert cmd("DR-HTF011S", [poweron: true, windlevel: 5, mode: 1], "cycleSpeed") == [windlevel: 7]
    assert cmd("DR-HTF011S", [poweron: true, windlevel: 6, mode: 1], "cycleSpeed") == [windlevel: 9]
    assert cmd("DR-HTF011S", [poweron: true, windlevel: 9, mode: 1], "cycleSpeed") == [windlevel: 2]
    assert cmd("DR-HTF011S", [poweron: true, windlevel: 1, mode: 1], "cycleSpeed") == [windlevel: 4]
    assert cmd("DR-HTF011S", OFF9, "cycleSpeed") == [poweron: true, windlevel: 2]
    assert cmd("DR-HTF011S", [poweron: true, mode: 1], "cycleSpeed") == [windlevel: 2]
    assert cmd("DR-HAF001S", [poweron: true, windlevel: 4, mode: 1], "cycleSpeed") == [windlevel: 1]
    assert cmd("DR-HTF011S", [poweron: true, windlevel: 5, mode: 4], "cycleSpeed") == [windlevel: 7, mode: 1]
}

// ---------------------------------------------------------------- buildCommand: modes and features

check("setMode") {
    assert cmd("DR-HTF011S", ON9, "setMode", ["sleep"]) == [mode: 3]
    assert cmd("DR-HTF011S", OFF9, "setMode", ["natural"]) == [poweron: true, mode: 2]
    assert cmd("DR-HPF008S", [poweron: true, mode: 1], "setMode", ["turbo"]) == [mode: 5]
    assert cmd("DR-HPF004S", [poweron: true, mode: 1], "setMode", ["custom"]) == [mode: 6]
    assert cmd("DR-HPF025S", [poweron: true, mode: 1], "setMode", ["natural"]) == [mode: 4]
    assert cmd("DR-HCF001S", [poweron: true, fanon: true, mode: 1], "setMode", ["reverse"]) == [mode: 4]
    throwsIAE { cmd("DR-HTF011S", ON9, "setMode", ["turbo"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setMode", [null]) }
}

check("setOscillation, single axis") {
    assert cmd("DR-HTF011S", ON9 + [shakehorizon: false], "setOscillation", ["on"]) == [shakehorizon: true]
    assert cmd("DR-HTF011S", ON9 + [shakehorizon: true], "setOscillation", ["off"]) == [shakehorizon: false]
    assert cmd("DR-HTF011S", ON9 + [oscon: false], "setOscillation", ["horizontal"]) == [oscon: true]
    assert cmd("DR-HTF011S", ON9 + [hoscon: false], "setOscillation", ["on"]) == [hoscon: true]
    throwsIAE { cmd("DR-HTF011S", ON9 + [oscon: false], "setOscillation", ["vertical"]) }
    throwsIAE { cmd("DR-HTF011S", ON9 + [oscon: false], "setOscillation", ["both"]) }
    throwsIAE { cmd("DR-HTF011S", ON9 + [oscon: true], "setOscillation", ["sideways"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setOscillation", ["on"]) }
}

check("setOscillation, oscmode (two axes)") {
    Map s = [poweron: true, oscmode: 2]  // vertical only
    assert cmd("DR-HPF008S", s, "setOscillation", ["on"]) == [oscmode: 3]
    assert cmd("DR-HPF008S", [poweron: true, oscmode: 0], "setOscillation", ["on"]) == [oscmode: 1]
    assert cmd("DR-HPF008S", s, "setOscillation", ["off"]) == [oscmode: 0]
    assert cmd("DR-HPF008S", s, "setOscillation", ["horizontal"]) == [oscmode: 1]
    assert cmd("DR-HPF008S", s, "setOscillation", ["vertical"]) == [oscmode: 2]
    assert cmd("DR-HPF008S", s, "setOscillation", ["both"]) == [oscmode: 3]
    throwsIAE { cmd("DR-HPF008S", s, "setOscillation", ["sideways"]) }
}

check("setOscillation, hoscon + voscon (two axes)") {
    Map s = [poweron: true, hoscon: false, voscon: true]
    assert cmd("DR-HPF004S", s, "setOscillation", ["on"]) == [hoscon: true, voscon: true]
    assert cmd("DR-HPF004S", s, "setOscillation", ["off"]) == [hoscon: false, voscon: false]
    assert cmd("DR-HPF004S", s, "setOscillation", ["horizontal"]) == [hoscon: true, voscon: false]
    assert cmd("DR-HPF004S", s, "setOscillation", ["both"]) == [hoscon: true, voscon: true]
}

check("setDisplay") {
    assert cmd("DR-HTF011S", ON9 + [ledalwayson: true], "setDisplay", ["off"]) == [ledalwayson: false]
    assert cmd("DR-HTF011S", ON9 + [ledkepton: false], "setDisplay", ["on"]) == [ledkepton: true]
    assert cmd("DR-HAF001S", [poweron: true, lighton: true], "setDisplay", ["off"]) == [lighton: false]
    assert cmd("DR-HPF008S", [poweron: true, lightmode: 1], "setDisplay", ["auto"]) == [lightmode: 2]
    assert cmd("DR-HPF008S", [poweron: true, lightmode: 2], "setDisplay", ["off"]) == [lightmode: 0]
    throwsIAE { cmd("DR-HPF008S", [poweron: true, lightmode: 1], "setDisplay", ["dim"]) }
    throwsIAE { cmd("DR-HTF011S", ON9 + [ledalwayson: true], "setDisplay", ["dim"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setDisplay", ["on"]) }
    // A ceiling fan's lighton is its light, not a display.
    throwsIAE { cmd("DR-HCF001S", [poweron: true, fanon: true, lighton: true], "setDisplay", ["off"]) }
}

check("setPanelSound and setChildLock") {
    assert cmd("DR-HTF011S", ON9 + [voiceon: false], "setPanelSound", ["on"]) == [voiceon: true]
    assert cmd("DR-HTF011S", ON9 + [voiceon: true], "setPanelSound", ["off"]) == [voiceon: false]
    assert cmd("DR-HTF011S", ON9 + [muteon: true], "setPanelSound", ["on"]) == [muteon: false]
    assert cmd("DR-HTF011S", ON9 + [muteon: false], "setPanelSound", ["off"]) == [muteon: true]
    throwsIAE { cmd("DR-HTF011S", ON9, "setPanelSound", ["on"]) }
    throwsIAE { cmd("DR-HTF011S", ON9 + [voiceon: true], "setPanelSound", ["loud"]) }
    assert cmd("DR-HTF011S", ON9 + [childlockon: false], "setChildLock", ["on"]) == [childlockon: true]
    throwsIAE { cmd("DR-HTF011S", ON9, "setChildLock", ["on"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "dance") }
}

// ---------------------------------------------------------------- lights

Map CEIL = [poweron: true, fanon: true, windlevel: 6, mode: 1, lighton: true, brightness: 50, colortemp: 50,
            atmon: false, atmbri: 50, atmcolor: 0xFF0000, atmmode: 1]

check("ceiling light commands") {
    assert cmd("DR-HCF007S", CEIL, "light.on") == [lighton: true]
    assert cmd("DR-HCF007S", CEIL, "light.off") == [lighton: false]
    assert cmd("DR-HCF007S", CEIL, "light.setLevel", [30]) == [brightness: 30]
    assert cmd("DR-HCF007S", CEIL, "light.setLevel", [0]) == [lighton: false]
    assert cmd("DR-HCF007S", CEIL, "light.setLevel", [150]) == [brightness: 100]
    assert cmd("DR-HCF007S", CEIL + [lighton: false], "light.setLevel", [30]) == [brightness: 30, lighton: true]
    // Gate closed: the light is dark even though lighton is true.
    assert cmd("DR-HCF007S", CEIL + [poweron: false], "light.setLevel", [30]) == [brightness: 30, lighton: true]
    assert cmd("DR-HCF007S", CEIL, "light.setColorTemperature", [2700]) == [colortemp: 0]
    assert cmd("DR-HCF007S", CEIL, "light.setColorTemperature", [6500, 40]) == [colortemp: 100, brightness: 40]
    assert cmd("DR-HCF007S", CEIL, "light.setColorTemperature", [4600, null]) == [colortemp: 50]
    assert cmd("DR-HCF007S", CEIL, "light.setColorTemperature", [9000]) == [colortemp: 100]
    throwsIAE { cmd("DR-HCF007S", CEIL, "light.blink") }
}

Map RING = [poweron: true, windlevel: 3, mode: 1, atmon: true, atmbri: 2, atmcolor: 0x00FF00, atmmode: 1]

check("light ring commands") {
    assert cmd("DR-HPF008S", RING, "ring.on") == [atmon: true]
    assert cmd("DR-HPF008S", RING, "ring.off") == [atmon: false]
    assert cmd("DR-HPF008S", RING, "ring.setLevel", [100]) == [atmbri: 3]
    assert cmd("DR-HPF008S", RING, "ring.setLevel", [50]) == [atmbri: 2]
    assert cmd("DR-HPF008S", RING, "ring.setLevel", [1]) == [atmbri: 1]
    assert cmd("DR-HPF008S", RING, "ring.setLevel", [0]) == [atmon: false]
    assert cmd("DR-HPF008S", RING + [atmon: false], "ring.setLevel", [100]) == [atmbri: 3, atmon: true]
    assert cmd("DR-HCF007S", CEIL + [atmon: true], "ring.setLevel", [40]) == [atmbri: 40]
    assert cmd("DR-HPF008S", RING, "ring.setColor", [[hue: 0, saturation: 100]]) == [atmcolor: 0xFF0000]
    assert cmd("DR-HPF008S", RING, "ring.setColor", [[hue: 66.6667, saturation: 100, level: 100]]) == [atmcolor: 0x0000FF, atmbri: 3]
    assert cmd("DR-HPF008S", RING, "ring.setHue", [50], [ringSaturation: 100]) == [atmcolor: 0x00FFFF]
    assert cmd("DR-HPF008S", RING, "ring.setSaturation", [0], [ringHue: 10]) == [atmcolor: 0xFFFFFF]
    assert cmd("DR-HPF008S", RING, "ring.setEffect", [3]) == [atmmode: 3]
    assert cmd("DR-HPF008S", RING, "ring.setEffect", ["2"]) == [atmmode: 2]
    throwsIAE { cmd("DR-HPF008S", RING, "ring.setEffect", [4]) }
    assert cmd("DR-HPF008S", RING + [atmmode: 3], "ring.setNextEffect") == [atmmode: 1]
    assert cmd("DR-HPF008S", RING + [atmmode: 1], "ring.setPreviousEffect") == [atmmode: 3]
    assert cmd("DR-HPF008S", RING + [atmmode: 1], "ring.setNextEffect") == [atmmode: 2]
}

// ---------------------------------------------------------------- ceiling power gate (hass-dreo PyDreoCeilingFan)

check("gate: waking a load while the gate is closed opens it and switches the other loads off") {
    Map s = [poweron: false, fanon: true, lighton: true, atmon: true]
    assert app.gateParams(s, [fanon: true]) == [fanon: true, poweron: true, lighton: false, atmon: false]
    assert app.gateParams(s, [lighton: true, brightness: 30]) == [lighton: true, brightness: 30, poweron: true, fanon: false, atmon: false]
    // Only loads the fan reports are switched off.
    assert app.gateParams([poweron: false, fanon: true, lighton: true], [fanon: true, windlevel: 3]) ==
        [fanon: true, windlevel: 3, poweron: true, lighton: false]
}

check("gate: switching off the last live load closes the gate") {
    assert app.gateParams([poweron: true, fanon: true, lighton: false, atmon: false], [fanon: false]) == [fanon: false, poweron: false]
    assert app.gateParams([poweron: true, fanon: false, lighton: true], [lighton: false]) == [lighton: false, poweron: false]
}

check("gate: unchanged cases") {
    // Another load is still on.
    assert app.gateParams([poweron: true, fanon: true, lighton: true], [fanon: false]) == [fanon: false]
    // Gate open, waking a load.
    assert app.gateParams([poweron: true, fanon: false, lighton: true], [fanon: true]) == [fanon: true]
    // Gate closed, switching a load off.
    assert app.gateParams([poweron: false, fanon: true, lighton: true], [fanon: false]) == [fanon: false]
    // Caller already set poweron.
    assert app.gateParams([poweron: false, fanon: true], [poweron: true, fanon: true]) == [poweron: true, fanon: true]
    // Fan without a gate.
    assert app.gateParams([fanon: true, lighton: false], [fanon: false]) == [fanon: false]
    // No load keys.
    assert app.gateParams([poweron: false, fanon: true], [windlevel: 3]) == [windlevel: 3]
    assert app.gateParams([poweron: true, fanon: true], [mode: 4]) == [mode: 4]
}

check("gate applied by dreoCommand for ceiling fans only") {
    def sent = []
    def conn = new FakeDevice(deviceNetworkId: "dreo-connection-${app.app.id}", name: "conn")
    conn.metaClass.sendCommand = { String sn, Map p -> sent << [sn, p] }
    app.children = [conn]
    FakeDevice cd = fan("DR-HCF007S")
    Map s = [poweron: false, fanon: false, lighton: true, atmon: false, windlevel: 3]
    assert app.dreoCommand(cd, s, "setSpeed", ["high"]) == [fanon: true, windlevel: 12, poweron: true, lighton: false, atmon: false]
    assert app.dreoCommand(cd, s, "light.on", []) == [lighton: true, poweron: true, fanon: false, atmon: false]
    assert sent.size() == 2 && sent[0][0] == "SN1"
    // Not a ceiling fan: no gate keys.
    assert app.dreoCommand(fan("DR-HTF011S"), [poweron: false, fanon: true], "on", []) == [poweron: true]
    // Bad input: nothing sent, a warning logged.
    assert app.dreoCommand(cd, s, "setMode", ["turbo"]) == null
    assert sent.size() == 3
    assert app.log.at("warn").any { it.contains("unsupported mode") }
    app.children = []
    assert app.dreoCommand(cd, s, "on", []) == null  // no connection device
}

// ---------------------------------------------------------------- temperature

check("temperature: tempunit 1 = °F, 0 = °C") {
    assert app.temperatureOf([temperature: 75, tempunit: 1], "F") == 75.0G
    assert app.temperatureOf([temperature: 75, tempunit: 1], "C") == 23.9G
    assert app.temperatureOf([temperature: 24, tempunit: 0], "C") == 24.0G
    assert app.temperatureOf([temperature: 24, tempunit: 0], "F") == 75.2G
    // tempunit wins over the >50 rule.
    assert app.temperatureOf([temperature: 55, tempunit: 0], "C") == 55.0G
    assert app.temperatureOf([temperature: 40, tempunit: 1], "C") == 4.4G
}

check("temperature: no tempunit means °F above 50") {
    assert app.temperatureOf([temperature: 72], "F") == 72.0G
    assert app.temperatureOf([temperature: 72], "C") == 22.2G
    assert app.temperatureOf([temperature: 22], "C") == 22.0G
    assert app.temperatureOf([temperature: 22], "F") == 71.6G
    assert app.temperatureOf([temperature: 50], "C") == 50.0G
}

check("temperature: offset is added in the device's unit") {
    assert app.temperatureOf([temperature: 74, tempoffset: -2, tempunit: 1], "F") == 72.0G
    assert app.temperatureOf([temperature: 74, tempoffset: -2, tempunit: 1], "C") == 22.2G
    assert app.temperatureOf([temperature: 21, tempoffset: 1, tempunit: 0], "C") == 22.0G
    assert app.temperatureOf([temperature: 21.5, tempoffset: 0.5, tempunit: 0], "F") == 71.6G
}

check("temperature: missing or not a number") {
    assert app.temperatureOf([:], "F") == null
    assert app.temperatureOf([temperature: null], "F") == null
    assert app.temperatureOf([temperature: "hot"], "F") == null
}

// ---------------------------------------------------------------- colour

check("hsToRgbInt known colours") {
    assert app.hsToRgbInt(0G, 100G) == 0xFF0000
    assert app.hsToRgbInt(100G, 100G) == 0xFF0000
    assert app.hsToRgbInt(16.6667G, 100G) == 0xFFFF00
    assert app.hsToRgbInt(33.3333G, 100G) == 0x00FF00
    assert app.hsToRgbInt(50G, 100G) == 0x00FFFF
    assert app.hsToRgbInt(66.6667G, 100G) == 0x0000FF
    assert app.hsToRgbInt(83.3333G, 100G) == 0xFF00FF
    assert app.hsToRgbInt(42G, 0G) == 0xFFFFFF
    assert app.hsToRgbInt(0G, 50G) == 0xFF8080
}

check("rgbIntToHs known colours") {
    assert app.rgbIntToHs(0xFF0000) == [hue: 0, saturation: 100]
    assert app.rgbIntToHs(0x00FF00) == [hue: 33, saturation: 100]
    assert app.rgbIntToHs(0x0000FF) == [hue: 67, saturation: 100]
    assert app.rgbIntToHs(0xFF00FF) == [hue: 83, saturation: 100]
    assert app.rgbIntToHs(0xFFFFFF) == [hue: 0, saturation: 0]
    assert app.rgbIntToHs(0x000000) == [hue: 0, saturation: 0]
    assert app.rgbIntToHs(0xFF8080) == [hue: 0, saturation: 50]
}

check("hs -> rgb -> hs round-trips within 1") {
    List bad = []
    for (int h = 0; h < 100; h += 3) {
        for (int sat = 10; sat <= 100; sat += 10) {
            Map back = app.rgbIntToHs(app.hsToRgbInt(h as BigDecimal, sat as BigDecimal))
            int dh = Math.abs(back.hue - h)
            dh = Math.min(dh, 100 - dh)  // hue wraps
            if (dh > 1 || Math.abs(back.saturation - sat) > 1) bad << [h, sat, back]
        }
    }
    assert !bad
}

check("rgb -> hs -> rgb round-trips for saturated colours") {
    [0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0xFFFFFF].each { int rgb ->
        Map hs = app.rgbIntToHs(rgb)
        int back = app.hsToRgbInt(hs.hue as BigDecimal, hs.saturation as BigDecimal)
        // Hue is whole percent, so allow a few counts of drift per channel.
        [16, 8, 0].each { int sh -> assert Math.abs(((back >> sh) & 0xFF) - ((rgb >> sh) & 0xFF)) <= 8 }
    }
}

// ---------------------------------------------------------------- dreoEvents

check("dreoEvents: DR-HAF001S (4 steps, single-axis oscillation)") {
    hub.temperatureScale = "F"
    Map s = [poweron: true, windlevel: 3, mode: 1, shakehorizon: true, ledalwayson: false,
             voiceon: false, childlockon: true, temperature: 72, tempunit: 1]
    Map e = events("DR-HAF001S", s)
    assert e.remove("supportedFanSpeeds").with { json(it) } == ["low", "medium-low", "medium-high", "high", "auto", "on", "off"]
    assert e.remove("supportedModes").with { json(it) } == ["normal", "natural", "sleep", "auto"]
    assert json(e.remove("supportedOscillation")) == ["off", "on"]
    assert json(e.remove("supportedDisplay")) == ["off", "on"]
    assert e == [switch: "on", maxSpeedLevel: 4, speedLevel: 3, speed: "medium-high", mode: "normal",
                 oscillation: "on", display: "off", panelSound: "off", childLock: "on", temperature: 72.0G]
}

check("dreoEvents: off fan, auto mode, °C hub") {
    hub.temperatureScale = "C"
    Map e = events("DR-HAF001S", [poweron: false, windlevel: 2, mode: 4, temperature: 72, tempunit: 1])
    assert e.switch == "off" && e.speed == "off" && e.speedLevel == 2 && e.mode == "auto"
    assert e.temperature == 22.2G
    List ev = app.dreoEvents(fan("DR-HAF001S"), [temperature: 22, tempunit: 0])
    assert ev.find { it.name == "temperature" }.unit == "°C"
    hub.temperatureScale = "F"
    assert events("DR-HAF001S", [poweron: true, windlevel: 2, mode: 4]).speed == "auto"
    // Off without a windlevel still reports speed off.
    assert events("DR-HAF001S", [poweron: false]).speed == "off"
    // Nothing known: no switch or speed events.
    Map none = events("DR-HAF001S", [:])
    assert !none.containsKey("switch") && !none.containsKey("speed") && !none.containsKey("temperature")
}

check("dreoEvents: DR-HPF008S with light ring (atm* keys)") {
    hub.temperatureScale = "F"
    Map s = [poweron: true, windlevel: 9, mode: 5, oscmode: 3, lightmode: 2, muteon: true, childlockon: false,
             temperature: 22, tempunit: 0, atmon: true, atmbri: 2, atmcolor: 0x00FF00, atmmode: 2]
    Map e = events("DR-HPF008S", s)
    assert json(e.remove("supportedFanSpeeds")) == FIVE + ["auto", "on", "off"]
    assert json(e.remove("supportedModes")) == ["normal", "natural", "sleep", "auto", "turbo"]
    assert json(e.remove("ring.lightEffects")) == ["1": "constant", "2": "circle", "3": "breath"]
    assert json(e.remove("supportedOscillation")) == ["off", "horizontal", "vertical", "both"]
    assert json(e.remove("supportedDisplay")) == ["off", "on", "auto"]
    assert e == [switch: "on", maxSpeedLevel: 9, speedLevel: 9, speed: "high", mode: "turbo",
                 oscillation: "both", display: "auto", panelSound: "off", childLock: "off", temperature: 71.6G,
                 "ring.switch": "on", "ring.level": 67, "ring.RGB": "#00FF00", "ring.color": "#00FF00",
                 "ring.hue": 33, "ring.saturation": 100, "ring.colorMode": "RGB", "ring.effectName": "circle"]
}

check("dreoEvents: ring hue/saturation left alone when they already give the colour") {
    Map s = [poweron: true, atmon: false, atmbri: 3, atmcolor: 0x00FF00, atmmode: 9]
    Map e = events("DR-HPF008S", s, [ringHue: 33.3333G, ringSaturation: 100])
    assert !e.containsKey("ring.hue") && !e.containsKey("ring.saturation")
    assert e["ring.switch"] == "off" && e["ring.level"] == 100
    assert !e.containsKey("ring.effectName")  // unknown effect 9
    // A stale hue produces new hue/saturation events.
    Map e2 = events("DR-HPF008S", s, [ringHue: 10, ringSaturation: 100])
    assert e2["ring.hue"] == 33 && e2["ring.saturation"] == 100
}

check("dreoEvents: two-axis hoscon/voscon and windtype modes") {
    Map e = events("DR-HPF004S", [poweron: true, windlevel: 1, windtype: 6, hoscon: false, voscon: true])
    assert e.oscillation == "vertical" && e.mode == "custom" && e.speed == "low"
    assert json(e.supportedOscillation) == ["off", "horizontal", "vertical", "both"]
    // No display key reported: no display choices either.
    assert !e.containsKey("supportedDisplay")
}

check("dreoEvents: DR-HCF ceiling fan, gate open") {
    Map s = [poweron: true, fanon: true, windlevel: 6, mode: 4, lighton: true, brightness: 80, colortemp: 50,
             atmon: true, atmbri: 50, atmcolor: 0xFF0000, atmmode: 1, voiceon: true, childlockon: false]
    Map e = events("DR-HCF007S", s)
    assert json(e.remove("supportedFanSpeeds")) == FIVE + ["on", "off"]  // no auto mode
    assert json(e.remove("supportedModes")) == ["normal", "natural", "sleep", "reverse"]
    e.remove("ring.lightEffects")
    assert e == [switch: "on", maxSpeedLevel: 12, speedLevel: 6, speed: "medium", mode: "reverse",
                 panelSound: "on", childLock: "off",
                 "light.switch": "on", "light.level": 80, "light.colorTemperature": 4600, "light.colorName": "Neutral White",
                 "ring.switch": "on", "ring.level": 50, "ring.RGB": "#FF0000", "ring.color": "#FF0000",
                 "ring.hue": 0, "ring.saturation": 100, "ring.colorMode": "RGB", "ring.effectName": "constant"]
}

check("dreoEvents: DR-HCF ceiling fan, gate closed") {
    Map s = [poweron: false, fanon: true, windlevel: 6, mode: 1, lighton: true, brightness: 80, colortemp: 0, atmon: true]
    Map e = events("DR-HCF001S", s)
    assert e.switch == "off" && e.speed == "off"
    assert e["light.switch"] == "off" && e["ring.switch"] == "off"
    assert e["light.colorTemperature"] == 2700 && e["light.colorName"] == "Warm White"
    assert !e.containsKey("display")
    assert events("DR-HCF001S", [poweron: true, fanon: false, lighton: true, colortemp: 100])
        .subMap(["switch", "light.switch", "light.colorTemperature", "light.colorName"]) ==
        [switch: "off", "light.switch": "on", "light.colorTemperature": 6500, "light.colorName": "Daylight"]
}

check("dreoEvents: speed-step override and reported step above the table") {
    Map e = events("DR-HTF011S", [poweron: true, windlevel: 3, mode: 1], [speedSteps: 4])
    assert e.maxSpeedLevel == 4 && e.speed == "medium-high"
    Map e2 = events("DR-HAF001S", [poweron: true, windlevel: 6, mode: 1])
    assert e2.maxSpeedLevel == 6 && json(e2.supportedFanSpeeds).size() == 5 + 3
}

check("dreoState drops private, hardware and noisy network keys") {
    Map s = [poweron: true, windlevel: 3, wifi_ssid: "home", module_hardware_mac: "aa:bb", wifi_rssi: -43,
             network_latency: 30, module_firmware_version: "3.2.2", mcu_hardware_model: "x", ip: "10.0.0.5",
             fixedconf: "0,0", muteon: false, timeron: [du: 0, ts: 0]]
    assert app.dreoState(s) == [poweron: true, windlevel: 3, fixedconf: "0,0", muteon: false, timeron: [du: 0, ts: 0]]
    assert app.dreoState(null) == [:]
}

check("dreoCommand: an offline device gets nothing yet; it is re-read first") {
    FakeDevice cd = fan("DR-HTF011S")
    cd.current.healthStatus = "offline"
    assert app.dreoCommand(cd, ON9, "on", []) == null
    assert app.log.at("info").last().contains("shows offline; checking with Dreo before sending on")
}

// ---------------------------------------------------------------- head position and oscillation range

// From hass-dreo's DR-HPF004S and DR-HPF008S state samples.
@Field Map HPF004S = [poweron: true, windlevel: 3, mode: 1, oscmode: 0, cruiseconf: "90,75,-30,-75", fixedconf: "0,-5"]
@Field Map HPF008S = [poweron: true, windlevel: 3, mode: 1, oscmode: 0, cruiseconf: "90,60,-30,-60", fixedconf: "-15,10"]

check("confParts") {
    assert app.confParts("0,-5", 2) == [0, -5]
    assert app.confParts(" 90, 75,-30,-75 ", 4) == [90, 75, -30, -75]
    assert app.confParts("90,75,-30,-75,1", 4) == [90, 75, -30, -75]
    assert app.confParts("1", 2) == null
    assert app.confParts("a,b", 2) == null
    assert app.confParts(null, 2) == null
    assert app.confParts(12, 2) == null
}

check("profile: head angle ranges from the model, family, defaults and reports") {
    assert prof("DR-HPF004S").subMap(["h", "v", "settle"]) == [h: [-75, 75], v: [-30, 90], settle: 0]
    assert prof("DR-HPF008S").subMap(["h", "v"]) == [h: [-60, 60], v: [-30, 90]]  // family vertical, default horizontal
    assert prof("DR-HTF011S").subMap(["h", "v"]) == [h: [-60, 60], v: [0, 90]]
    assert prof("DR-HPF017S").settle == 8 && prof("DR-HAF004S").settle == 8
    // A fan reporting an angle beyond the table widens it.
    assert prof("DR-HPF008S", [fixedconf: "-40,70"]).subMap(["h", "v"]) == [h: [-60, 70], v: [-40, 90]]
    assert prof("DR-HPF008S", [cruiseconf: "95,60,-30,-80"]).subMap(["h", "v"]) == [h: [-80, 60], v: [-30, 95]]
}

check("dreoEvents: head position and oscillation limits") {
    Map e = events("DR-HPF004S", HPF004S)
    assert e.subMap(["headVertical", "headHorizontal", "oscillationTop", "oscillationRight", "oscillationBottom", "oscillationLeft"]) ==
        [headVertical: 0, headHorizontal: -5, oscillationTop: 90, oscillationRight: 75, oscillationBottom: -30, oscillationLeft: -75]
    assert app.dreoEvents(fan("DR-HPF004S"), HPF004S).find { it.name == "headVertical" }.unit == "°"
    Map none = events("DR-HAF001S", [poweron: true, windlevel: 2, mode: 1, hoscon: false])
    assert !none.keySet().any { it.startsWith("head") || it.startsWith("oscillationT") }
    assert !events("DR-HPF004S", HPF004S + [fixedconf: "junk"]).containsKey("headVertical")
}

check("setHeadPosition: both, one, clamped, and stopping oscillation") {
    assert cmd("DR-HPF004S", HPF004S, "setHeadPosition", [30, 20]) == [fixedconf: "30,20"]
    assert cmd("DR-HPF004S", HPF004S, "setHeadPosition", [null, 40]) == [fixedconf: "0,40"]
    assert cmd("DR-HPF004S", HPF004S, "setHeadPosition", [45]) == [fixedconf: "45,-5"]
    assert cmd("DR-HPF004S", HPF004S, "setHeadPosition", ["", "-20"]) == [fixedconf: "0,-20"]
    assert cmd("DR-HPF004S", HPF004S, "setHeadPosition", [120, -100]) == [fixedconf: "90,-75"]
    assert cmd("DR-HPF008S", HPF008S, "setHeadPosition", [-45, 0]) == [fixedconf: "-30,0"]
    // Oscillating: a fixed position switches oscillation off.
    assert cmd("DR-HPF004S", HPF004S + [oscmode: 3], "setHeadPosition", [10, 10]) == [fixedconf: "10,10", oscmode: 0]
    assert cmd("DR-HPF004S", HPF004S.findAll { it.key != "oscmode" } + [hoscon: true, voscon: false], "setHeadPosition", [10, 10]) ==
        [fixedconf: "10,10", hoscon: false, voscon: false]
    throwsIAE { cmd("DR-HAF001S", [poweron: true, hoscon: false], "setHeadPosition", [10, 10]) }
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setHeadPosition", ["up", 10]) }
}

check("setOscillationRange: limits, kept values, clamp and minimum span") {
    assert cmd("DR-HPF004S", HPF004S, "setOscillationRange", [-45, 45, 0, 60]) == [cruiseconf: "60,45,0,-45"]
    assert cmd("DR-HPF004S", HPF004S, "setOscillationRange", [-30]) == [cruiseconf: "90,75,-30,-30"]
    assert cmd("DR-HPF004S", HPF004S, "setOscillationRange", [null, null, null, 45]) == [cruiseconf: "45,75,-30,-75"]
    assert cmd("DR-HPF008S", HPF008S, "setOscillationRange", [-90, 90]) == [cruiseconf: "90,60,-30,-60"]
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setOscillationRange", [0, 20]) }
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setOscillationRange", [null, null, 40, 60]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setOscillationRange", [-30, 30]) }
}

check("head position waits for the head to settle on DR-HPF017S") {
    def sent = []
    def conn = new FakeDevice(deviceNetworkId: "dreo-connection-${app.app.id}", name: "conn")
    conn.metaClass.sendCommand = { String sn, Map p -> sent << [sn, p] }
    app.children = [conn]
    FakeDevice cd = new FakeDevice(id: 7, deviceNetworkId: "dreo-SN7", name: "DR-HPF017S", data: [model: "DR-HPF017S", sn: "SN7"])
    Map s = [poweron: true, oscmode: 0, fixedconf: "0,0", cruiseconf: "90,75,-30,-75"]
    int timers = app.callsNamed("runInMillis").size()
    assert app.dreoCommand(cd, s, "setHeadPosition", [10, 10]) == [fixedconf: "10,10"]
    assert sent*.get(1) == [[fixedconf: "10,10"]]
    // Within 8 s: shown straight away, but held; a newer one replaces it.
    assert app.dreoCommand(cd, s, "setHeadPosition", [20, 20]) == [fixedconf: "20,20"]
    assert app.dreoCommand(cd, s, "setHeadPosition", [30, 30]) == [fixedconf: "30,30"]
    assert sent.size() == 1
    List t = app.callsNamed("runInMillis").drop(timers)
    assert t*.get(2).unique() == ["flushHead"] && t[0][1] > 7000
    // Too early: waits again; after the settle time the newest goes out once.
    app.flushHead(t[0][3].data)
    assert sent.size() == 1
    hub.advance(8100)
    app.flushHead(t[0][3].data)
    app.flushHead(t[0][3].data)
    assert sent*.get(1) == [[fixedconf: "10,10"], [fixedconf: "30,30"]]
    // Other commands are never held.
    app.dreoCommand(cd, s, "setSpeedLevel", [3])
    assert sent.size() == 3
    // DR-HPF004S has no settle time.
    app.dreoCommand(fan("DR-HPF004S"), HPF004S, "setHeadPosition", [5, 5])
    app.dreoCommand(fan("DR-HPF004S"), HPF004S, "setHeadPosition", [6, 6])
    assert sent.size() == 5
    app.children = []
}

check("a waiting head position doesn't hold back, drop or reorder stopping oscillation") {
    def sent = []
    def conn = new FakeDevice(deviceNetworkId: "dreo-connection-${app.app.id}", name: "conn")
    conn.metaClass.sendCommand = { String sn, Map p -> sent << p }
    app.children = [conn]
    FakeDevice cd = new FakeDevice(id: 8, deviceNetworkId: "dreo-SN8", name: "DR-HPF017S", data: [model: "DR-HPF017S", sn: "SN8"])
    Map s = [poweron: true, oscmode: 0, fixedconf: "0,0", cruiseconf: "90,75,-30,-75"]
    app.dreoCommand(cd, s, "setHeadPosition", [10, 10])
    // Oscillating again within the settle time: the new position waits, stopping oscillation doesn't.
    s = s + [fixedconf: "10,10", oscmode: 1]
    assert app.dreoCommand(cd, s, "setHeadPosition", [20, 20]) == [fixedconf: "20,20", oscmode: 0]
    assert sent == [[fixedconf: "10,10"], [oscmode: 0]]
    // A newer position (the driver already shows oscillation off) replaces only the position.
    s = s + [oscmode: 0]
    app.dreoCommand(cd, s, "setHeadPosition", [30, 30])
    // Oscillation switched on meanwhile goes now, and the waiting position is
    // dropped: sent later, it would stop the sweep.
    app.dreoCommand(cd, s, "setOscillation", ["horizontal"])
    hub.advance(8100)
    app.flushHead([sn: "SN8", settle: 8])
    assert sent == [[fixedconf: "10,10"], [oscmode: 0], [oscmode: 1]]
    // Switching oscillation off keeps a waiting position.
    app.dreoCommand(cd, s, "setHeadPosition", [40, 40])     // goes now
    app.dreoCommand(cd, s, "setHeadPosition", [50, 50])     // waits
    app.dreoCommand(cd, s, "setOscillation", ["off"])
    hub.advance(8100)
    app.flushHead([sn: "SN8", settle: 8])
    assert sent.takeRight(3) == [[fixedconf: "40,40"], [oscmode: 0], [fixedconf: "50,50"]]
    app.children = []
}

// ---------------------------------------------------------------- humidifiers

// A humidifier device as the driver passes it, with data values from discovery.
FakeDevice hum(String model = "DR-HHM001S", Map limits = [humidityRange: "30-80", mistRange: "1-6"]) {
    new FakeDevice(id: 5, deviceNetworkId: "dreo-SN5", name: model, data: [model: model, sn: "SN5", kind: "humidifier"] + limits)
}
Map hEvents(Map s, FakeDevice d = hum()) {
    Map out = [:]
    app.dreoEvents(d, s).each { e -> assert !out.containsKey(e.name); out[e.name] = e.value }
    return out
}
Map hCmd(Map s, String name, List args = [], FakeDevice d = hum()) {
    app.humidifierCommand(app.humidifierProfile(d), s, name, args)
}

// DR-HHM001S (HM311S) state, from hass-dreo's get_device_state_HHM001S_1.json.
@Field Map HHM001S = [poweron: true, mode: 1, rh: 47, rhautolevel: 60, rhsleeplevel: 70, foglevel: 6,
    wrong: 0, ledlevel: 2, muteon: false, rgblevel: 2, suspend: false, worktime: 0, scheon: false]

check("humidifiers are discovered as their own kind") {
    assert app.kindOf("DR-HHM001S") == "humidifier"
    assert app.kindOf("DR-HHM003S") == "humidifier"
    assert app.kindOf("DR-HAP001S") == null
}

check("humidifierLimits: from the HHM001S schedule controls, and humRange first") {
    Map conf = [schedule: [modes: [
        [title: "device_control_mode_auto", cmd: "mode", value: 1,
         controls: [[type: 0, cmd: ["rhautolevel"], startValue: 30, endValue: 80]]],
        [title: "device_control_mode_manual", cmd: "mode", value: 0,
         controls: [[type: 1, cmd: ["foglevel"], startValue: 0, validValue: 1, endValue: 6]]],
        [title: "device_control_mode_sleep", cmd: "mode", value: 2, controls: []],
    ]]]
    assert app.humidifierLimits(conf) == [humidityRange: "30-80", mistRange: "1-6"]
    assert app.humidifierLimits(conf + [humRange: [controlMin: 35, controlMax: 85]]).humidityRange == "35-85"
    assert app.humidifierLimits(null) == [:]
    assert app.humidifierLimits([schedule: [modes: "junk"]]) == [:]
}

check("humidifierProfile: data values, else defaults") {
    Map p = app.humidifierProfile(hum())
    assert p.target == [30, 80] && p.mist == [1, 6]
    Map d = app.humidifierProfile(hum("DR-HHM003S", [:]))
    assert d.target == [30, 90] && d.mist == [1, 6]
    assert d.modes*.get(0) == ["manual", "auto", "sleep"]
}

check("dreoEvents: DR-HHM001S sample state") {
    Map e = hEvents(HHM001S)
    assert json(e.remove("supportedModes")) == ["manual", "auto", "sleep"]
    assert e == [switch: "on", humidity: 47, targetHumidity: 60, mode: "auto", mistLevel: 6, maxMistLevel: 6,
                 operatingState: "humidifying", waterTank: "ok", display: "on", panelSound: "on", ambientLight: "on",
                 useSinceCleaning: 0, schedules: "off"]
    assert hEvents(HHM001S + [filtertime: 75]).filterLife == 75
}

check("dreoEvents: humidifier sleep target, paused, empty tank, off") {
    assert hEvents(HHM001S + [mode: 2]).targetHumidity == 70
    assert hEvents(HHM001S + [mode: 0]).subMap(["mode", "targetHumidity"]) == [mode: "manual", targetHumidity: 60]
    assert hEvents(HHM001S + [suspend: true]).operatingState == "idle"
    assert hEvents(HHM001S + [poweron: false]).subMap(["switch", "operatingState"]) == [switch: "off", operatingState: "off"]
    assert hEvents(HHM001S + [wrong: 1]).waterTank == "empty"
    assert hEvents(HHM001S + [wrong: 4]).waterTank == "empty"
    assert hEvents(HHM001S + [wrong: 7]).waterTank == "fault 7"
    assert hEvents(HHM001S + [ledlevel: 0, muteon: true, rgblevel: 0]).subMap(["display", "panelSound", "ambientLight"]) ==
        [display: "off", panelSound: "off", ambientLight: "off"]
    // Newer models: water level %, ambient_switch over rgblevel.
    Map n = hEvents([poweron: true, waterlevel: 40, ambient_switch: false, rgblevel: 2])
    assert n.waterLevel == 40 && n.ambientLight == "off"
    // A reported mist level above the range widens it.
    assert hEvents([foglevel: 8]).maxMistLevel == 8
    // Nothing known: no switch, humidity or state events.
    Map none = hEvents([:])
    assert none.keySet() == ["supportedModes", "maxMistLevel"] as Set
}

check("humidifierCommand: power and mode") {
    assert hCmd(HHM001S, "on") == [poweron: true]
    assert hCmd(HHM001S, "off") == [poweron: false]
    assert hCmd(HHM001S, "setMode", ["sleep"]) == [mode: 2]
    assert hCmd(HHM001S + [poweron: false], "setMode", ["manual"]) == [poweron: true, mode: 0]
    throwsIAE { hCmd(HHM001S, "setMode", ["turbo"]) }
    throwsIAE { hCmd(HHM001S, "setSpeed", ["low"]) }
}

check("humidifierCommand: target humidity follows the mode") {
    assert hCmd(HHM001S, "setTargetHumidity", [55]) == [rhautolevel: 55]
    assert hCmd(HHM001S, "setTargetHumidity", ["45"]) == [rhautolevel: 45]
    assert hCmd(HHM001S + [mode: 2], "setTargetHumidity", [65]) == [rhsleeplevel: 65]
    // Sleep on a model without its own sleep target sets the auto one.
    assert hCmd(HHM001S.findAll { it.key != "rhsleeplevel" } + [mode: 2], "setTargetHumidity", [65]) == [rhautolevel: 65]
    // Manual has no target: go to auto.
    assert hCmd(HHM001S + [mode: 0], "setTargetHumidity", [50]) == [rhautolevel: 50, mode: 1]
    assert hCmd(HHM001S + [poweron: false], "setTargetHumidity", [50]) == [poweron: true, rhautolevel: 50]
    throwsIAE { hCmd(HHM001S, "setTargetHumidity", [85]) }   // HHM001S tops out at 80
    throwsIAE { hCmd(HHM001S, "setTargetHumidity", [25]) }
    throwsIAE { hCmd(HHM001S, "setTargetHumidity", ["lots"]) }
    assert hCmd(HHM001S, "setTargetHumidity", [85], hum("DR-HHM003S", [:])) == [rhautolevel: 85]
}

check("humidifierCommand: mist level goes to manual") {
    assert hCmd(HHM001S, "setMistLevel", [3]) == [foglevel: 3, mode: 0]
    assert hCmd(HHM001S + [mode: 0], "setMistLevel", [6]) == [foglevel: 6]
    assert hCmd(HHM001S + [mode: 0, poweron: false], "setMistLevel", [2]) == [poweron: true, foglevel: 2]
    assert hCmd(HHM001S, "setMistLevel", [0]) == [poweron: false]
    throwsIAE { hCmd(HHM001S, "setMistLevel", [7]) }
}

check("humidifierCommand: display, panel sound, ambient light") {
    assert hCmd(HHM001S, "setDisplay", ["off"]) == [ledlevel: 0]
    assert hCmd(HHM001S, "setDisplay", ["on"]) == [ledlevel: 2]
    assert hCmd(HHM001S, "setPanelSound", ["off"]) == [muteon: true]
    assert hCmd(HHM001S, "setAmbientLight", ["off"]) == [rgblevel: 0]
    assert hCmd([ambient_switch: false, rgblevel: 0], "setAmbientLight", ["on"]) == [ambient_switch: true]
    throwsIAE { hCmd([:], "setDisplay", ["on"]) }
    throwsIAE { hCmd([:], "setPanelSound", ["on"]) }
    throwsIAE { hCmd([:], "setAmbientLight", ["on"]) }
    throwsIAE { hCmd(HHM001S, "setDisplay", ["auto"]) }
}

// ---------------------------------------------------------------- controlsConf (fanLimits)

/** A fixture's device-list entry (tests/fixtures/<dir>/<model>.json). */
Map fixtureDevice(String dir, String model) {
    json(new File(root, "tests/fixtures/${dir}/${model}.json").text).device
}

check("fanLimits: speed range and Mode control from real device lists") {
    assert app.fanLimits(fixtureDevice("hass-dreo", "DR-HAF001S").controlsConf) ==
        [speedRange: "1-4", modes: "normal=1,natural=2,sleep=3,auto=4"]
    // The schedule's custom 6 doesn't count: the table has no custom for this model.
    // DR-HTF005S also has a 1-12 Speed under extraConfigs, for one MCU only: the main control wins.
    assert app.fanLimits(fixtureDevice("hass-dreo", "DR-HTF005S").controlsConf) ==
        [speedRange: "1-9", modes: "normal=1,natural=2,sleep=3,auto=4"]
    // Swapped auto/natural, as the model table says.
    assert app.fanLimits(fixtureDevice("hass-dreo", "DR-HPF025S").controlsConf) ==
        [speedRange: "1-9", modes: "normal=1,auto=2,sleep=3,natural=4,turbo=5"]
    // Ceiling fans list modes under CFFan: this one has auto 5, not reverse 4; its CFAtmosphere control is a light ring.
    assert app.fanLimits(fixtureDevice("hass-dreo", "DR-HCF002S").controlsConf) ==
        [speedRange: "1-12", modes: "normal=1,natural=2,sleep=3,auto=5", lightRing: "true"]
    assert app.fanLimits([control: [[type: "Speed", items: [[value: 1], [value: 9]]], [type: "Other", cmd: "atmon", items: []]]]) ==
        [speedRange: "1-9", lightRing: "true"]
}

check("fanLimits: a template reference or junk gives nothing, so the model table applies") {
    assert app.fanLimits(fixtureDevice("hass-dreo", "DR-HPF008S").controlsConf) == [:]
    assert app.fanLimits(fixtureDevice("hass-dreo", "DR-HPF004S").controlsConf) == [:]
    assert app.fanLimits(null) == [:]
    assert app.fanLimits([control: "junk", extraConfigs: [[key: "control", value: "junk"]]]) == [:]
    assert app.fanLimits([control: [[type: "Speed", items: [[value: 3]]]]]) == [:]
    assert app.fanLimits([control: [[type: "Mode", items: [[text: "x", value: "high"]]]]]) == [:]
}

check("fanLimits: an Oscillation switch with no presets doesn't hide a later control's angles") {
    Map conf = [control: [[type: "Oscillation", cmd: "hoscon", items: []]],
        extraConfigs: [[key: "control", value: [[type: "Oscillation", cmd: "shakehorizon", items: [[value: 30], [value: 45], [value: 60], [value: 90]]]]]]]
    assert app.fanLimits(conf).oscillationAngles == "30,45,60,90"
    assert !app.fanLimits([control: [[type: "Oscillation", cmd: "hoscon", items: []]]]).containsKey("oscillationAngles")
}

check("fanLimits: the test DR-HPF004S keeps turbo and custom, which its table and schedule both list") {
    Map conf = fixtureDevice("digital-homesteaders", "DR-HPF004S-1").controlsConf
    assert app.fanLimits(conf, "DR-HPF004S") == [speedRange: "1-9", modes: "normal=1,natural=2,sleep=3,auto=4,turbo=5,custom=6"]
    // Without the model, only the mode buttons.
    assert app.fanLimits(conf).modes == "normal=1,natural=2,sleep=3,auto=4"
    // The test DR-HTF004S isn't in the table: its own list says 1-12, not the family's 1-9.
    // Its Oscillation control lists the sweep-width presets (shakehorizonangle).
    assert app.fanLimits(fixtureDevice("digital-homesteaders", "DR-HTF004S").controlsConf, "DR-HTF004S") ==
        [speedRange: "1-12", modes: "normal=1,natural=2,sleep=3,auto=4", oscillationAngles: "30,60,90,120"]
    assert app.fanLimits(fixtureDevice("digital-homesteaders", "DR-HAF001S").controlsConf, "DR-HAF001S") ==
        [speedRange: "1-4", modes: "normal=1,natural=2,sleep=3,auto=4"]
    assert app.fanLimits(fixtureDevice("digital-homesteaders", "DR-HPF008S").controlsConf, "DR-HPF008S") == [:]
}

check("humidifierLimits: the test DR-HHM001S, where humRange (30-90, MCU 0.7.1+) beats the schedule's 80") {
    assert app.humidifierLimits(fixtureDevice("digital-homesteaders", "DR-HHM001S").controlsConf) == [humidityRange: "30-90", mistRange: "1-6"]
}

check("fanLimits: every Speed item counts; schedule modes only without a mode control") {
    assert app.fanLimits([control: [[type: "Speed", items: [[value: 1], [value: 2], [value: 3], [value: 4]]]]]) == [speedRange: "1-4"]
    assert app.fanLimits([extraConfigs: [[key: "control", value: [[type: "Speed", items: [[value: 1], [value: 6]]]]]]]) == [speedRange: "1-6"]
    assert app.fanLimits([schedule: [modes: [[title: "device_fans_mode_straight", value: 1], [title: "device_control_mode_turbo", value: 5]]]]) ==
        [modes: "normal=1,turbo=5"]
    // Unknown texts keep Dreo's own name.
    assert app.fanLimits([control: [[type: "Mode", items: [[text: "fan_2in1_breeze", value: 7]]]]]) == [modes: "fan_2in1_breeze=7"]
}

check("profile: controlsConf data overrides the model table, except speedFixed models") {
    Map p = app.profile("DR-HTF004S", [:], [:], [speedRange: "1-12", modes: "normal=1,auto=2"])
    assert p.min == 1 && p.max == 12 && p.modes == [["normal", 1], ["auto", 2]]
    Map q = app.profile("DR-HTF004S", [:], [:], [:])
    assert q.max == 9 && q.modes == app.MODE_LISTS.basic
    // Garbled data values fall back too.
    Map g = app.profile("DR-HAF001S", [:], [:], [speedRange: "fast", modes: "normal"])
    assert g.max == 4 && g.modes == app.MODE_LISTS.basic
    assert app.profile("DR-HTF007S", [:], [:], [speedRange: "1-4"]).max == 8
    // The fan's speedSteps preference still wins.
    assert app.profile("DR-HTF004S", [:], [speedSteps: 6], [speedRange: "1-12"]).max == 6
}

check("dreoEvents use the device's controlsConf data values") {
    FakeDevice d = new FakeDevice(id: 1, deviceNetworkId: "dreo-SN1", name: "x",
        data: [model: "DR-HCF002S", sn: "SN1", speedRange: "1-12", modes: "normal=1,natural=2,sleep=3,auto=5"])
    Map ev = app.dreoEvents(d, [fanon: true, mode: 5, windlevel: 3]).collectEntries { [(it.child ? "${it.child}.${it.name}" : it.name): it.value] }
    assert ev.mode == "auto" && ev.speed == "auto"
    assert json(ev.supportedModes) == ["normal", "natural", "sleep", "auto"]
    assert app.dreoCommand(d, [fanon: true, mode: 1], "setMode", ["reverse"]) == null
}

check("driversFor: each model's own driver first, then the light children it may create") {
    assert app.driversFor("DR-HTF004S") == ["Dreo Fan"]
    assert app.driversFor("DR-HPF004S") == ["Dreo Fan"]
    assert app.driversFor("DR-HPF008S") == ["Dreo Fan", "Dreo Fan Light Ring"]
    assert app.driversFor("DR-HCF001S") == ["Dreo Ceiling Fan", "Dreo Ceiling Fan Light"]
    ["DR-HCF002S", "DR-HCF007S", "DR-HCF008S", "DR-HCF010S"].each {
        assert app.driversFor(it) == ["Dreo Ceiling Fan", "Dreo Ceiling Fan Light", "Dreo Fan Light Ring"]
    }
    assert app.driversFor("DR-HHM001S") == ["Dreo Humidifier"]
    // A ring found in the device's own controlsConf counts for a model the table doesn't list.
    assert app.driversFor("DR-HCF001S", [speedRange: "1-12", lightRing: "true"]) == ["Dreo Ceiling Fan", "Dreo Ceiling Fan Light", "Dreo Fan Light Ring"]
    assert app.driversFor("DR-HTF004S", [speedRange: "1-12"]) == ["Dreo Fan"]
    // An unknown family is a fan, as profile() treats it: never a null driver name.
    assert app.driversFor("DR-XYZ") == ["Dreo Fan"]
}

check("missingDriver: Hubitat's unknown-driver exception and the stand-in's, not other failures") {
    assert app.missingDriver(new IllegalArgumentException("no driver named Dreo Fan"))
    assert app.missingDriver(new RuntimeException("Device type 'Dreo Fan' in namespace 'dreo' not found"))
    assert !app.missingDriver(new IllegalArgumentException("duplicate dni dreo-SN1"))
}

// ---------------------------------------------------------------- settings shared by every kind (timers, schedules, adaptive brightness)

// The test DR-HTF004S: timers as Dreo reports them, a sweep-width preset, adaptive brightness.
@Field Map HTF004S = [poweron: true, windlevel: 5, windtype: 1, shakehorizon: true, shakehorizonangle: 60, voiceon: true,
    lightsensoron: true, scheon: true, scheid: 7, timeron: [du: 0, ts: 1783896410], timeroff: [du: 45, ts: 1783896410]]

check("settings: timers, schedules and adaptive brightness on fans, ceiling fans and humidifiers") {
    assert cmd("DR-HTF004S", HTF004S, "setTimerOff", [30]) == [timeroff: 30]
    assert cmd("DR-HTF004S", HTF004S, "setTimerOn", ["90"]) == [timeron: 90]
    assert cmd("DR-HTF004S", HTF004S, "setTimerOff", [0]) == [timeroff: 0]
    assert cmd("DR-HTF004S", HTF004S, "setTimerOff", [719]) == [timeroff: 719]
    throwsIAE { cmd("DR-HTF004S", HTF004S, "setTimerOff", [720]) }
    throwsIAE { cmd("DR-HTF004S", HTF004S, "setTimerOff", [-1]) }
    throwsIAE { cmd("DR-HTF004S", HTF004S, "setTimerOff", ["soon"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setTimerOff", [30]) }
    assert cmd("DR-HTF004S", HTF004S, "setSchedules", ["off"]) == [scheon: false]
    assert cmd("DR-HTF004S", HTF004S, "setAdaptiveBrightness", ["off"]) == [lightsensoron: false]
    throwsIAE { cmd("DR-HTF011S", ON9, "setSchedules", ["off"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setAdaptiveBrightness", ["on"]) }
    throwsIAE { cmd("DR-HTF004S", HTF004S, "setSchedules", ["later"]) }
    // Ceiling fans and humidifiers share them.
    assert cmd("DR-HCF007S", CEIL + [timeroff: [du: 0, ts: 1], scheon: true], "setTimerOff", [60]) == [timeroff: 60]
    assert cmd("DR-HCF007S", CEIL + [scheon: true], "setSchedules", ["on"]) == [scheon: true]
    assert hCmd(HHM001S + [timeron: [du: 0, ts: 1]], "setTimerOn", [15]) == [timeron: 15]
    assert hCmd(HHM001S, "setSchedules", ["on"]) == [scheon: true]
    assert hCmd(HHM001S + [lightsensoron: false], "setAdaptiveBrightness", ["on"]) == [lightsensoron: true]
    throwsIAE { hCmd(HHM001S, "setAdaptiveBrightness", ["on"]) }
}

check("dreoEvents: timers (reported {du} or a sent number), schedules, adaptive brightness") {
    Map e = events("DR-HTF004S", HTF004S)
    assert e.subMap(["timerOn", "timerOff", "schedules", "adaptiveBrightness"]) ==
        [timerOn: 0, timerOff: 45, schedules: "on", adaptiveBrightness: "on"]
    // A command's own params fold in as plain minutes until the fan reports.
    assert events("DR-HTF004S", HTF004S + [timeroff: 30, scheon: false]).subMap(["timerOff", "schedules"]) == [timerOff: 30, schedules: "off"]
    assert !events("DR-HTF011S", ON9).keySet().any { it in ["timerOn", "timerOff", "schedules", "adaptiveBrightness"] }
    Map c = events("DR-HCF007S", CEIL + [timeroff: [du: 10, ts: 1], scheon: true])
    assert c.timerOff == 10 && c.schedules == "on"
    assert hEvents(HHM001S + [lightsensoron: false, timeroff: [du: 5, ts: 1]]).subMap(["adaptiveBrightness", "timerOff"]) ==
        [adaptiveBrightness: "off", timerOff: 5]
}

// ---------------------------------------------------------------- oscillation angle presets (tower fans)

check("anglesOf and profile angles: the device list, else the presets, plus a reported angle") {
    assert app.anglesOf("30,60,90,120") == [30, 60, 90, 120]
    assert app.anglesOf("30,x") == null && app.anglesOf("30") == null && app.anglesOf(null) == null
    assert prof("DR-HTF004S").angles == [30, 60, 90, 120]
    assert app.profile("DR-HTF004S", [:], [:], [oscillationAngles: "30,60,90,120,150"]).angles == [30, 60, 90, 120, 150]
    assert prof("DR-HTF004S", [shakehorizonangle: 150]).angles == [30, 60, 90, 120, 150]
    assert prof("DR-HTF004S", [shakehorizonangle: 60]).angles == [30, 60, 90, 120]
}

check("setOscillationAngle: a preset only, on fans that report the angle") {
    assert cmd("DR-HTF004S", HTF004S, "setOscillationAngle", [90]) == [shakehorizonangle: 90]
    assert cmd("DR-HTF004S", HTF004S, "setOscillationAngle", ["120"]) == [shakehorizonangle: 120]
    throwsIAE { cmd("DR-HTF004S", HTF004S, "setOscillationAngle", [45]) }
    throwsIAE { cmd("DR-HTF004S", HTF004S, "setOscillationAngle", ["wide"]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setOscillationAngle", [60]) }
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setOscillationAngle", [60]) }
}

check("dreoEvents: oscillationAngle and its presets") {
    Map e = events("DR-HTF004S", HTF004S)
    assert e.oscillationAngle == 60 && json(e.supportedOscillationAngles) == [30, 60, 90, 120]
    assert !events("DR-HPF004S", HPF004S).containsKey("oscillationAngle")
}

// ---------------------------------------------------------------- sweep presets and 5° limits (two-axis fans)

check("setOscillationSweep: horizontal centred, vertical from the lowest tilt, within the head's reach") {
    assert cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [90]) == [cruiseconf: "90,45,-30,-45"]
    assert cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [150]) == [cruiseconf: "90,75,-30,-75"]
    assert cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [null, 60]) == [cruiseconf: "30,75,-30,-75"]
    assert cmd("DR-HPF004S", HPF004S, "setOscillationSweep", ["", "120"]) == [cruiseconf: "90,75,-30,-75"]
    assert cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [60, 30]) == [cruiseconf: "0,30,-30,-30"]
    // Widths go in 10° (horizontal) and 5° (vertical) steps.
    assert cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [94, 33]) == [cruiseconf: "5,45,-30,-45"]
    // DR-HPF008S reaches ±60° across.
    assert cmd("DR-HPF008S", HPF008S, "setOscillationSweep", [120]) == [cruiseconf: "90,60,-30,-60"]
    throwsIAE { cmd("DR-HPF008S", HPF008S, "setOscillationSweep", [150]) }
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [20]) }
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [null, 130]) }
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setOscillationSweep", []) }
    throwsIAE { cmd("DR-HPF004S", HPF004S, "setOscillationSweep", [null, null]) }
    throwsIAE { cmd("DR-HTF011S", ON9, "setOscillationSweep", [90]) }
}

check("setOscillationRange rounds limits to 5°") {
    assert cmd("DR-HPF004S", HPF004S, "setOscillationRange", [-47, 43, 2, 58]) == [cruiseconf: "60,45,0,-45"]
    assert cmd("DR-HPF004S", HPF004S, "setOscillationRange", [-73]) == [cruiseconf: "90,75,-30,-75"]
    assert app.roundTo(-47, 5) == -45 && app.roundTo(43, 5) == 45 && app.roundTo(94, 10) == 90 && app.roundTo(95, 10) == 100
}

// ---------------------------------------------------------------- temperature offset, mist, ring effect speed

check("setTemperatureOffset: clamped to ±10, on fans that report tempoffset") {
    assert cmd("DR-HPF008S", HPF008S + [tempoffset: -5], "setTemperatureOffset", [3]) == [tempoffset: 3]
    assert cmd("DR-HPF008S", HPF008S + [tempoffset: -5], "setTemperatureOffset", ["-2"]) == [tempoffset: -2]
    assert cmd("DR-HPF008S", HPF008S + [tempoffset: -5], "setTemperatureOffset", [25]) == [tempoffset: 10]
    assert cmd("DR-HPF008S", HPF008S + [tempoffset: -5], "setTemperatureOffset", [-25]) == [tempoffset: -10]
    throwsIAE { cmd("DR-HPF008S", HPF008S, "setTemperatureOffset", [3]) }
    throwsIAE { cmd("DR-HPF008S", HPF008S + [tempoffset: -5], "setTemperatureOffset", ["warm"]) }
    assert events("DR-HPF008S", HPF008S + [tempoffset: -5]).temperatureOffset == -5
    assert !events("DR-HPF008S", HPF008S).containsKey("temperatureOffset")
}

check("setMist on misting tower fans") {
    assert cmd("DR-HTF021S", ON9 + [miston: false], "setMist", ["on"]) == [miston: true]
    assert cmd("DR-HTF021S", ON9 + [miston: true], "setMist", ["off"]) == [miston: false]
    throwsIAE { cmd("DR-HTF011S", ON9, "setMist", ["on"]) }
    assert events("DR-HTF021S", ON9 + [miston: true]).mist == "on"
    assert !events("DR-HTF011S", ON9).containsKey("mist")
}

check("light ring effect speed (atmspeed 1-3)") {
    assert cmd("DR-HPF008S", RING + [atmspeed: 2], "ring.setEffectSpeed", ["high"]) == [atmspeed: 3]
    assert cmd("DR-HPF008S", RING + [atmspeed: 2], "ring.setEffectSpeed", ["Low"]) == [atmspeed: 1]
    // A dark ring lights up, as with any other ring change.
    assert cmd("DR-HPF008S", RING + [atmspeed: 2, atmon: false], "ring.setEffectSpeed", ["medium"]) == [atmspeed: 2, atmon: true]
    throwsIAE { cmd("DR-HPF008S", RING + [atmspeed: 2], "ring.setEffectSpeed", ["fast"]) }
    throwsIAE { cmd("DR-HPF008S", RING, "ring.setEffectSpeed", ["high"]) }
    assert events("DR-HPF008S", RING + [atmspeed: 2])."ring.effectSpeed" == "medium"
    assert events("DR-HPF008S", RING + [atmspeed: 3])."ring.effectSpeed" == "high"
    assert !events("DR-HPF008S", RING + [atmspeed: 9]).containsKey("ring.effectSpeed")
    assert !events("DR-HPF008S", RING).containsKey("ring.effectSpeed")
}

// ---------------------------------------------------------------- humidifier display and light levels

check("humidifierCommand: display and ambient light come back at the level last seen on") {
    Map p = app.humidifierProfile(hum())
    assert app.humidifierCommand(p, HHM001S + [ledlevel: 0], "setDisplay", ["on"], [ledlevelOn: 3]) == [ledlevel: 3]
    assert app.humidifierCommand(p, HHM001S + [rgblevel: 0], "setAmbientLight", ["on"], [rgblevelOn: 1]) == [rgblevel: 1]
    // Nothing remembered (or a zero): the Dreo app's middle level.
    assert app.humidifierCommand(p, HHM001S + [ledlevel: 0], "setDisplay", ["on"], [ledlevelOn: 0]) == [ledlevel: 2]
    assert app.humidifierCommand(p, HHM001S + [ledlevel: 0], "setDisplay", ["on"], [:]) == [ledlevel: 2]
    assert app.humidifierCommand(p, HHM001S + [ledlevel: 0], "setDisplay", ["on"]) == [ledlevel: 2]
    assert app.humidifierCommand(p, HHM001S, "setDisplay", ["off"], [ledlevelOn: 3]) == [ledlevel: 0]
}

Check.finish()
