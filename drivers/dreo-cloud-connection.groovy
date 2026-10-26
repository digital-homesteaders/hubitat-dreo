/**
 * Dreo Cloud Connection: the WebSocket to Dreo's cloud, created by the
 * Dreo Connect app (Hubitat lets only drivers open WebSockets).
 *
 * One socket carries pushed state reports for every fan on the account and
 * the outgoing control commands. Commands go one at a time: each waits up to
 * 2 s for Dreo's acknowledgement and is retried up to twice. Commands for a
 * device that are still waiting merge into one (the newest value of each key).
 *
 * Protocol ported from hass-dreo (https://github.com/JeffSteinbok/hass-dreo,
 * MIT License; see NOTICE).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

// Release version; the app compares it with its own (see dreoHello).
@Field static final String DRIVER_VERSION = "0.3.0"

metadata {
    definition(name: "Dreo Cloud Connection", namespace: "dreo", author: "Digital Homesteaders", importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-dreo/main/drivers/dreo-cloud-connection.groovy") {
        capability "Initialize"
        attribute "connection", "enum", ["connecting", "connected", "disconnected"]
        command "reconnect"
        command "disconnect"
    }
    preferences {
        input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
    }
}

@Field static final int KEEPALIVE_SECONDS = 15
// A connect that neither opens nor fails in this long is treated as failed.
@Field static final int CONNECT_TIMEOUT_S = 30
// A close notice this soon after a connect is the previous socket's (closed by
// connect()), even if the new socket has opened meanwhile.
@Field static final long OLD_CLOSE_GRACE_MS = 3000
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
        // phase: null (unknown: new code or a restart), "connecting", "open" or
        // "closed". Every change to it happens under synchronized (m).
        m = [queue: [], inflight: null, phase: null, nextUrl: null]
        MEM.putIfAbsent(id, m)
        m = MEM.get(id)
    }
    return m
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    sendEvent(name: "connection", value: "disconnected")
}

def updated() {
    if (settings.logEnable) runIn(1800, "logsOff")
}

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
    unschedule("connectTimeout")
    // ackTimeout stays scheduled: it drops (and reports) a command still in
    // flight, which would otherwise block the queue after the reconnect.
    Map m = mem()
    synchronized (m) {
        m.phase = "closed"   // so the close notice that follows is not taken for a drop
        m.nextUrl = null
    }
    try { interfaces.webSocket.close() } catch (e) { /* not open */ }
    sendEvent(name: "connection", value: "disconnected")
}

/** Called by the app with a fresh URL (it carries the access token). */
void connect(String url) {
    Map m = mem()
    synchronized (m) {
        if (m.phase == "open") return
        if (m.phase == "connecting" && now() - ((m.connectAt ?: 0L) as long) < CONNECT_TIMEOUT_S * 1000L) {
            // An attempt is in progress: don't start a second socket on top of
            // it. Should it fail, this newer URL (e.g. a fresh sign-in) is tried next.
            m.nextUrl = url
            return
        }
        m.phase = "connecting"
        m.connectAt = now()
        m.nextUrl = null
    }
    state.wanted = true
    sendEvent(name: "connection", value: "connecting")
    logDebug "Connecting"
    // New code or a hub restart empties MEM but can leave the old socket open,
    // holding its buffers: close it first. Its "status: closing" notice is
    // ignored while connecting (see webSocketStatus).
    try { interfaces.webSocket.close() } catch (e) { /* not open */ }
    runIn(CONNECT_TIMEOUT_S, "connectTimeout")
    try {
        interfaces.webSocket.connect(url, pingInterval: KEEPALIVE_SECONDS, headers: ["User-Agent": "okhttp/4.9.1"])
    } catch (e) {
        socketDown("connect failed: ${e.message}")
    }
}

/** The connect neither opened nor failed in time. */
def connectTimeout() {
    if (mem().phase == "connecting") socketDown("no answer from Dreo in ${CONNECT_TIMEOUT_S} s")
}

