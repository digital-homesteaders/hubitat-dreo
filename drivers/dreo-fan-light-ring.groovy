/**
 * Dreo Fan Light Ring: the ambient light ring of a Dreo fan (e.g. DR-HPF008S, or
 * a ceiling fan's ring), as a child of the fan device. Commands go to the
 * parent, which sends them to Dreo; the parent sends state back through parse().
 *
 * Effects: 1 constant, 2 circle, 3 breath; effect speed low, medium or high (atmspeed 1-3).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.transform.Field

// Release version; the app compares it with its own (see dreoHello).
@Field static final String DRIVER_VERSION = "0.3.0"

metadata {
    definition(name: "Dreo Fan Light Ring", namespace: "dreo", author: "Digital Homesteaders", importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-dreo/main/drivers/dreo-fan-light-ring.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "LightEffects"
        capability "Refresh"

        attribute "effectSpeed", "enum", ["low", "medium", "high"]

        command "setEffectSpeed", [[name: "Speed*", type: "ENUM", description: "How fast the circle and breath effects run", constraints: ["low", "medium", "high"]]]
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
        if (txtEnable && !(e.name in ["hue", "saturation", "lightEffects", "RGB", "colorMode"])) {
            e.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}"
            log.info e.descriptionText
        }
        sendEvent(e)
    }
}

def on() { parent?.componentOn(device) }
def off() { parent?.componentOff(device) }
def setLevel(level, duration = null) { parent?.componentSetLevel(device, level, duration) }
def setColor(Map color) { parent?.componentSetColor(device, color) }
def setHue(hue) { parent?.componentSetHue(device, hue) }
def setSaturation(saturation) { parent?.componentSetSaturation(device, saturation) }
def setEffect(effect) { parent?.componentSetEffect(device, effect) }
def setNextEffect() { parent?.componentSetNextEffect(device) }
def setPreviousEffect() { parent?.componentSetPreviousEffect(device) }
def setEffectSpeed(speed) { parent?.componentSetEffectSpeed(device, speed) }
def refresh() { parent?.componentRefresh(device) }

/** Called by the app (or the parent fan) on Done: this driver's version where it differs from the app's. */
List dreoHello(String appVersion) {
    device.updateDataValue("driverVersion", DRIVER_VERSION)
    return DRIVER_VERSION == appVersion ? [] : ["${device.displayName} (${DRIVER_VERSION})".toString()]
}
