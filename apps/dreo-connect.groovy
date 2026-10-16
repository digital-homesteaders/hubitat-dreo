/**
 * Dreo Connect: Hubitat integration for Dreo cloud fans.
 *
 * The app holds the Dreo account, signs in, discovers fans and creates one
 * child device per fan. A "Dreo Cloud Connection" child device holds the
 * WebSocket (only drivers can open one), which carries pushed state reports
 * and outgoing commands for every fan on the account.
 *
 * All Dreo-specific mapping (speed steps, modes, oscillation, the ceiling-fan
 * power gate, light colours, temperature units) lives in this file as plain
 * functions of the fan's reported state, so the fan and light drivers stay thin.
 *
 * Protocol, model data and feature mappings are ported from hass-dreo
 * (https://github.com/JeffSteinbok/hass-dreo, MIT License; see LICENSE).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.security.MessageDigest

definition(
    name: "Dreo Connect",
    namespace: "dreo",
    author: "Digital Homesteaders",
    description: "Control Dreo cloud fans (tower, circulator, ceiling) from Hubitat.",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

preferences {
    page(name: "mainPage")
    page(name: "fansPage")
}

// ---------------------------------------------------------------- constants

@Field static final String CLIENT_ID = "7de37c362ee54dcf9c4561812309347a"
@Field static final String CLIENT_SECRET = "32dfa0764f25451d99f94e1693498791"
@Field static final String HIMEI = "faede31549d649f58864093158787ec9"
@Field static final Map REGION_HOST = [NA: "us", EU: "eu"]

// After Dreo rejects the credentials, wait this long before trying them again.
@Field static final long AUTH_RETRY_MS = 30 * 60 * 1000L
// A discovery still running after this long has failed (e.g. sign-in failed).
@Field static final long DISCOVERY_TIMEOUT_MS = 45 * 1000L

@Field static final String PATH_LOGIN = "/api/oauth/login"
@Field static final String PATH_DEVICES = "/api/v2/user-device/device/list"
@Field static final String PATH_ROOMS = "/api/app/index/family/room/devices"
@Field static final String PATH_STATE = "/api/user-device/device/state"

// Mode lists by name: [id, value of the fan's `mode`/`windtype` key].
// (FAMILIES and MODELS name these lists: a @Field initializer can't refer to
// another @Field in the Groovy that Hubitat runs.)
@Field static final Map MODE_LISTS = [
    basic: [["normal", 1], ["natural", 2], ["sleep", 3], ["auto", 4]],
    turbo: [["normal", 1], ["natural", 2], ["sleep", 3], ["auto", 4], ["turbo", 5]],
    custom: [["normal", 1], ["natural", 2], ["sleep", 3], ["auto", 4], ["turbo", 5], ["custom", 6]],
    // DR-HPF017S and DR-HPF025S swap natural and auto (per hass-dreo; unverified).
    swapped: [["normal", 1], ["auto", 2], ["sleep", 3], ["natural", 4], ["turbo", 5]],
    swappedCustom: [["normal", 1], ["auto", 2], ["sleep", 3], ["natural", 4], ["turbo", 5], ["custom", 6]],
    ceiling: [["normal", 1], ["natural", 2], ["sleep", 3], ["reverse", 4]],
]

// Family defaults by model prefix. Unknown fans in a family use these.
@Field static final Map FAMILIES = [
    "DR-HTF": [kind: "tower", speed: [1, 9], modes: "basic"],
    "DR-HAF": [kind: "circulator", speed: [1, 9], modes: "basic"],
    "DR-HPF": [kind: "circulator", speed: [1, 9], modes: "basic"],
    "DR-HCF": [kind: "ceiling", speed: [1, 12], modes: "ceiling"],
]

// Per-model details, from hass-dreo's pydreo/models.py. `ring` is the light
// ring's brightness range (atmbri) where it differs from 1-5.
@Field static final Map MODELS = [
    "DR-HTF007S": [speed: [1, 8]],
    "DR-HTF011S": [speed: [1, 9]],
    "DR-HTF017S": [speed: [1, 4]],
    "DR-HTF018S": [speed: [1, 9]],
    "DR-HTF021S": [speed: [1, 12]],
    "DR-HTF021AS": [speed: [1, 12]],
    "DR-HTF024S": [speed: [1, 9]],
    "DR-HTF518S": [speed: [1, 9]],
    "DR-HAF001S": [speed: [1, 4]],
    "DR-HAF008S": [speed: [1, 9], modes: "turbo"],
    "DR-HPF004S": [speed: [1, 9], modes: "custom"],
    "DR-HPF005S": [speed: [1, 10], modes: "custom"],
    "DR-HPF007S": [speed: [1, 10], modes: "custom"],
    "DR-HPF008S": [speed: [1, 9], modes: "turbo", ring: [1, 3]],
    "DR-HPF015S": [speed: [1, 12], modes: "custom"],
    "DR-HPF017S": [speed: [1, 12], modes: "swappedCustom"],
    "DR-HPF020S": [speed: [1, 9], modes: "custom"],
    "DR-HPF022S": [speed: [1, 9], modes: "turbo"],
    "DR-HPF025S": [speed: [1, 9], modes: "swapped"],
    "DR-HCF001S": [speed: [1, 12]],
    "DR-HCF002S": [speed: [1, 12]],
    "DR-HCF007S": [speed: [1, 12], ring: [1, 100]],
    "DR-HCF008S": [speed: [1, 12], ring: [1, 100]],
    "DR-HCF010S": [speed: [1, 12], ring: [1, 100]],
    "DR-HCF521S": [speed: [1, 12]],
]

// Hubitat's named fan speeds, slowest first, and the subsets used when a fan
// has fewer than five steps.
@Field static final List SPEED_NAMES = ["low", "medium-low", "medium", "medium-high", "high"]
@Field static final Map FEW_SPEEDS = [
    1: ["high"],
    2: ["low", "high"],
    3: ["low", "medium", "high"],
    4: ["low", "medium-low", "medium-high", "high"],
]

// Single-axis oscillation keys, in the order hass-dreo checks them.
@Field static final List OSC_KEYS = ["shakehorizon", "oscon", "hoscon"]
@Field static final List OSC_MODES = ["off", "horizontal", "vertical", "both"]
// Display on/off keys on tower and circulator fans (ceiling fans use lighton for the light).
@Field static final List DISPLAY_KEYS = ["ledkepton", "ledalwayson", "lighton"]
@Field static final List DISPLAY_MODES = ["off", "on", "auto"]
// Loads behind a ceiling fan's poweron gate.
@Field static final List CEILING_LOADS = ["fanon", "lighton", "atmon"]
@Field static final Map RING_EFFECTS = [1: "constant", 2: "circle", 3: "breath"]
// Ceiling light colortemp 0..100 spans 2700 K (warm) to 6500 K (cool).
@Field static final int CT_WARM = 2700
@Field static final int CT_COOL = 6500

// ---------------------------------------------------------------- pages

def mainPage() {
    hashPasswordSetting()
    dynamicPage(name: "mainPage", title: "Dreo Connect", install: true, uninstall: true) {
        section("Dreo account") {
            input "email", "email", title: "Email", required: true, submitOnChange: true
            input "password", "password", title: state.passwordHash ? "Password (saved; enter again to change)" : "Password",
                required: false, submitOnChange: true
            paragraph "Only an MD5 hash of the password is kept, which is what Dreo's sign-in expects."
            if (state.status) paragraph "<b>Status:</b> ${state.status}"
        }
        if (settings.email && state.passwordHash) {
            section("Fans") {
                href "fansPage", title: "Choose fans", description: selectedSummary()
                getFanDevices().each { cd ->
                    paragraph "${cd.displayName}: ${cd.currentValue('switch') ?: '?'}, ${cd.currentValue('speed') ?: '?'} " +
                        "(${cd.getDataValue('model')}, ${cd.currentValue('healthStatus') ?: 'unknown'})"
                }
                def conn = getConnection()
                if (conn) paragraph "Cloud connection: ${conn.currentValue('connection') ?: 'unknown'}"
            }
        }
        section("Logging") {
            input "logEnable", "bool", title: "Debug logging", defaultValue: false
        }
    }
}

def fansPage() {
    hashPasswordSetting()
    Long started = state.discovering as Long
    if (started && now() - started > DISCOVERY_TIMEOUT_MS) {
        state.remove("discovering")
        started = null
        state.discoverError = state.status ?: "Dreo did not answer"
    }
    if (!started && (!state.discoveredAt || now() - (state.discoveredAt as Long) > 60000)) {
        startDiscovery()
        started = state.discovering as Long
    }
    Map page = [name: "fansPage", title: "Choose fans"]
    if (started) page.refreshInterval = 2
    dynamicPage(page) {
        section {
            if (started) {
                paragraph "Looking up fans on your Dreo account..."
            } else if (state.discoverError) {
                paragraph "Could not list fans: ${state.discoverError}"
            }
            Map found = state.discovered ?: [:]
            if (found) {
                Map options = found.collectEntries { sn, f ->
                    [(sn): "${f.name} (${f.series ?: f.model}${f.room ? ', ' + f.room : ''})"]
                }.sort { it.value }
                input "selectedFans", "enum", title: "Fans to add to Hubitat", options: options, multiple: true, required: false
                paragraph "Fans you untick are removed from Hubitat when you press Done."
            } else if (!started && !state.discoverError) {
                paragraph "No fans found on this Dreo account."
            }
            if (state.unsupported) paragraph "Not supported (not a fan): ${state.unsupported.join(', ')}"
        }
    }
}

private String selectedSummary() {
    int n = (settings.selectedFans ?: []).size()
    n ? "${n} fan${n == 1 ? '' : 's'} selected" : "Tap to pick fans"
}

/** Replace a freshly entered password with its MD5 hash. */
private void hashPasswordSetting() {
    if (settings.email && settings.email != state.emailUsed) {
        state.emailUsed = settings.email
        state.remove("token")
        state.remove("authFailedAt")
        state.remove("discoveredAt")
    }
    if (settings.password) {
        state.passwordHash = md5(settings.password as String)
        app.removeSetting("password")
        state.remove("token")
        state.remove("authFailedAt")
        state.remove("discoveredAt")
        state.status = "Password saved"
    }
}