def webSocketStatus(String status) {
    logDebug "Status: ${status}"
    Map m = mem()
    if (status.startsWith("status: open")) {
        String was
        synchronized (m) {
            was = m.phase
            if (was == "connecting" || was == null) m.phase = "open"
        }
        if (was == "open") return
        if (was == "closed") {
            // Given up meanwhile (disconnect, reconnect or the connect timeout): not wanted.
            logDebug "Closing a socket that opened after it was given up"
            try { interfaces.webSocket.close() } catch (e) { /* already closed */ }
            return
        }
        opened(was == null ? "Took over the open socket after new code" : null)
    } else if (status.startsWith("status: closing") && oldSocketsClose(m)) {
        logDebug "Ignored a close notice: it is the previous socket's"
    } else if (status.startsWith("failure:") || status.startsWith("status: closing")) {
        socketDown(status)
    }
}

/**
 * Whether a close notice now is the previous socket's, closed by connect():
 * while the new one is connecting, or just after it opened (Hubitat may
 * deliver the old notice late).
 */
private boolean oldSocketsClose(Map m) {
    synchronized (m) {
        if (m.phase == "connecting") return true
        return m.phase == "open" && now() - ((m.connectAt ?: 0L) as long) < OLD_CLOSE_GRACE_MS
    }
}

/** The socket is open (phase already set): resume normal service and tell the app. */
private void opened(String note) {
    unschedule("connectTimeout")
    state.failures = 0
    sendEvent(name: "connection", value: "connected")
    if (note) logDebug note else log.info "Dreo cloud connected"
    keepAlive()
    parent?.wsOpened()
    sendNext()
}

/** The socket dropped or failed to open: tell the app, which schedules a reconnect. */
private void socketDown(String reason) {
    Map m = mem()
    String nextUrl
    synchronized (m) {
        // A failure is often followed by a "closing" status: handle only the first.
        if (m.phase == "closed") return
        m.phase = "closed"
        nextUrl = m.nextUrl
        m.nextUrl = null
    }
    unschedule("keepAlive")
    unschedule("connectTimeout")
    // Release the failed socket's resources; its own close notice then finds nothing to do.
    try { interfaces.webSocket.close() } catch (e) { /* already closed */ }
    sendEvent(name: "connection", value: "disconnected")
    if (state.wanted == false) return
    if (nextUrl) {
        // A newer URL arrived during the failed attempt: no need to wait for the app's retry.
        logDebug "Connect failed (${reason}); trying the newer URL"
        connect(nextUrl)
        return
    }
    state.failures = (state.failures ?: 0) + 1
    parent?.wsClosed(reason, state.failures as int)
}

/** Dreo ignores the "2" text ping but the Dreo app sends it; protocol pings come from pingInterval. */
def keepAlive() {
    if (mem().phase != "open") return
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
    // Anything from Dreo shows the link is alive (see ackTimeout).
    Map m = mem()
    m.lastRx = now()
    boolean tookOver
    synchronized (m) {
        // New driver code emptied MEM while the socket stayed up: carry on with it.
        tookOver = m.phase == null
        if (tookOver) m.phase = "open"
    }
    if (tookOver) opened("Took over the open socket after new code")
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

    boolean rejected = reported?.containsKey("error_code")
    if (method in ACK_METHODS) acknowledge(sn, reported)
    // control-reply echoes the command, and a rejection carries only the error:
    // neither is device state.
    parent?.dreoMessage(sn, method, method == "control-reply" || rejected ? null : reported)
}

/**
 * Whether a message acknowledges the in-flight command: same fan, and it
 * mentions at least one of the keys sent (or is an error, or carries no
 * keys). A report about other keys, such as a button press, is not an ack.
 */
private boolean acks(Map item, String sn, Map reported) {
    if (item?.sn != sn) return false
    if (!reported || reported.containsKey("error_code")) return true
    return item.params.keySet().any { reported.containsKey(it) }
}

