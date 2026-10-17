/**
 * Dreo Humidifier: a Dreo humidifier (DR-HHM...), created by the Dreo Connect app.
 *
 * The device keeps the humidifier's merged Dreo state; the app turns it into
 * events and turns commands into Dreo control params (see apps/dreo-connect.groovy).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

metadata {
    definition(name: "Dreo Humidifier", namespace: "dreo", author: "Digital Homesteaders", importUrl: "") {
        capability "Actuator"
        capability "Switch"
        capability "RelativeHumidityMeasurement"
        capability "Refresh"

        attribute "targetHumidity", "number"
        attribute "mode", "string"
        attribute "supportedModes", "JSON_OBJECT"
        attribute "mistLevel", "number"
        attribute "maxMistLevel", "number"
        attribute "operatingState", "enum", ["off", "humidifying", "idle"]
        attribute "waterTank", "string"
        attribute "waterLevel", "number"
        attribute "useSinceCleaning", "number"
        attribute "filterLife", "number"
        attribute "display", "string"
        attribute "panelSound", "string"
        attribute "ambientLight", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "setTargetHumidity", [[name: "Target humidity*", type: "NUMBER", description: "Relative humidity to hold, in % (switches Manual mode to Auto)"]]
        command "setMode", [[name: "Mode*", type: "ENUM", description: "Humidifier mode to set", constraints: ["manual", "auto", "sleep"]]]
        command "setMistLevel", [[name: "Mist level*", type: "NUMBER", description: "Mist level, 1 to maxMistLevel (switches to Manual mode; 0 = off)"]]
        command "setDisplay", [[name: "Display*", type: "ENUM", description: "Display on or off", constraints: ["on", "off"]]]
        command "setPanelSound", [[name: "Panel sound*", type: "ENUM", description: "Beep when buttons are pressed", constraints: ["on", "off"]]]
        command "setAmbientLight", [[name: "Ambient light*", type: "ENUM", description: "Night light on or off", constraints: ["on", "off"]]]
    }
    preferences {
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        input "logEnable", "bool", title: "Debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    refresh()
}

def updated() {
    applyState()
}

def refresh() {
    parent?.refreshFan(device.getDataValue("sn"))
}

// ---------------------------------------------------------------- commands

def on() { sendDreo("on") }
def off() { sendDreo("off") }
def setTargetHumidity(value) { sendDreo("setTargetHumidity", [value]) }
def setMode(mode) { sendDreo("setMode", [mode]) }
def setMistLevel(level) { sendDreo("setMistLevel", [level]) }
def setDisplay(value) { sendDreo("setDisplay", [value]) }
def setPanelSound(value) { sendDreo("setPanelSound", [value]) }
def setAmbientLight(value) { sendDreo("setAmbientLight", [value]) }

private void sendDreo(String cmd, List args = []) {
    Map params = parent?.dreoCommand(device, state.dreo ?: [:], cmd, args)
    // Show the change straight away; the humidifier's own report follows.
    if (params) dreoUpdate(params, null)
}

// ---------------------------------------------------------------- state from the app

/** Merge reported Dreo state; `online` is true/false when known, else null. */
void dreoUpdate(Map reported, Boolean online) {
    Map s = (state.dreo ?: [:]) + (reported ?: [:])
    state.dreo = s
    applyState()
    if (online != null) dreoOnline(online)
}

void dreoOnline(boolean online) {
    emit([name: "healthStatus", value: online ? "online" : "offline"])
}

private void applyState() {
    if (!state.dreo || !parent) return
    parent.dreoEvents(device, state.dreo).each { Map e -> emit(e) }
}

private void emit(Map e) {
    // Most of each report is unchanged: skip those values rather than send them.
    boolean same = device.currentValue(e.name)?.toString() == e.value?.toString()
    if (same && !e.isStateChange) return
    if (txtEnable && !same && !(e.name in ["supportedModes", "maxMistLevel"])) {
        e.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}"
        log.info e.descriptionText
    }
    sendEvent(e)
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}