private String md5(String s) {
    MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")).encodeHex().toString()
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    hashPasswordSetting()
    syncChildren()
    if (getFanDevices()) {
        runEvery30Minutes("pollAll")
        connectSocket()
    } else {
        getConnection()?.disconnect()
    }
}

private void syncChildren() {
    List selected = settings.selectedFans ?: []
    Map found = state.discovered ?: [:]
    selected.each { String sn ->
        if (getChildDevice(fanDni(sn))) return
        Map f = found[sn]
        if (!f) return
        String driver = kindOf(f.model) == "ceiling" ? "Dreo Ceiling Fan" : "Dreo Fan"
        try {
            // Data values go in with the device: its installed() runs inside addChildDevice.
            addChildDevice("dreo", driver, fanDni(sn), [name: f.series ?: f.model, label: f.name, isComponent: false,
                data: [sn: sn, model: f.model]])
            log.info "Dreo: added ${f.name} (${f.model})"
        } catch (e) {
            log.error "Dreo: could not add ${f.name}: ${e.message} (is the '${driver}' driver installed?)"
        }
    }
    getFanDevices().each { cd ->
        if (!(cd.getDataValue("sn") in selected)) {
            log.info "Dreo: removing ${cd.displayName}"
            deleteChildDevice(cd.deviceNetworkId)
        }
    }
    if (getFanDevices() && !getConnection()) {
        try {
            addChildDevice("dreo", "Dreo Cloud Connection", connDni(), [name: "Dreo Cloud Connection", isComponent: false])
        } catch (e) {
            log.error "Dreo: could not add the cloud connection device: ${e.message} (is the 'Dreo Cloud Connection' driver installed?)"
        }
    }
}