private void acknowledge(String sn, Map reported) {
    Map m = mem()
    Map done
    synchronized (m) {
        if (!acks(m.inflight, sn, reported)) return
        done = m.inflight
        m.inflight = null
    }
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
        // A command still waiting for this device takes the new keys instead of
        // queueing another (e.g. a rule or slider sending several in a row).
        Map waiting = m.queue.find { it.sn == sn }
        if (waiting) {
            waiting.params = waiting.params + params
            logDebug "Merged ${params} into the waiting command for ${sn}"
        } else {
            m.queue << [sn: sn, params: params, queuedAt: now(), attempts: 0]
        }
    }
    if (m.phase == "open") {
        sendNext()
        return
    }
    // Commands that wait too long for the socket are dropped and reported
    // even if it never reopens.
    runIn((int) (COMMAND_MAX_AGE_MS / 1000) + 1, "expireQueue")
    // Ask the app for a socket unless one is on its way. After new code the
    // phase is unknown: connect() then replaces whatever socket is there.
    if (m.phase != "connecting") parent?.connectSocket()
}

/** Drop queued commands older than COMMAND_MAX_AGE_MS and tell the app, so the fan is re-read. */
def expireQueue() {
    Map m = mem()
    List stale = []
    synchronized (m) {
        while (m.queue && now() - (m.queue[0].queuedAt as long) > COMMAND_MAX_AGE_MS) stale << m.queue.remove(0)
        if (m.queue) runIn((int) (COMMAND_MAX_AGE_MS / 1000) + 1, "expireQueue")
    }
    stale.each { Map item ->
        log.warn "Dreo: dropped command ${item.params} for ${item.sn}: not connected in time"
        parent?.commandFailed(item.sn)
    }
}

private void sendNext() {
    expireQueue()
    Map m = mem()
    Map item
    synchronized (m) {
        if (m.inflight || m.phase != "open" || !m.queue) return
        item = m.queue.remove(0)
        m.inflight = item
        stamp(m, item)
    }
    transmit(m, item)
}

/** Count an attempt; call while holding the lock. */
private void stamp(Map m, Map item) {
    item.attempts = (item.attempts as int) + 1
    item.sentAt = now()
    if (!item.firstSentAt) item.firstSentAt = item.sentAt
}

private void transmit(Map m, Map item) {
    String payload = JsonOutput.toJson([devicesn: item.sn, method: "control", params: item.params, timestamp: now().toString()])
    synchronized (m) {
        // An ack may have arrived meanwhile: then there is nothing to (re)send.
        if (!m.inflight.is(item)) return
    }
    logDebug "Send ${payload}"
    try {
        interfaces.webSocket.sendMessage(payload)
    } catch (e) {
        log.warn "Dreo: send failed: ${e.message}"
    }
    // One timer serves whatever is in flight when it fires (see ackTimeout),
    // so a later schedule replacing this one never leaves a command unwatched.
    runInMillis(ACK_TIMEOUT_MS, "ackTimeout")
}

def ackTimeout(Map data = null) {
    Map m = mem()
    Map item
    boolean retry = false
    long wait = 0
    synchronized (m) {
        item = m.inflight
        if (!item) return
        wait = (item.sentAt as long) + ACK_TIMEOUT_MS - now()
        if (wait <= 0) {
            if ((item.attempts as int) <= MAX_RETRIES && m.phase == "open") {
                retry = true
                stamp(m, item)
            } else {
                m.inflight = null
            }
        }
    }
    if (wait > 0) {
        runInMillis(wait, "ackTimeout")
        return
    }
    if (retry) {
        logDebug "No acknowledgement for ${item.sn} (attempt ${item.attempts - 1}), retrying"
        transmit(m, item)
        return
    }
    log.warn "Dreo did not acknowledge command ${item.params} for ${item.sn}"
    parent?.commandFailed(item.sn)
    // No ack and nothing else from Dreo since the command went out usually
    // means a dead link the hub hasn't noticed yet. If other messages arrived,
    // the link works and only this device didn't answer: don't reconnect.
    if (m.phase == "open" && ((m.lastRx ?: 0L) as long) < (item.firstSentAt as long)) {
        closeSocket()
        state.failures = 0
        parent?.wsClosed("no acknowledgement", 1)
    }
    sendNext()
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "Dreo connection: ${msg}"
}

/** Debug logging switches itself off 30 minutes after it's turned on. */
void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/** Called by the app (or the parent fan) on Done: this driver's version where it differs from the app's. */
List dreoHello(String appVersion) {
    device.updateDataValue("driverVersion", DRIVER_VERSION)
    return DRIVER_VERSION == appVersion ? [] : ["${device.displayName} (${DRIVER_VERSION})".toString()]
}
