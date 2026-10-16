/**
 * Dreo Cloud Connection: the WebSocket to Dreo's cloud, created by the
 * Dreo Connect app (Hubitat lets only drivers open WebSockets).
 *
 * One socket carries pushed state reports for every fan on the account and
 * the outgoing control commands. Commands go one at a time: each waits up to
 * 2 s for Dreo's acknowledgement and is retried up to twice.
 *
 * Protocol ported from hass-dreo (https://github.com/JeffSteinbok/hass-dreo,
 * MIT License; see LICENSE).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition(name: "Dreo Cloud Connection", namespace: "dreo", author: "Digital Homesteaders", importUrl: "") {
        capability "Initialize"
        attribute "connection", "enum", ["connecting", "connected", "disconnected"]
        command "reconnect"
        command "disconnect"
    }
    preferences {
        input "logEnable", "bool", title: "Debug logging", defaultValue: false
    }
}

@Field static final int KEEPALIVE_SECONDS = 15
@Field static final long ACK_TIMEOUT_MS = 2000
@Field static final int MAX_RETRIES = 2
// Commands still queued after this long (e.g. while reconnecting) are dropped.
@Field static final long COMMAND_MAX_AGE_MS = 30000
// Only these methods acknowledge a command.
@Field static final List ACK_METHODS = ["control-reply", "control-report"]

// In-memory command queue per connection device. It is lost on reboot or
// code save, which is fine: queued commands are seconds old at most.
@Field static final ConcurrentHashMap<Long, Map> MEM = new ConcurrentHashMap<>()

private Map mem() {
    Long id = device.idAsLong
    Map m = MEM.get(id)
    if (m == null) {
        m = [queue: [], inflight: null, seq: 0, open: false]
        MEM.putIfAbsent(id, m)
        m = MEM.get(id)
    }
    return m
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    sendEvent(name: "connection", value: "disconnected")
}

def updated() {}

/** Runs at hub start-up (Initialize capability): ask the app to connect. */
def initialize() {
    state.failures = 0
    parent?.connectSocket()
}

def reconnect() {
    closeSocket()
    state.failures = 0
    parent?.connectSocket()
}

def disconnect() {
    state.wanted = false
    closeSocket()
    synchronized (mem()) { mem().queue.clear(); mem().inflight = null }
}

private void closeSocket() {
    unschedule("keepAlive")
    unschedule("ackTimeout")
    mem().open = false
    try { interfaces.webSocket.close() } catch (e) { /* not open */ }
    sendEvent(name: "connection", value: "disconnected")
}

/** Called by the app with a fresh URL (it carries the access token). */
void connect(String url) {
    if (device.currentValue("connection") == "connected" && mem().open) return
    state.wanted = true
    mem().open = false
    sendEvent(name: "connection", value: "connecting")
    logDebug "Connecting"
    try {
        interfaces.webSocket.connect(url, pingInterval: KEEPALIVE_SECONDS, headers: ["User-Agent": "okhttp/4.9.1"])
    } catch (e) {
        socketDown("connect failed: ${e.message}")
    }
}

def webSocketStatus(String status) {
    logDebug "Status: ${status}"
    if (status.startsWith("status: open")) {
        mem().open = true
        state.failures = 0
        sendEvent(name: "connection", value: "connected")
        log.info "Dreo cloud connected"
        keepAlive()
        parent?.wsOpened()
        sendNext()
    } else if (status.startsWith("failure:") || status.startsWith("status: closing")) {
        socketDown(status)
    }
}

/** The socket dropped or failed to open: tell the app, which schedules a reconnect. */
private void socketDown(String reason) {
    // A failure is often followed by a "closing" status: handle only the first.
    if (device.currentValue("connection") == "disconnected" && !mem().open) return
    mem().open = false
    unschedule("keepAlive")
    sendEvent(name: "connection", value: "disconnected")
    if (state.wanted == false) return
    state.failures = (state.failures ?: 0) + 1
    parent?.wsClosed(reason, state.failures as int)
}