private String fanDni(String sn) { "dreo-${sn}" }
private String connDni() { "dreo-connection-${app.id}" }
private def getConnection() { getChildDevice(connDni()) }
private List getFanDevices() { getChildDevices().findAll { it.deviceNetworkId != connDni() } }
private def fanBySn(String sn) { getChildDevice(fanDni(sn)) }

// ---------------------------------------------------------------- REST

private String apiBase(String region = null) {
    "https://app-api-${REGION_HOST[region ?: state.region ?: 'NA'] ?: 'us'}.dreo-tech.com"
}

private Map apiHeaders() {
    Map h = [ua: "dreo/2.8.2", lang: "en", "User-Agent": "okhttp/4.9.1"]
    if (state.token) h.Authorization = "Bearer ${state.token}"
    return h
}

/** GET a Dreo API path; calls `callback(data, cbData)` with data = null on failure. */
private void apiGet(String path, Map query, String callback, Map cbData = [:], boolean retried = false) {
    Map req = [path: path, query: query ?: [:], cb: callback, data: cbData, retried: retried]
    if (!state.token) {
        login("apiReplay", req)
        return
    }
    req.tokenAt = state.tokenAt
    Map params = [
        uri: apiBase(), path: path, query: req.query + [timestamp: now()],
        headers: apiHeaders(), contentType: "application/json", timeout: 30,
    ]
    logDebug "GET ${path} ${req.query}"
    asynchttpGet("apiGetDone", params, req)
}

def apiReplay(Map req) {
    apiGet(req.path, req.query, req.cb, req.data ?: [:], true)
}

def apiGetDone(resp, Map req) {
    if (resp.status == 401 && !req.retried) {
        // Sign in again, unless another request already replaced the token.
        if (state.token && state.tokenAt != req.tokenAt) {
            apiReplay(req)
        } else {
            log.info "Dreo: ${req.path} returned 401, signing in again"
            state.remove("token")
            login("apiReplay", req)
        }
        return
    }
    Map json = parseJson(resp)
    if (resp.status != 200 || json?.code != 0) {
        log.warn "Dreo: ${req.path} failed: HTTP ${resp.status} code=${json?.code} msg=${json?.msg ?: errorText(resp)}"
        "${req.cb}"(null, req.data ?: [:])
        return
    }
    "${req.cb}"(json.data, req.data ?: [:])
}

private Map parseJson(resp) {
    try {
        def j = resp.getJson()
        return j instanceof Map ? j : null
    } catch (e) {
        return null
    }
}

private String errorText(resp) {
    try { return resp.hasError() ? resp.getErrorMessage() : null } catch (e) { return null }
}

/** Sign in, then call `then(thenData)`. */
private void login(String then, Map thenData, String region = null, int depth = 0) {
    if (!settings.email || !state.passwordHash) {
        state.status = "Enter your Dreo email and password"
        return
    }
    if (state.authFailedAt && now() - (state.authFailedAt as Long) < AUTH_RETRY_MS) {
        logDebug "Sign-in skipped: Dreo rejected the credentials recently"
        return
    }
    String r = region ?: state.region ?: "NA"
    Map body = [
        acceptLanguage: "en", client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
        email: settings.email, encrypt: "ciphertext", grant_type: "email-password",
        himei: HIMEI, password: state.passwordHash, scope: "all",
    ]
    Map params = [
        uri: apiBase(r), path: PATH_LOGIN, query: [timestamp: now()],
        headers: [ua: "dreo/2.8.2", lang: "en", "User-Agent": "okhttp/4.9.1"],
        requestContentType: "application/json", contentType: "application/json",
        body: body, timeout: 30,
    ]
    logDebug "Signing in (${r})"
    asynchttpPost("loginDone", params, [then: then, thenData: thenData, region: r, depth: depth])
}

