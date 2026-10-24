/**
 * Dreo Ceiling Fan Light: the main light of a Dreo ceiling fan, as a child of the
 * "Dreo Ceiling Fan" device. Commands go to the parent, which sends them to
 * Dreo; the parent sends state back through parse().
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.transform.Field

// Release version; the app compares it with its own (see dreoHello).
@Field static final String DRIVER_VERSION = "0.3.0"

metadata {
    definition(name: "Dreo Ceiling Fan Light", namespace: "dreo", author: "Digital Homesteaders", importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-dreo/main/drivers/dreo-ceiling-fan-light.groovy") {
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

/** Called by the app (or the parent fan) on Done: this driver's version where it differs from the app's. */
List dreoHello(String appVersion) {
    device.updateDataValue("driverVersion", DRIVER_VERSION)
    return DRIVER_VERSION == appVersion ? [] : ["${device.displayName} (${DRIVER_VERSION})".toString()]
}