/** Dreo ignores the "2" text ping but the Dreo app sends it; protocol pings come from pingInterval. */
def keepAlive() {
    if (!mem().open) return
    try {
        interfaces.webSocket.sendMessage("2")
    } catch (e) {
        socketDown("keepalive failed: ${e.message}")
        return
    }
    runIn(KEEPALIVE_SECONDS, "keepAlive")
}

// ---------------------------------------------------------------- incoming

def parse(String text) {
    Map msg
    try {
        def j = new JsonSlurper().parseText(text)
        msg = j instanceof Map ? j : null
    } catch (e) {
        msg = null // non-JSON keepalive
    }
    if (!msg?.devicesn) return
    String sn = msg.devicesn
    String method = msg.method
    Map reported = msg.reported instanceof Map ? msg.reported : null
    logDebug "${method} ${sn} ${reported}"

    if (method in ACK_METHODS) acknowledge(sn, reported)
    parent?.dreoMessage(sn, method, method == "control-reply" ? null : reported)
}

private void acknowledge(String sn, Map reported) {
    Map m = mem()
    Map done
    synchronized (m) {
        if (m.inflight?.sn != sn) return
        done = m.inflight
        m.inflight = null
    }
    unschedule("ackTimeout")
    if (reported?.containsKey("error_code")) {
        log.warn "Dreo rejected command ${done.params} for ${sn}: ${reported.error_msg} (error ${reported.error_code})"
        parent?.commandFailed(sn)
    }
    sendNext()
}

// ---------------------------------------------------------------- outgoing

/** Queue a control command for one fan. */
void sendCommand(String sn, Map params) {
    Map m = mem()
    synchronized (m) {
        m.queue << [sn: sn, params: params, queuedAt: now(), attempts: 0]
    }
    if (m.open) {
        sendNext()
    } else if (device.currentValue("connection") != "connecting") {
        parent?.connectSocket()
    }
}

private void sendNext() {
    Map m = mem()
    Map item
    synchronized (m) {
        if (m.inflight || !m.open) return
        while (m.queue && now() - (m.queue[0].queuedAt as long) > COMMAND_MAX_AGE_MS) {
            Map stale = m.queue.remove(0)
            log.warn "Dreo: dropped command ${stale.params} for ${stale.sn}: not connected in time"
        }
        if (!m.queue) return
        item = m.queue.remove(0)
        m.inflight = item
    }
    transmit(item)
}

private void transmit(Map item) {
    Map m = mem()
    item.attempts = (item.attempts as int) + 1
    item.id = ++m.seq
    String payload = JsonOutput.toJson([devicesn: item.sn, method: "control", params: item.params, timestamp: now().toString()])
    logDebug "Send ${payload}"
    try {
        interfaces.webSocket.sendMessage(payload)
    } catch (e) {
        log.warn "Dreo: send failed: ${e.message}"
    }
    runInMillis(ACK_TIMEOUT_MS, "ackTimeout", [data: [id: item.id]])
}

def ackTimeout(Map data) {
    Map m = mem()
    Map item
    boolean retry = false
    synchronized (m) {
        item = m.inflight
        if (!item || item.id != data?.id) return
        if ((item.attempts as int) <= MAX_RETRIES && m.open) {
            retry = true
        } else {
            m.inflight = null
        }
    }
    if (retry) {
        logDebug "No acknowledgement for ${item.sn} (attempt ${item.attempts}), retrying"
        transmit(item)
        return
    }
    log.warn "Dreo did not acknowledge command ${item.params} for ${item.sn}"
    parent?.commandFailed(item.sn)
    // No ack at all usually means a dead link the hub hasn't noticed yet.
    if (m.open) {
        closeSocket()
        state.failures = 0
        parent?.wsClosed("no acknowledgement", 1)
    }
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "Dreo connection: ${msg}"
}