def loginDone(resp, Map ctx) {
    Map json = parseJson(resp)
    if (resp.status == 200 && json && json.code != 0) {
        // Dreo answered and said no: wrong credentials. Don't retry every minute.
        state.authFailedAt = now()
        state.status = "Dreo rejected the email or password (${json.msg ?: json.code}). Will retry in 30 minutes, or enter the password again."
        log.warn "Dreo: ${state.status}"
        return
    }
    if (resp.status != 200 || !json?.data?.access_token) {
        state.status = "Sign-in failed: HTTP ${resp.status} ${errorText(resp) ?: ''}".trim()
        log.warn "Dreo: ${state.status}"
        return
    }
    String accountRegion = json.data.region
    if (REGION_HOST[accountRegion] && accountRegion != ctx.region && ctx.depth == 0) {
        log.info "Dreo: account is in region ${accountRegion}, signing in there"
        login(ctx.then, ctx.thenData, accountRegion, 1)
        return
    }
    state.region = ctx.region
    state.token = json.data.access_token
    state.tokenAt = now()
    state.remove("authFailedAt")
    state.status = "Signed in (${ctx.region == 'EU' ? 'Europe' : 'North America'})"
    log.info "Dreo: ${state.status}"
    if (ctx.then) "${ctx.then}"(ctx.thenData ?: [:])
}

// ---------------------------------------------------------------- discovery

private void startDiscovery() {
    state.discovering = now()
    state.remove("discoverError")
    apiGet(PATH_DEVICES, [acceptLanguage: "en", method: "devices", pageNo: 1, pageSize: 100], "discoverDevices")
}

def discoverDevices(data, Map ctx) {
    if (data == null) {
        state.remove("discovering")
        state.discoverError = "Dreo device list unavailable"
        return
    }
    Map found = [:]
    List unsupported = []
    (data.list ?: []).findAll { it.deviceId && it.sn }.each { d ->
        if (kindOf(d.model)) {
            found[d.sn] = [name: d.deviceName ?: d.model, model: d.model, series: d.seriesName]
        } else {
            unsupported << "${d.deviceName ?: d.model} (${d.model})"
        }
    }
    state.discovered = found
    state.unsupported = unsupported
    apiGet(PATH_ROOMS, [pageNo: 1, pageSize: 50], "discoverRooms")
}

def discoverRooms(data, Map ctx) {
    // Rooms are a nicety: the device list's own room fields are always empty.
    if (data != null) {
        Map found = state.discovered ?: [:]
        (data.familyRooms ?: []).each { family ->
            (family.rooms ?: []).each { room ->
                (room.devices ?: []).each { d ->
                    if (d.sn && room.roomName && found[d.sn]) found[d.sn].room = room.roomName
                }
            }
        }
        state.discovered = found
    }
    state.discoveredAt = now()
    state.remove("discovering")
}

// ---------------------------------------------------------------- state refresh

def pollAll() {
    getFanDevices().each { refreshFan(it.getDataValue("sn")) }
}

/** Re-read one fan's full state over REST. Called by the fan's refresh(). */
void refreshFan(String sn) {
    if (sn) apiGet(PATH_STATE, [deviceSn: sn], "fanStateDone", [sn: sn])
}

def fanStateDone(data, Map ctx) {
    def cd = fanBySn(ctx.sn)
    if (!cd || data == null) return
    Map flat = [:]
    (data.mixed ?: [:]).each { k, v ->
        flat[k] = (v instanceof Map && v.containsKey("state")) ? v.state : v
    }
    cd.dreoUpdate(flat, flat.connected == false ? false : true)
}

// ---------------------------------------------------------------- WebSocket (via the connection device)

void connectSocket(Map data = [:]) {
    def conn = getConnection()
    if (!conn) return
    if (!state.token) {
        login("connectSocket", [:])
        return
    }
    String host = REGION_HOST[state.region ?: "NA"] ?: "us"
    String token = URLEncoder.encode(state.token as String, "UTF-8")
    conn.connect("wss://wsb-${host}.dreo-tech.com/websocket?accessToken=${token}&timestamp=${now()}")
}

/** The connection device opened the socket. */
void wsOpened() {
    // Catch up on anything missed while disconnected.
    runIn(5, "pollAll")
}

/** The socket closed or failed; `failures` counts attempts since the last open. */
void wsClosed(String reason, int failures) {
    // Repeated failures may mean the token expired: sign in afresh.
    if (failures >= 3) state.remove("token")
    int delay = Math.min(60, 5 * (1 << Math.max(0, Math.min(failures - 1, 4))))
    if (state.authFailedAt) delay = (int) (AUTH_RETRY_MS / 1000)
    log.info "Dreo: cloud connection closed (${reason}); reconnecting in ${delay}s"
    runIn(delay, "connectSocket")
}

/** A message from the WebSocket about one fan. */
void dreoMessage(String sn, String method, Map reported) {
    def cd = fanBySn(sn)
    if (!cd) return
    if (method == "device-offline") {
        cd.dreoOnline(false)
    } else if (method == "device-online") {
        cd.dreoUpdate(reported ?: [:], true)
    } else if (reported) {
        cd.dreoUpdate(reported, null)
    }
}

