/**
 * Dreo Ceiling Fan: a Dreo ceiling fan, created by the Dreo Connect app.
 *
 * The fan motor is this device's switch. The main light becomes a
 * "Dreo Ceiling Fan Light" child and a light ring (atm* keys) a "Dreo Fan Light Ring"
 * child, so dashboards and voice assistants see ordinary lights.
 *
 * Ceiling fans put every load behind a poweron gate; the app adds the gate
 * keys to each command (see gateParams in apps/dreo-connect.groovy).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.transform.Field

// Speed numbers for the setSpeedLevel dropdown, which can't vary per fan: Dreo fans have up to 12.
@Field static final List SPEED_LEVELS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"]

// Release version; the app compares it with its own (see dreoHello).
@Field static final String DRIVER_VERSION = "0.3.0"

metadata {
    definition(name: "Dreo Ceiling Fan", namespace: "dreo", author: "Digital Homesteaders", importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-dreo/main/drivers/dreo-ceiling-fan.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "FanControl"
        capability "Refresh"

        attribute "speedLevel", "number"
        attribute "maxSpeedLevel", "number"
        attribute "mode", "string"
        attribute "supportedModes", "JSON_OBJECT"
        attribute "panelSound", "string"
        attribute "childLock", "string"
        attribute "timerOn", "number"
        attribute "timerOff", "number"
        attribute "schedules", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "setSpeedLevel", [[name: "Speed (1 to 12)*", type: "ENUM", description: "The speed number as in the Dreo app (maxSpeedLevel is this fan's top one); a number above it uses the top speed", constraints: SPEED_LEVELS]]
        command "setMode", [[name: "Mode*", type: "ENUM", description: "Fan mode to set; this fan's modes are in supportedModes", constraints: ["normal", "natural", "sleep", "reverse", "auto"]]]
        command "setPanelSound", [[name: "Panel sound*", type: "ENUM", description: "Beep when buttons are pressed", constraints: ["on", "off"]]]
        command "setChildLock", [[name: "Child lock*", type: "ENUM", description: "Lock the fan's buttons", constraints: ["on", "off"]]]
        command "setTimerOn", [[name: "Minutes*", type: "NUMBER", description: "Switch the fan on after this many minutes (0 to 719; 0 cancels)"]]
        command "setTimerOff", [[name: "Minutes*", type: "NUMBER", description: "Switch the fan off after this many minutes (0 to 719; 0 cancels)"]]
        command "setSchedules", [[name: "Schedules*", type: "ENUM", description: "Run or pause the schedules set in the Dreo app", constraints: ["on", "off"]]]
    }
    preferences {
        input "speedSteps", "number", title: "Number of speed steps (0 = from the model)", defaultValue: 0, range: "0..20"
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
    }
}

// Child drivers by DNI suffix.
@Field static final Map CHILD_DRIVERS = [light: "Dreo Ceiling Fan Light", ring: "Dreo Fan Light Ring"]

// ---------------------------------------------------------------- lifecycle

def installed() {
    refresh()
}

def updated() {
    if (logEnable) runIn(1800, "logsOff")
    applyState()
}

def refresh() {
    parent?.refreshFan(device.getDataValue("sn"))
}

// ---------------------------------------------------------------- commands

def on() { sendDreo("on") }
def off() { sendDreo("off") }
def setSpeed(speed) { sendDreo("setSpeed", [speed]) }
def cycleSpeed() { sendDreo("cycleSpeed") }
def setSpeedLevel(level) { sendDreo("setSpeedLevel", [level]) }
def setMode(mode) { sendDreo("setMode", [mode]) }
def setPanelSound(value) { sendDreo("setPanelSound", [value]) }
def setChildLock(value) { sendDreo("setChildLock", [value]) }
def setTimerOn(minutes) { sendDreo("setTimerOn", [minutes]) }
def setTimerOff(minutes) { sendDreo("setTimerOff", [minutes]) }
def setSchedules(value) { sendDreo("setSchedules", [value]) }

// Light and light ring child (component) commands.
def componentOn(cd) { sendDreo("${childKind(cd)}.on") }
def componentOff(cd) { sendDreo("${childKind(cd)}.off") }
def componentSetLevel(cd, level, duration = null) { sendDreo("${childKind(cd)}.setLevel", [level]) }
def componentSetColorTemperature(cd, ct, level = null, transition = null) { sendDreo("light.setColorTemperature", [ct, level]) }
def componentSetColor(cd, Map color) { sendDreo("ring.setColor", [color]) }
def componentSetHue(cd, hue) { sendDreo("ring.setHue", [hue]) }
def componentSetSaturation(cd, saturation) { sendDreo("ring.setSaturation", [saturation]) }
def componentSetEffect(cd, effect) { sendDreo("ring.setEffect", [effect]) }
def componentSetNextEffect(cd) { sendDreo("ring.setNextEffect") }
def componentSetPreviousEffect(cd) { sendDreo("ring.setPreviousEffect") }
def componentSetEffectSpeed(cd, speed) { sendDreo("ring.setEffectSpeed", [speed]) }
def componentRefresh(cd) { refresh() }

private String childKind(cd) {
    return cd.deviceNetworkId.endsWith("-ring") ? "ring" : "light"
}

private void sendDreo(String cmd, List args = []) {
    Map params = parent?.dreoCommand(device, state.dreo ?: [:], cmd, args, opts())
    // Show the change straight away; the fan's own report follows.
    if (params) dreoUpdate(params, null)
}

// ---------------------------------------------------------------- state from the app

/** Merge reported Dreo state; `online` is true/false when known, else null. */
void dreoUpdate(Map reported, Boolean online) {
    Map s = (state.dreo ?: [:]) + (reported ?: [:])
    // The app drops private, hardware and noisy network keys.
    state.dreo = parent ? parent.dreoState(s) : s
    applyState()
    if (online != null) dreoOnline(online)
}

