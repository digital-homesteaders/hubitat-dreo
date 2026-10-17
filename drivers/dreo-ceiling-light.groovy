/**
 * Dreo Ceiling Light: the main light of a Dreo ceiling fan, as a child of the
 * "Dreo Ceiling Fan" device. Commands go to the parent, which sends them to
 * Dreo; the parent sends state back through parse().
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

metadata {
    definition(name: "Dreo Ceiling Light", namespace: "dreo", author: "Digital Homesteaders", importUrl: "") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorTemperature"
        capability "Refresh"
    }
    preferences {
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
    }
}

def installed() {}
def updated() {}

/** Events from the parent fan. */
void parse(List<Map> events) {
    events.each { Map e ->
        // Most of each report is unchanged: skip those values rather than send them.
        if (device.currentValue(e.name)?.toString() == e.value?.toString()) return
        if (txtEnable) {
            e.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}"
            log.info e.descriptionText
        }
        sendEvent(e)
    }
}

def on() { parent?.componentOn(device) }
def off() { parent?.componentOff(device) }
def setLevel(level, duration = null) { parent?.componentSetLevel(device, level, duration) }
/** Kelvin, 2700 (warm) to 6500 (cool). */
def setColorTemperature(ct, level = null, transition = null) { parent?.componentSetColorTemperature(device, ct, level, transition) }
def refresh() { parent?.componentRefresh(device) }