/** A command got no acknowledgement; re-read the fan so it shows the truth. */
void commandFailed(String sn) {
    refreshFan(sn)
}

// ---------------------------------------------------------------- called by the fan drivers

/**
 * The Hubitat events for a fan's merged Dreo state. Each event is a sendEvent
 * map; events with a `child` key ("light" or "ring") belong to that child.
 * `opts`: speedSteps (override, 0 = model default), ringHue/ringSaturation
 * (the ring child's current values).
 */
List dreoEvents(cd, Map s, Map opts = [:]) {
    Map p = profile(cd.getDataValue("model"), s, opts)
    List ev = []
    Boolean on = isOn(p, s)
    String modeId = modeOf(p, s)

    if (on != null) ev << [name: "switch", value: on ? "on" : "off"]

    List names = speedNames(p.steps)
    List supported = names + (hasMode(p, "auto") ? ["auto"] : []) + ["on", "off"]
    ev << [name: "supportedFanSpeeds", value: JsonOutput.toJson(supported)]
    ev << [name: "maxSpeedLevel", value: p.max]
    if (s.windlevel instanceof Number) {
        int level = s.windlevel as int
        ev << [name: "speedLevel", value: level]
        String speed = on == false ? "off" : (modeId == "auto" ? "auto" : speedNameFor(p, level))
        ev << [name: "speed", value: speed]
    } else if (on == false) {
        ev << [name: "speed", value: "off"]
    }

    if (p.modes) {
        ev << [name: "supportedModes", value: JsonOutput.toJson(p.modes.collect { it[0] })]
        if (modeId) ev << [name: "mode", value: modeId]
    }

    String osc = oscillationOf(s)
    if (osc) ev << [name: "oscillation", value: osc]

    if (p.kind != "ceiling") {
        String display = displayOf(s)
        if (display) ev << [name: "display", value: display]
    }
    if (s.containsKey("voiceon")) ev << [name: "panelSound", value: s.voiceon ? "on" : "off"]
    else if (s.containsKey("muteon")) ev << [name: "panelSound", value: s.muteon ? "off" : "on"]
    if (s.containsKey("childlockon")) ev << [name: "childLock", value: s.childlockon ? "on" : "off"]

    BigDecimal temp = temperatureOf(s, location.temperatureScale)
    if (temp != null) ev << [name: "temperature", value: temp, unit: "°${location.temperatureScale}"]

    if (p.kind == "ceiling" && s.containsKey("lighton")) ev.addAll(ceilingLightEvents(s))
    if (s.containsKey("atmon")) ev.addAll(ringEvents(p, s, opts))
    return ev
}

/**
 * Turn a driver command into Dreo control params, send them, and return them
 * so the driver can fold them into its state straight away (null if nothing
 * was sent). Light commands are prefixed "light." or "ring.".
 */
Map dreoCommand(cd, Map s, String cmd, List args, Map opts = [:]) {
    Map p = profile(cd.getDataValue("model"), s, opts)
    Map params
    try {
        params = buildCommand(p, s, cmd, args ?: [], opts)
    } catch (IllegalArgumentException e) {
        log.warn "Dreo: ${cd.displayName}: ${e.message}"
        return null
    }
    if (!params) return null
    if (p.kind == "ceiling") params = gateParams(s, params)
    def conn = getConnection()
    if (!conn) {
        log.warn "Dreo: no cloud connection device; command not sent"
        return null
    }
    logDebug "${cd.displayName}: ${cmd} ${args} -> ${params}"
    conn.sendCommand(cd.getDataValue("sn"), params)
    return params
}

// ---------------------------------------------------------------- pure mapping logic

private String kindOf(String model) {
    String prefix = FAMILIES.keySet().find { model?.startsWith(it) }
    return prefix ? FAMILIES[prefix].kind : null
}

/** What we know about this fan: model data, adjusted by the state keys it reports. */
private Map profile(String model, Map s, Map opts) {
    String prefix = FAMILIES.keySet().find { model?.startsWith(it) }
    Map fam = prefix ? FAMILIES[prefix] : FAMILIES["DR-HTF"]
    Map m = MODELS[model] ?: [:]
    String kind = fam.kind
    int min = (m.speed ?: fam.speed)[0]
    int max = (m.speed ?: fam.speed)[1]
    int override = (opts?.speedSteps ?: 0) as int
    if (override > 0) max = min + override - 1
    // A fan reporting a higher step than we expected knows better.
    if (s.windlevel instanceof Number && (s.windlevel as int) > max) max = s.windlevel as int

    String powerKey = (kind == "ceiling" && s.containsKey("fanon")) ? "fanon"
        : s.containsKey("poweron") ? "poweron" : s.containsKey("fanon") ? "fanon" : "poweron"
    String modeKey = s.containsKey("mode") ? "mode" : s.containsKey("windtype") ? "windtype" : "mode"
    return [
        kind: kind, min: min, max: max, steps: max - min + 1,
        modes: MODE_LISTS[m.modes ?: fam.modes], powerKey: powerKey, modeKey: modeKey,
        ring: m.ring ?: [1, 5],
    ]
}

/** On a ceiling fan a load is live only when poweron and its own key are both true. */
private boolean gated(Map s, String key) {
    return (s.containsKey("poweron") ? s.poweron as boolean : true) && (s[key] as boolean)
}

