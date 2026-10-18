/**
 * Dreo Fan: a Dreo tower fan or air circulator, created by the Dreo Connect app.
 *
 * The device keeps the fan's merged Dreo state; the app turns it into events
 * and turns commands into Dreo control params (see apps/dreo-connect.groovy).
 * Fans with a light ring (e.g. DR-HPF008S) get a "Dreo Light Ring" child.
 * Saved head positions are kept on this device (Dreo's cloud has no list of them).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

// Speed numbers for the setSpeedLevel dropdown, which can't vary per fan: Dreo fans have up to 12.
@Field static final List SPEED_LEVELS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"]
// Slots for saved head positions.
@Field static final List POSITION_SLOTS = ["1", "2", "3", "4", "5"]

metadata {
    definition(name: "Dreo Fan", namespace: "dreo", author: "Digital Homesteaders", importUrl: "") {
        capability "Actuator"
        capability "Switch"
        capability "FanControl"
        capability "TemperatureMeasurement"
        capability "Refresh"

        attribute "speedLevel", "number"
        attribute "maxSpeedLevel", "number"
        attribute "mode", "string"
        attribute "supportedModes", "JSON_OBJECT"
        attribute "oscillation", "string"
        attribute "supportedOscillation", "JSON_OBJECT"
        attribute "display", "string"
        attribute "supportedDisplay", "JSON_OBJECT"
        attribute "panelSound", "string"
        attribute "childLock", "string"
        attribute "headVertical", "number"
        attribute "headHorizontal", "number"
        attribute "oscillationLeft", "number"
        attribute "oscillationRight", "number"
        attribute "oscillationBottom", "number"
        attribute "oscillationTop", "number"
        attribute "savedPositions", "JSON_OBJECT"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "setSpeedLevel", [[name: "Speed (1 to 12)*", type: "ENUM", description: "The speed number as in the Dreo app (maxSpeedLevel is this fan's top one); a number above it uses the top speed", constraints: SPEED_LEVELS]]
        command "setMode", [[name: "Mode*", type: "ENUM", description: "Fan mode to set; this fan's modes are in supportedModes", constraints: ["normal", "natural", "sleep", "auto", "turbo", "custom"]]]
        command "setOscillation", [[name: "Oscillation*", type: "ENUM", description: "Oscillation to set; this fan's choices are in supportedOscillation", constraints: ["off", "on", "horizontal", "vertical", "both"]]]
        command "setDisplay", [[name: "Display*", type: "ENUM", description: "Display on, off or automatic; this fan's choices are in supportedDisplay", constraints: ["on", "off", "auto"]]]
        command "setPanelSound", [[name: "Panel sound*", type: "ENUM", description: "Beep when buttons are pressed (fans with a panelSound attribute)", constraints: ["on", "off"]]]
        command "setChildLock", [[name: "Child lock*", type: "ENUM", description: "Lock the fan's buttons (fans with a childLock attribute)", constraints: ["on", "off"]]]
        command "setHeadPosition", [
            [name: "Vertical angle", type: "NUMBER", description: "Degrees up (negative is down); empty keeps it"],
            [name: "Horizontal angle", type: "NUMBER", description: "Degrees right (negative is left); empty keeps it. Stops oscillation."]]
        command "setOscillationRange", [
            [name: "Left", type: "NUMBER", description: "Left limit in degrees (negative); empty keeps it"],
            [name: "Right", type: "NUMBER", description: "Right limit in degrees; empty keeps it"],
            [name: "Bottom", type: "NUMBER", description: "Bottom limit in degrees; empty keeps it"],
            [name: "Top", type: "NUMBER", description: "Top limit in degrees; empty keeps it"]]
        command "savePosition", [
            [name: "Slot*", type: "ENUM", description: "Keep the head's current position in this slot", constraints: POSITION_SLOTS],
            [name: "Name", type: "STRING", description: "Optional, e.g. Desk"]]
        command "goToPosition", [[name: "Slot*", type: "ENUM", description: "Point the head at a saved position (see savedPositions)", constraints: POSITION_SLOTS]]
        command "deletePosition", [[name: "Slot*", type: "ENUM", description: "Forget a saved position", constraints: POSITION_SLOTS]]
    }
    preferences {
        input "speedSteps", "number", title: "Number of speed steps (0 = from the model)", defaultValue: 0, range: "0..20"
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        input "logEnable", "bool", title: "Debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    refresh()
}

def updated() {
    // A changed speed-step override re-maps the named speeds.
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
def setOscillation(value) { sendDreo("setOscillation", [value]) }
def setDisplay(value) { sendDreo("setDisplay", [value]) }
def setPanelSound(value) { sendDreo("setPanelSound", [value]) }
def setChildLock(value) { sendDreo("setChildLock", [value]) }
def setHeadPosition(vertical = null, horizontal = null) { sendDreo("setHeadPosition", [vertical, horizontal]) }
def setOscillationRange(left = null, right = null, bottom = null, top = null) {
    sendDreo("setOscillationRange", [left, right, bottom, top])
}

// ---------------------------------------------------------------- saved head positions

def savePosition(slot, name = null) {
    def v = device.currentValue("headVertical")
    def h = device.currentValue("headHorizontal")
    if (v == null || h == null) {
        log.warn "${device.displayName}: no head position reported, so nothing to save"
        return
    }
    Map saved = state.positions ?: [:]
    saved[slot.toString()] = [name: name ?: "Position ${slot}".toString(), vertical: v as int, horizontal: h as int]
    state.positions = saved
    showPositions()
}

/** A slot number or a saved position's name (from a rule). */
def goToPosition(slot) {
    Map saved = state.positions ?: [:]
    Map pos = saved[slot?.toString()] ?: saved.values().find { it.name?.equalsIgnoreCase(slot?.toString()) }
    if (!pos) {
        log.warn "${device.displayName}: no saved position ${slot}"
        return
    }
    sendDreo("setHeadPosition", [pos.vertical, pos.horizontal])
}