void dreoOnline(boolean online) {
    emit([name: "healthStatus", value: online ? "online" : "offline"])
}

private void applyState() {
    if (!state.dreo || !parent) return
    // Fans had a percentage level before 0.2.0; clear the leftover value.
    if (device.currentValue("level") != null) device.deleteCurrentState("level")
    List events = parent.dreoEvents(device, state.dreo, opts())
    events.each { Map e ->
        if (e.child) childEvent(e) else emit(e)
    }
}

private Map opts() {
    def ring = getChildDevice("${device.deviceNetworkId}-ring")
    return [
        speedSteps: (settings.speedSteps ?: 0) as int,
        ringHue: ring?.currentValue("hue"),
        ringSaturation: ring?.currentValue("saturation"),
    ]
}

private void emit(Map e) {
    // Most of each report is unchanged: skip those values rather than send them.
    boolean same = device.currentValue(e.name)?.toString() == e.value?.toString()
    if (same && !e.isStateChange) return
    if (txtEnable && !same && !(e.name.startsWith("supported") || e.name == "maxSpeedLevel")) {
        e.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}"
        log.info e.descriptionText
    }
    sendEvent(e)
}

private void childEvent(Map e) {
    String kind = e.child
    def cd = getChildDevice("${device.deviceNetworkId}-${kind}".toString()) ?: addChild(kind)
    if (cd) cd.parse([e.findAll { k, v -> k != "child" }])
}

/**
 * Create a light child, unless the app has noted its driver missing: the app
 * logs that once and clears it on Done, so the next report tries again.
 */
private def addChild(String kind) {
    String driver = CHILD_DRIVERS[kind]
    if (parent.lightDriverMissing(device, driver)) return null
    try {
        return addChildDevice("dreo", driver, "${device.deviceNetworkId}-${kind}".toString(),
            [name: driver, label: "${device.displayName} ${kind == 'ring' ? 'Light Ring' : 'Light'}", isComponent: true])
    } catch (ex) {
        parent.lightDriverFailed(device, driver, ex.message)
        return null
    }
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

/** Debug logging switches itself off 30 minutes after it's turned on. */
void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/** Called by the app on Done: this driver's (and its light children's) version where it differs from the app's. */
List dreoHello(String appVersion) {
    device.updateDataValue("driverVersion", DRIVER_VERSION)
    List odd = DRIVER_VERSION == appVersion ? [] : ["${device.displayName} (${DRIVER_VERSION})".toString()]
    getChildDevices().each { c -> odd.addAll(parent.lightHello(c, appVersion)) }
    return odd
}