private Boolean isOn(Map p, Map s) {
    if (!s.containsKey(p.powerKey)) return null
    if (p.kind == "ceiling" && p.powerKey == "fanon") return gated(s, "fanon")
    return s[p.powerKey] as boolean
}

private boolean hasMode(Map p, String id) {
    return p.modes?.any { it[0] == id }
}

private String modeOf(Map p, Map s) {
    if (!p.modes || !s.containsKey(p.modeKey)) return null
    def v = s[p.modeKey]
    def hit = p.modes.find { it[1] == v }
    // Some devices report variants such as "auto-regular" for "auto".
    if (!hit && v instanceof String) hit = p.modes.find { it[0] == v.split("-")[0] }
    return hit ? hit[0] : null
}

List speedNames(int steps) {
    return steps >= 5 ? SPEED_NAMES : FEW_SPEEDS[Math.max(1, steps)]
}

/** The fan step for the i-th named speed: names spread evenly over the steps, the last one is the top step. */
int levelForSpeed(int i, int min, int steps) {
    int n = speedNames(steps).size()
    return min + Math.max(0, (int) Math.round(((i + 1) * steps) / (double) n) - 1)
}

/** The named speed covering a fan step: the first name whose step is at or above it. */
String speedNameFor(Map p, int level) {
    List names = speedNames(p.steps)
    for (int i = 0; i < names.size(); i++) {
        if (levelForSpeed(i, p.min, p.steps) >= level) return names[i]
    }
    return names[-1]
}

private String oscillationOf(Map s) {
    if (s.containsKey("oscmode")) return OSC_MODES[(s.oscmode as int) & 3]
    if (s.containsKey("hoscon") && s.containsKey("voscon")) {
        return OSC_MODES[(s.hoscon ? 1 : 0) | (s.voscon ? 2 : 0)]
    }
    String key = OSC_KEYS.find { s.containsKey(it) }
    return key ? (s[key] ? "on" : "off") : null
}

private String displayOf(Map s) {
    if (s.lightmode instanceof Number) return DISPLAY_MODES[s.lightmode as int] ?: null
    String key = DISPLAY_KEYS.find { s.containsKey(it) }
    return key ? (s[key] ? "on" : "off") : null
}

/**
 * Temperature in the hub's scale. Dreo reports in the unit chosen in the Dreo
 * app (tempunit 1 = °F) with the calibration offset to add on top; models
 * without tempunit report °F whenever the value is above 50 (hass-dreo's rule).
 */
BigDecimal temperatureOf(Map s, String hubScale) {
    if (!(s.temperature instanceof Number)) return null
    BigDecimal t = (s.temperature as BigDecimal) + (s.tempoffset instanceof Number ? s.tempoffset as BigDecimal : 0)
    boolean fahrenheit = s.containsKey("tempunit") ? s.tempunit == 1 : (s.temperature as BigDecimal) > 50
    BigDecimal c = fahrenheit ? (t - 32) * 5 / 9 : t
    BigDecimal out = hubScale == "F" ? c * 9 / 5 + 32 : c
    return out.setScale(1, java.math.RoundingMode.HALF_UP)
}

private List ceilingLightEvents(Map s) {
    List ev = [[child: "light", name: "switch", value: gated(s, "lighton") ? "on" : "off"]]
    if (s.brightness instanceof Number) ev << [child: "light", name: "level", value: s.brightness as int, unit: "%"]
    if (s.colortemp instanceof Number) {
        int k = CT_WARM + (int) Math.round((s.colortemp as int) * (CT_COOL - CT_WARM) / 100d)
        ev << [child: "light", name: "colorTemperature", value: k, unit: "K"]
        ev << [child: "light", name: "colorName", value: k < 3500 ? "Warm White" : k < 5000 ? "Neutral White" : "Daylight"]
    }
    return ev
}

private List ringEvents(Map p, Map s, Map opts) {
    boolean on = p.kind == "ceiling" ? gated(s, "atmon") : s.atmon as boolean
    List ev = [[child: "ring", name: "switch", value: on ? "on" : "off"]]
    if (s.atmbri instanceof Number) {
        int level = (int) Math.round((s.atmbri as int) * 100d / (p.ring[1] as int))
        ev << [child: "ring", name: "level", value: Math.max(1, Math.min(100, level)), unit: "%"]
    }
    if (s.atmcolor instanceof Number) {
        int rgb = s.atmcolor as int
        ev << [child: "ring", name: "RGB", value: String.format("#%06X", rgb & 0xFFFFFF)]
        ev << [child: "ring", name: "color", value: String.format("#%06X", rgb & 0xFFFFFF)]
        // Leave hue/saturation alone when they already produce this colour, so a
        // just-picked hue isn't nudged by RGB rounding.
        Integer current = (opts?.ringHue != null && opts?.ringSaturation != null)
            ? hsToRgbInt(opts.ringHue as BigDecimal, opts.ringSaturation as BigDecimal) : null
        if (current != rgb) {
            Map hs = rgbIntToHs(rgb)
            ev << [child: "ring", name: "hue", value: hs.hue, unit: "%"]
            ev << [child: "ring", name: "saturation", value: hs.saturation, unit: "%"]
        }
        ev << [child: "ring", name: "colorMode", value: "RGB"]
    }
    if (s.atmmode instanceof Number) {
        String effect = RING_EFFECTS[s.atmmode as int]
        if (effect) ev << [child: "ring", name: "effectName", value: effect]
        ev << [child: "ring", name: "lightEffects", value: JsonOutput.toJson(RING_EFFECTS)]
    }
    return ev
}