def deletePosition(slot) {
    Map saved = state.positions ?: [:]
    saved.remove(slot?.toString())
    state.positions = saved
    showPositions()
}

private void showPositions() {
    sendEvent(name: "savedPositions", value: JsonOutput.toJson((state.positions ?: [:]).sort { it.key }))
}

// Light ring child (component) commands.
def componentOn(cd) { sendDreo("ring.on") }
def componentOff(cd) { sendDreo("ring.off") }
def componentSetLevel(cd, level, duration = null) { sendDreo("ring.setLevel", [level]) }
def componentSetColor(cd, Map color) { sendDreo("ring.setColor", [color]) }
def componentSetHue(cd, hue) { sendDreo("ring.setHue", [hue]) }
def componentSetSaturation(cd, saturation) { sendDreo("ring.setSaturation", [saturation]) }
def componentSetEffect(cd, effect) { sendDreo("ring.setEffect", [effect]) }
def componentSetNextEffect(cd) { sendDreo("ring.setNextEffect") }
def componentSetPreviousEffect(cd) { sendDreo("ring.setPreviousEffect") }
def componentRefresh(cd) { refresh() }

private void sendDreo(String cmd, List args = []) {
    Map params = parent?.dreoCommand(device, state.dreo ?: [:], cmd, args, opts())
    // Show the change straight away; the fan's own report follows.
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
    String dni = "${device.deviceNetworkId}-${kind}"
    def cd = getChildDevice(dni)
    if (!cd) {
        try {
            cd = addChildDevice("dreo", "Dreo Light Ring", dni, [name: "Dreo Light Ring", label: "${device.displayName} Light Ring", isComponent: true])
        } catch (ex) {
            log.error "Could not add the light ring device: ${ex.message} (is the 'Dreo Light Ring' driver installed?)"
            return
        }
    }
    Map evt = e.findAll { k, v -> k != "child" }
    cd.parse([evt])
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}