/** Hue and saturation on Hubitat's 0-100 scales to a 24-bit RGB int, at full value. */
int hsToRgbInt(BigDecimal hue, BigDecimal sat) {
    double h = (hue as double) / 100.0
    double sv = (sat as double) / 100.0
    Closure<Integer> f = { int n ->
        double k = (n + h * 6) % 6
        (int) Math.round(255 * (1 - sv * Math.max(0, Math.min(Math.min(k, 4 - k), 1))))
    }
    return (f(5) << 16) | (f(3) << 8) | f(1)
}

/** A 24-bit RGB int to hue and saturation on Hubitat's 0-100 scales. */
Map rgbIntToHs(int color) {
    double r = ((color >> 16) & 0xff) / 255d
    double g = ((color >> 8) & 0xff) / 255d
    double b = (color & 0xff) / 255d
    double max = Math.max(r, Math.max(g, b))
    double d = max - Math.min(r, Math.min(g, b))
    double h = 0
    if (d > 0) {
        if (max == r) h = ((g - b) / d) % 6
        else if (max == g) h = (b - r) / d + 2
        else h = (r - g) / d + 4
        h = (h < 0 ? h + 6 : h) / 6
    }
    return [hue: (int) Math.round(h * 100), saturation: max > 0 ? (int) Math.round(d / max * 100) : 0]
}

private boolean onOffArg(def v, String what) {
    String x = v?.toString()?.toLowerCase()
    if (x in ["on", "true", "1"]) return true
    if (x in ["off", "false", "0"]) return false
    throw new IllegalArgumentException("${what} must be on or off, not ${v}")
}

private int clamp(def v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, Math.round(v as double) as int))
}

/** Params for a fan command. Throws IllegalArgumentException for bad input. */
private Map buildCommand(Map p, Map s, String cmd, List args, Map opts) {
    def a = args ? args[0] : null
    Boolean on = isOn(p, s)
    Closure<Map> power = { boolean v -> [(p.powerKey): v] }
    // Speed or mode changes on a fan that is off also switch it on.
    Closure<Map> wake = { Map m -> on ? m : power(true) + m }

    switch (cmd) {
        case "on": return power(true)
        case "off": return power(false)
        case "setSpeedLevel":
            int level = a as int
            if (level <= 0) return power(false)
            if (level < p.min || level > p.max) throw new IllegalArgumentException("speed level must be ${p.min}-${p.max}")
            return wake(speedParams(p, s, level))
        case "setSpeed":
            String speed = a?.toString()
            if (speed == "off") return power(false)
            if (speed == "on") return power(true)
            if (speed == "auto") {
                def autoMode = p.modes?.find { it[0] == "auto" }
                if (!autoMode) throw new IllegalArgumentException("this fan has no auto mode")
                return wake([(p.modeKey): autoMode[1]])
            }
            int named = speedNames(p.steps).indexOf(speed)
            if (named < 0) throw new IllegalArgumentException("unsupported speed ${speed}")
            return wake(speedParams(p, s, levelForSpeed(named, p.min, p.steps)))
        case "cycleSpeed":
            List names = speedNames(p.steps)
            int cur = s.windlevel instanceof Number ? names.indexOf(speedNameFor(p, s.windlevel as int)) : -1
            int next = on ? (cur + 1) % names.size() : 0
            return wake(speedParams(p, s, levelForSpeed(next, p.min, p.steps)))
        case "setMode":
            def mode = p.modes?.find { it[0] == a?.toString() }
            if (!mode) throw new IllegalArgumentException("unsupported mode ${a}; this fan has ${p.modes?.collect { it[0] }}")
            return wake([(p.modeKey): mode[1]])
        case "setOscillation":
            return oscillationParams(s, a?.toString())
        case "setDisplay":
            if (s.lightmode instanceof Number) {
                int dm = DISPLAY_MODES.indexOf(a?.toString())
                if (dm < 0) throw new IllegalArgumentException("display must be off, on or auto")
                return [lightmode: dm]
            }
            String key = DISPLAY_KEYS.find { s.containsKey(it) }
            if (!key || p.kind == "ceiling") throw new IllegalArgumentException("this fan has no display setting")
            return [(key): onOffArg(a, "display")]
        case "setPanelSound":
            boolean sound = onOffArg(a, "panel sound")
            if (s.containsKey("voiceon")) return [voiceon: sound]
            if (s.containsKey("muteon")) return [muteon: !sound]
            throw new IllegalArgumentException("this fan has no panel sound setting")
        case "setChildLock":
            if (!s.containsKey("childlockon")) throw new IllegalArgumentException("this fan has no child lock")
            return [childlockon: onOffArg(a, "child lock")]
    }
    if (cmd.startsWith("light.")) return lightParams(p, s, cmd.substring(6), args)
    if (cmd.startsWith("ring.")) return ringParams(p, s, cmd.substring(5), args, opts)
    throw new IllegalArgumentException("unknown command ${cmd}")
}

/** A speed step; leaving auto mode, since a fixed speed means manual control. */
private Map speedParams(Map p, Map s, int level) {
    Map params = [windlevel: level]
    if (modeOf(p, s) == "auto") {
        def normal = p.modes.find { it[0] == "normal" }
        if (normal) params[p.modeKey] = normal[1]
    }
    return params
}

private Map oscillationParams(Map s, String v) {
    boolean twoAxis = s.containsKey("oscmode") || (s.containsKey("hoscon") && s.containsKey("voscon"))
    if (twoAxis) {
        int bits
        if (v == "on") bits = (s.containsKey("oscmode") ? (s.oscmode as int) & 2 : (s.voscon ? 2 : 0)) | 1
        else bits = OSC_MODES.indexOf(v)
        if (bits < 0) throw new IllegalArgumentException("oscillation must be off, on, horizontal, vertical or both")
        return s.containsKey("oscmode") ? [oscmode: bits] : [hoscon: (bits & 1) != 0, voscon: (bits & 2) != 0]
    }
    String key = OSC_KEYS.find { s.containsKey(it) }
    if (!key) throw new IllegalArgumentException("this fan does not oscillate")
    if (v in ["vertical", "both"]) throw new IllegalArgumentException("this fan only oscillates horizontally")
    if (!(v in ["off", "on", "horizontal"])) throw new IllegalArgumentException("oscillation must be off, on or horizontal")
    return [(key): v != "off"]
}

private Map lightParams(Map p, Map s, String cmd, List args) {
    def a = args ? args[0] : null
    boolean lit = gated(s, "lighton")
    switch (cmd) {
        case "on": return [lighton: true]
        case "off": return [lighton: false]
        case "setLevel":
            if ((a as double) <= 0) return [lighton: false]
            return [brightness: clamp(a, 1, 100)] + (lit ? [:] : [lighton: true])
        case "setColorTemperature":
            int k = clamp(a, CT_WARM, CT_COOL)
            Map params = [colortemp: (int) Math.round((k - CT_WARM) * 100d / (CT_COOL - CT_WARM))]
            if (args.size() > 1 && args[1] != null && (args[1] as double) > 0) params.brightness = clamp(args[1], 1, 100)
            return params + (lit ? [:] : [lighton: true])
    }
    throw new IllegalArgumentException("unknown light command ${cmd}")
}

private Map ringParams(Map p, Map s, String cmd, List args, Map opts) {
    def a = args ? args[0] : null
    boolean lit = p.kind == "ceiling" ? gated(s, "atmon") : s.atmon as boolean
    Map params
    switch (cmd) {
        case "on": return [atmon: true]
        case "off": return [atmon: false]
        case "setLevel":
            if ((a as double) <= 0) return [atmon: false]
            params = [atmbri: clamp((a as double) * (p.ring[1] as int) / 100d, p.ring[0], p.ring[1])]
            break
        case "setColor":
            Map c = a as Map
            params = [atmcolor: hsToRgbInt((c.hue != null ? c.hue : 0) as BigDecimal, (c.saturation != null ? c.saturation : 100) as BigDecimal)]
            if (c.level != null && (c.level as double) > 0) params.atmbri = clamp((c.level as double) * (p.ring[1] as int) / 100d, p.ring[0], p.ring[1])
            break
        case "setHue":
            params = [atmcolor: hsToRgbInt(a as BigDecimal, (opts?.ringSaturation != null ? opts.ringSaturation : 100) as BigDecimal)]
            break
        case "setSaturation":
            params = [atmcolor: hsToRgbInt((opts?.ringHue != null ? opts.ringHue : 0) as BigDecimal, a as BigDecimal)]
            break
        case "setEffect":
            int effect = a as int
            if (!RING_EFFECTS[effect]) throw new IllegalArgumentException("effect must be 1 (constant), 2 (circle) or 3 (breath)")
            params = [atmmode: effect]
            break
        case "setNextEffect":
        case "setPreviousEffect":
            int cur = s.atmmode instanceof Number ? s.atmmode as int : 1
            int n = RING_EFFECTS.size()
            params = [atmmode: ((cur - 1 + (cmd == "setNextEffect" ? 1 : n - 1)) % n) + 1]
            break
        default:
            throw new IllegalArgumentException("unknown light ring command ${cmd}")
    }
    // Changing brightness, colour or effect on a dark ring turns it on, like the Dreo app.
    return lit ? params : params + [atmon: true]
}

/**
 * Ceiling fans: add the gate keys a batch of load changes needs. Load keys
 * survive a gate close, so waking one load must open the gate and switch the
 * others off explicitly, and switching off the last load must close the gate.
 * Behaviour per hass-dreo's PyDreoCeilingFan.
 */
Map gateParams(Map s, Map params) {
    List loads = CEILING_LOADS.findAll { params.containsKey(it) }
    if (!loads || params.containsKey("poweron") || !s.containsKey("poweron")) return params
    if (!s.poweron && loads.any { params[it] }) {
        Map out = [:] + params
        out.poweron = true
        CEILING_LOADS.each { if (s.containsKey(it) && !params.containsKey(it)) out[it] = false }
        return out
    }
    Map after = [:] + s + params
    if (s.poweron && !CEILING_LOADS.any { after[it] }) return params + [poweron: false]
    return params
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "Dreo: ${msg}"
}
