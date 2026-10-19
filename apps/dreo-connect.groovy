/**
 * Dreo Connect: Hubitat integration for Dreo cloud fans and humidifiers.
 *
 * The app holds the Dreo account, signs in, discovers devices and creates one
 * child device per fan or humidifier. A "Dreo Cloud Connection" child device holds the
 * WebSocket (only drivers can open one), which carries pushed state reports
 * and outgoing commands for every fan on the account.
 *
 * All Dreo-specific mapping (speed steps, modes, oscillation, the ceiling-fan
 * power gate, light colours, temperature units, humidifier modes and targets)
 * lives in this file as plain functions of the device's reported state, so the
 * fan, humidifier and light drivers stay thin.
 *
 * Protocol, model data and feature mappings are ported from hass-dreo
 * (https://github.com/JeffSteinbok/hass-dreo, MIT License; see LICENSE).
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "Dreo Connect",
    namespace: "dreo",
    author: "Digital Homesteaders",
    description: "Control Dreo cloud fans (tower, circulator, ceiling) and humidifiers from Hubitat.",
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

// The release version, the same in the app, every driver and packageManifest.json
// (scripts/release.sh sets them all). Drivers compare theirs with it.
@Field static final String VERSION = "0.2.0"

@Field static final String CLIENT_ID = "7de37c362ee54dcf9c4561812309347a"
@Field static final String CLIENT_SECRET = "32dfa0764f25451d99f94e1693498791"
@Field static final String HIMEI = "faede31549d649f58864093158787ec9"
@Field static final Map REGION_HOST = [NA: "us", EU: "eu"]

// After Dreo rejects the credentials, wait this long before trying them again.
@Field static final long AUTH_RETRY_MS = 30 * 60 * 1000L
// A discovery still running after this long has failed (e.g. sign-in failed).
@Field static final long DISCOVERY_TIMEOUT_MS = 45 * 1000L

// Sign-in bookkeeping shared by every execution of this app, keyed by app id.
// state is read once per execution, so concurrent callbacks (one per fan
// after a token expires) can't see each other's changes there: this map lets
// them share one sign-in and the token it produced.
@Field static final ConcurrentHashMap<Long, Map> AUTH = new ConcurrentHashMap<>()
// A sign-in older than this is assumed lost and may be started again.
@Field static final long LOGIN_STALE_MS = 60 * 1000L

// Commands for a device showing offline, held while a refresh checks whether
// it really is (see dreoCommand), keyed by serial number. In memory only: they
// are seconds old at most.
@Field static final ConcurrentHashMap<String, Map> HELD = new ConcurrentHashMap<>()
// Held commands still waiting after this long are dropped (the refresh got lost).
@Field static final long HELD_MAX_AGE_MS = 30 * 1000L
@Field static final int HELD_MAX = 10
// The cloud connection counts as down (every device offline) after this long
// without reconnecting.
@Field static final long CLOUD_DOWN_MS = 2 * 60 * 1000L

// Device-list fields the raw dump keeps (model data, nothing personal).
@Field static final List RAW_DEVICE_KEYS = ["model", "series", "seriesName", "productId", "productName", "type", "brand", "color", "controlsConf", "mainConf"]

// State keys the raw dump leaves out: network identifiers and anything secret-looking.
@Field static final String RAW_PRIVATE_STATE = /(?i).*(mac|ssid|ipaddr|password|token|secret).*|ip|.*_ip/

// State keys devices don't keep: hardware and firmware details (firmware goes
// in a data value) and network readings that change all the time, each change
// costing a state write, without meaning anything here. Private keys
// (RAW_PRIVATE_STATE) are dropped too.
@Field static final List STATE_SKIP = ["wifi_rssi", "network_latency", "module_hardware_model", "mcu_hardware_model",
    "module_firmware_version", "mcu_firmware_version", "_ota"]

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
    // Humidifiers: manual mist level, auto (holds rhautolevel), sleep (holds rhsleeplevel).
    humidifier: [["manual", 0], ["auto", 1], ["sleep", 2]],
]

// Mode names for the text keys in a device list's controlsConf (hass-dreo's
// PRESET_MODE_STRINGS). Keys not listed here keep Dreo's own text.
@Field static final Map MODE_TEXT = [
    device_fans_mode_straight: "normal", device_control_mode_manual: "normal",
    device_fans_mode_natural: "natural",
    device_fans_mode_sleep: "sleep", device_control_mode_sleep: "sleep",
    device_fans_mode_auto: "auto", device_control_mode_auto: "auto", base_auto: "auto",
    device_fans_mode_turbo: "turbo", device_control_mode_turbo: "turbo",
    device_control_custom: "custom", base_reverse: "reverse",
]

// Family defaults by model prefix. Unknown fans in a family use these.
@Field static final Map FAMILIES = [
    "DR-HTF": [kind: "tower", speed: [1, 9], modes: "basic"],
    "DR-HAF": [kind: "circulator", speed: [1, 9], modes: "basic"],
    "DR-HPF": [kind: "circulator", speed: [1, 9], modes: "basic", v: [-30, 90]],
    "DR-HCF": [kind: "ceiling", speed: [1, 12], modes: "ceiling"],
    "DR-HHM": [kind: "humidifier", modes: "humidifier"],
]

// Per-model details, from hass-dreo's pydreo/models.py. A fan's own
// controlsConf in the device list (fanLimits) overrides `speed` and `modes`
// unless the entry says `speedFixed`. `ring` is the light
// ring's brightness range (atmbri) where it differs from 1-5. `h`/`v` are the
// head's horizontal and vertical angle ranges (degrees) where hass-dreo gives
// them; `settle` is the seconds a head needs between position commands.
@Field static final Map MODELS = [
    // controlsConf says 1-4 on every revision; newer ones have 8 steps (hass-dreo).
    "DR-HTF007S": [speed: [1, 8], speedFixed: true],
    "DR-HTF011S": [speed: [1, 9]],
    "DR-HTF017S": [speed: [1, 4]],
    "DR-HTF018S": [speed: [1, 9]],
    "DR-HTF021S": [speed: [1, 12]],
    "DR-HTF021AS": [speed: [1, 12]],
    "DR-HTF024S": [speed: [1, 9]],
    "DR-HTF518S": [speed: [1, 9]],
    "DR-HAF001S": [speed: [1, 4]],
    // Rejects a new head position while the head still moves; stacked rejects can need recalibration.
    "DR-HAF004S": [settle: 8],
    "DR-HAF008S": [speed: [1, 9], modes: "turbo"],
    // hass-dreo lists turbo and custom too; the two test units ignore both (2026-10-18).
    "DR-HPF004S": [speed: [1, 9], modes: "basic", h: [-75, 75], v: [-30, 90]],
    "DR-HPF005S": [speed: [1, 10], modes: "custom", h: [-60, 60]],
    "DR-HPF007S": [speed: [1, 10], modes: "custom", h: [-75, 75], v: [-30, 90]],
    "DR-HPF008S": [speed: [1, 9], modes: "turbo", ring: [1, 3], v: [-30, 90]],
    "DR-HPF015S": [speed: [1, 12], modes: "custom", h: [-75, 75], v: [0, 90]],
    "DR-HPF017S": [speed: [1, 12], modes: "swappedCustom", h: [-75, 75], v: [-30, 90], settle: 8],
    "DR-HPF020S": [speed: [1, 9], modes: "custom", h: [-60, 60], v: [-30, 90]],
    "DR-HPF022S": [speed: [1, 9], modes: "turbo", h: [-60, 60], v: [-30, 90]],
    "DR-HPF025S": [speed: [1, 9], modes: "swapped", h: [-60, 60], v: [-30, 90]],
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
// Head angle ranges when neither the model table nor the family gives one (as the Homey app).
@Field static final List ANGLES_H = [-60, 60]
@Field static final List ANGLES_V = [0, 90]
// Oscillation limits on one axis must be at least this many degrees apart (hass-dreo).
@Field static final int MIN_OSC_SPAN = 30
// When each fan last got a head position, and the newest one waiting for the
// head to settle, keyed by serial number (models with `settle`). Shared by
// every execution of this app, since state is read once per execution.
@Field static final ConcurrentHashMap<String, Map> HEAD = new ConcurrentHashMap<>()
// Humidifier ranges when the device list's controlsConf doesn't give them (hass-dreo's defaults).
@Field static final List HUM_TARGET_RANGE = [30, 90]
@Field static final List HUM_MIST_RANGE = [1, 6]
// Humidifier `wrong` codes meaning the tank is empty or off its base.
@Field static final List HUM_TANK_EMPTY = [1, 4]

// ---------------------------------------------------------------- pages

def mainPage() {
    hashPasswordSetting()
    dynamicPage(name: "mainPage", title: "Dreo Connect", install: true, uninstall: true) {
        section("Dreo account") {
            input "email", "email", title: "Email", required: true, submitOnChange: true
            input "password", "password", title: passwordHash() ? "Password (saved; enter again to change)" : "Password",
                required: false, submitOnChange: true
            paragraph "Only an MD5 hash of the password is kept, which is what Dreo's sign-in expects, in a " +
                "password-type setting. The hash signs in to Dreo just like the password and is in hub " +
                "backups, so keep those private."
            if (state.status) paragraph "<b>Status:</b> ${state.status}"
        }
        if (settings.email && passwordHash()) {
            section("Devices") {
                href "fansPage", title: "Choose devices", description: selectedSummary()
                getFanDevices().each { cd ->
                    def what = cd.getDataValue("kind") == "humidifier"
                        ? "${cd.currentValue('humidity') ?: '?'} % humidity" : cd.currentValue('speed') ?: '?'
                    paragraph "${cd.displayName}: ${cd.currentValue('switch') ?: '?'}, ${what} " +
                        "(${cd.getDataValue('model')}, ${cd.currentValue('healthStatus') ?: 'unknown'})"
                }
                def conn = getConnection()
                if (conn) {
                    paragraph "Cloud connection: ${conn.currentValue('connection') ?: 'unknown'}. The " +
                        "'Dreo Cloud Connection' device carries live updates and commands for all of these; " +
                        "leave it in place (pressing Done adds it back if it is deleted)."
                }
            }
        }
        section("Logging") {
            input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
            paragraph "Dreo Connect version ${VERSION}${driverVersionNote()}"
            if (settings.email && passwordHash()) {
                input name: "dumpRaw", type: "button", title: "Log raw Dreo data"
                paragraph "Writes each device's raw Dreo device-list entry and full state to Logs, for reporting a model that isn't mapped yet."
            }
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
    Map page = [name: "fansPage", title: "Choose devices"]
    if (started) page.refreshInterval = 2
    dynamicPage(page) {
        section {
            if (started) {
                paragraph "Looking up devices on your Dreo account..."
            } else if (state.discoverError) {
                paragraph "Could not list devices: ${state.discoverError}"
            }
            Map found = state.discovered ?: [:]
            if (found) {
                Map options = found.collectEntries { sn, f ->
                    [(sn): "${f.name} (${f.series ?: f.model}${f.room ? ', ' + f.room : ''})"]
                }.sort { it.value }
                input "selectedFans", "enum", title: "Fans and humidifiers to add to Hubitat", options: options, multiple: true, required: false
                paragraph "Devices you untick are removed from Hubitat when you press Done."
            } else if (!started && !state.discoverError) {
                paragraph "No fans or humidifiers found on this Dreo account."
            }
            if (state.unsupported) paragraph "Not supported yet: ${state.unsupported.join(', ')}"
            if (!started) {
                input name: "rediscover", type: "button", title: "Look again"
                paragraph "Lists the Dreo account's devices again, e.g. after adding one in the Dreo app."
            }
        }
    }
}

private String selectedSummary() {
    int n = (settings.selectedFans ?: []).size()
    n ? "${n} device${n == 1 ? '' : 's'} selected" : "Tap to pick fans and humidifiers"
}

/** Replace a freshly entered password with its MD5 hash. */
private void hashPasswordSetting() {
    if (settings.email && settings.email != state.emailUsed) {
        state.emailUsed = settings.email
        dropToken()
        state.remove("authFailedAt")
        state.remove("discoveredAt")
    }
    // Before 0.3.0 the hash lived in state, which the app's status page shows as is.
    if (state.passwordHash) {
        if (settings.passwordMd5 == state.passwordHash) state.remove("passwordHash")
        else savePasswordHash(state.passwordHash as String)
    }
    if (settings.password) {
        savePasswordHash(md5(settings.password as String))
        app.removeSetting("password")
        dropToken()
        state.remove("authFailedAt")
        state.remove("discoveredAt")
        state.status = "Password saved"
    }
}

/**
 * The hash is kept in a password-type setting, which the hub doesn't show in
 * plain text. state.passwordHash holds it only until the setting reads back
 * the same value, and is newer than the setting while it exists.
 */
private void savePasswordHash(String h) {
    app.updateSetting("passwordMd5", [value: h, type: "password"])
    if (settings.passwordMd5 == h) state.remove("passwordHash")
    else state.passwordHash = h
}

private String passwordHash() {
    (state.passwordHash ?: settings.passwordMd5) as String
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
    log.info "Dreo Connect ${VERSION}"
    hashPasswordSetting()
    syncChildren()
    checkDrivers()
    if (settings.logEnable) runIn(1800, "logsOff")
    if (getFanDevices()) {
        runEvery30Minutes("pollAll")
        connectSocket()
        // Re-read the device list so devices pick up speed ranges, modes and
        // humidifier limits from Dreo (e.g. after pasting a newer app) without
        // a visit to Choose devices.
        if (!state.discovering) startDiscovery()
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
        String kind = kindOf(f.model)
        String driver = kind == "ceiling" ? "Dreo Ceiling Fan" : kind == "humidifier" ? "Dreo Humidifier" : "Dreo Fan"
        try {
            // Data values go in with the device: its installed() runs inside addChildDevice.
            addChildDevice("dreo", driver, fanDni(sn), [name: f.series ?: f.model, label: f.name, isComponent: false,
                data: [sn: sn, model: f.model, kind: kind] + (f.limits ?: [:])])
            log.info "Dreo: added ${f.name} (${f.model})"
        } catch (e) {
            log.error "Dreo: could not add ${f.name}: ${e.message} (is the '${driver}' driver installed?)"
        }
    }
    applyLimits(found)
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

/** Limits read at discovery (speed range, modes, humidifier ranges) also reach devices added earlier. */
private void applyLimits(Map found) {
    getFanDevices().each { cd ->
        Map limits = found[cd.getDataValue("sn")]?.limits ?: [:]
        boolean changed = false
        limits.each { k, v ->
            if (cd.getDataValue(k) != v) {
                cd.updateDataValue(k, v)
                changed = true
            }
        }
        // Show the new range or modes now rather than at the fan's next report.
        if (changed) refreshFan(cd.getDataValue("sn"))
    }
}

/** Debug logging switches itself off 30 minutes after it's turned on. */
void logsOff() {
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

/** The app's code version; drivers check theirs against it. */
String dreoVersion() { VERSION }

/**
 * Ask every device which driver version it runs (fans also ask their light
 * children). A driver too old to answer is from an earlier version too.
 */
private void checkDrivers() {
    List odd = []
    getChildDevices().each { cd ->
        try {
            odd.addAll(cd.dreoHello(VERSION) ?: [])
        } catch (e) {
            odd << "${cd.displayName} (older)".toString()
        }
    }
    state.driverVersions = odd
    if (odd) log.warn "Dreo: drivers from another version than the app (${VERSION}): ${odd.join(', ')}. Re-paste them and press Done."
}

private String driverVersionNote() {
    List odd = state.driverVersions ?: []
    return odd ? ". <b>Drivers from another version:</b> ${odd.join(', ')}. Re-paste them and press Done." : ""
}

/** Merged Dreo state as devices keep it: without private, hardware and noisy network keys. */
Map dreoState(Map s) {
    return (s ?: [:]).findAll { k, v -> !(k in STATE_SKIP) && !(k ==~ RAW_PRIVATE_STATE) }
}

private String fanDni(String sn) { "dreo-${sn}" }
private String connDni() { "dreo-connection-${app.id}" }
private def getConnection() { getChildDevice(connDni()) }
private List getFanDevices() { getChildDevices().findAll { it.deviceNetworkId != connDni() } }
private def fanBySn(String sn) { getChildDevice(fanDni(sn)) }

// ---------------------------------------------------------------- REST

private Map auth() {
    Long id = app.id as Long
    AUTH.putIfAbsent(id, [:])
    return AUTH.get(id)
}

/** The newest access token, from this execution's state or another's sign-in. */
private String currentToken() {
    Map a = auth()
    synchronized (a) {
        if (a.tokenAt && (a.tokenAt as Long) > ((state.tokenAt ?: 0) as Long)) {
            state.token = a.token
            state.tokenAt = a.tokenAt
        }
    }
    return state.token
}

private Long currentTokenAt() {
    currentToken()
    return state.tokenAt as Long
}

private void dropToken() {
    state.remove("token")
    Map a = auth()
    synchronized (a) { a.remove("token"); a.remove("tokenAt") }
}

private String apiBase(String region = null) {
    "https://app-api-${REGION_HOST[region ?: state.region ?: 'NA'] ?: 'us'}.dreo-tech.com"
}

private Map apiHeaders() {
    Map h = [ua: "dreo/2.8.2", lang: "en", "User-Agent": "okhttp/4.9.1"]
    String token = currentToken()
    if (token) h.Authorization = "Bearer ${token}"
    return h
}

/** GET a Dreo API path; calls `callback(data, cbData)` with data = null on failure. */
private void apiGet(String path, Map query, String callback, Map cbData = [:], boolean retried = false) {
    Map req = [path: path, query: query ?: [:], cb: callback, data: cbData, retried: retried]
    if (!currentToken()) {
        login("apiReplay", req)
        return
    }
    req.tokenAt = currentTokenAt()
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
        if (currentToken() && currentTokenAt() != req.tokenAt) {
            apiReplay(req)
        } else {
            log.info "Dreo: ${req.path} returned 401, signing in again"
            dropToken()
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

/**
 * Sign in, then call `then(thenData)`. Callers arriving while a sign-in is
 * already under way wait for that one instead of starting their own.
 */
private void login(String then, Map thenData, String region = null, int depth = 0) {
    if (!settings.email || !passwordHash()) {
        state.status = "Enter your Dreo email and password"
        return
    }
    if (state.authFailedAt && now() - (state.authFailedAt as Long) < AUTH_RETRY_MS) {
        logDebug "Sign-in skipped: Dreo rejected the credentials recently"
        return
    }
    if (depth == 0) {
        Map a = auth()
        synchronized (a) {
            boolean running = a.loginAt && now() - (a.loginAt as Long) < LOGIN_STALE_MS
            if (!running) {
                a.loginAt = now()
                a.waiters = []
            }
            if (then) a.waiters << [then: then, thenData: thenData ?: [:]]
            if (running) {
                logDebug "Sign-in already under way; waiting for it"
                return
            }
        }
    }
    String r = region ?: state.region ?: "NA"
    Map body = [
        acceptLanguage: "en", client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
        email: settings.email, encrypt: "ciphertext", grant_type: "email-password",
        himei: HIMEI, password: passwordHash(), scope: "all",
    ]
    Map params = [
        uri: apiBase(r), path: PATH_LOGIN, query: [timestamp: now()],
        headers: [ua: "dreo/2.8.2", lang: "en", "User-Agent": "okhttp/4.9.1"],
        requestContentType: "application/json", contentType: "application/json",
        body: body, timeout: 30,
    ]
    logDebug "Signing in (${r})"
    asynchttpPost("loginDone", params, [region: r, depth: depth])
}

/** End the sign-in under way and return who was waiting for it. */
private List finishLogin() {
    Map a = auth()
    synchronized (a) {
        List w = a.waiters ?: []
        a.remove("loginAt")
        a.remove("waiters")
        return w
    }
}

def loginDone(resp, Map ctx) {
    Map json = parseJson(resp)
    if (resp.status == 200 && json && json.code != 0) {
        // Dreo answered and said no: wrong credentials. Don't retry every minute.
        state.authFailedAt = now()
        state.status = "Dreo rejected the email or password (${json.msg ?: json.code}). Will retry in 30 minutes, or enter the password again."
        log.warn "Dreo: ${state.status}"
        finishLogin()
        // Without a sign-in the cloud connection can't come back.
        if (getConnection()?.currentValue("connection") != "connected") cloudDown("Dreo rejected the sign-in")
        return
    }
    if (resp.status != 200 || !json?.data?.access_token) {
        state.status = "Sign-in failed: HTTP ${resp.status} ${errorText(resp) ?: ''}".trim()
        log.warn "Dreo: ${state.status}"
        finishLogin()
        return
    }
    String accountRegion = json.data.region
    if (REGION_HOST[accountRegion] && accountRegion != ctx.region && ctx.depth == 0) {
        log.info "Dreo: account is in region ${accountRegion}, signing in there"
        login(null, null, accountRegion, 1)
        return
    }
    state.region = ctx.region
    state.token = json.data.access_token
    state.tokenAt = now()
    Map a = auth()
    synchronized (a) {
        a.token = state.token
        a.tokenAt = state.tokenAt
    }
    state.remove("authFailedAt")
    state.status = "Signed in (${ctx.region == 'EU' ? 'Europe' : 'North America'})"
    log.info "Dreo: ${state.status}"
    finishLogin().each { Map w -> "${w.then}"(w.thenData ?: [:]) }
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
        String kind = kindOf(d.model)
        if (kind) {
            found[d.sn] = [name: d.deviceName ?: d.model, model: d.model, series: d.seriesName]
            try {
                found[d.sn].limits = kind == "humidifier" ? humidifierLimits(d.controlsConf) : fanLimits(d.controlsConf)
            } catch (e) {
                log.warn "Dreo: could not read ${d.model} limits (${e}); using defaults"
            }
        } else {
            unsupported << "${d.deviceName ?: d.model} (${d.model})"
        }
    }
    state.discovered = found
    state.unsupported = unsupported
    applyLimits(found)
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

// ---------------------------------------------------------------- raw data dump

def appButtonHandler(String btn) {
    if (btn == "rediscover") {
        if (!state.discovering) startDiscovery()
        return
    }
    if (btn == "dumpRaw") {
        log.info "Dreo: logging raw Dreo data"
        apiGet(PATH_DEVICES, [acceptLanguage: "en", method: "devices", pageNo: 1, pageSize: 100], "dumpDevices")
    }
}

def dumpDevices(data, Map ctx) {
    if (data == null) return
    (data.list ?: []).each { d ->
        // Only what describes the model: the entry also names the owner, home
        // and rooms, and people paste these logs into public threads.
        Map entry = d.findAll { k, v -> k in RAW_DEVICE_KEYS }
        log.info "Dreo raw device ${d.model} ${shortSn(d.sn)}: ${JsonOutput.toJson(entry)}"
        if (d.sn) apiGet(PATH_STATE, [deviceSn: d.sn], "dumpState", [sn: d.sn, model: d.model])
    }
}

def dumpState(data, Map ctx) {
    if (data == null) return
    Map flat = new TreeMap()
    (data.mixed ?: [:]).each { k, v ->
        if (!(k ==~ RAW_PRIVATE_STATE)) flat[k] = (v instanceof Map && v.containsKey("state")) ? v.state : v
    }
    log.info "Dreo raw state ${ctx.model} ${shortSn(ctx.sn)}: ${JsonOutput.toJson(flat)}"
}

/**
 * A short tag for a serial number, enough to tell devices apart in a log
 * without showing it. (Not its last characters: the test fans all end in "000s".)
 */
private String shortSn(def sn) {
    return sn ? "#" + md5(sn.toString()).take(6) : ""
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
    List held = takeHeld(ctx.sn as String)
    if (!cd) return
    if (data == null) {
        if (held) log.warn "Dreo: could not check whether ${cd.displayName} is online; ${held*.cmd.join(', ')} not sent"
        return
    }
    Map flat = [:]
    (data.mixed ?: [:]).each { k, v ->
        flat[k] = (v instanceof Map && v.containsKey("state")) ? v.state : v
    }
    String fw = ["module_firmware_version", "mcu_firmware_version"].findAll { flat[it] }.collect { flat[it] }.join(" / ")
    if (fw && cd.getDataValue("firmware") != fw) cd.updateDataValue("firmware", fw)
    boolean connected = flat.connected != false
    // While the cloud connection is down every device stays offline (see cloudDown).
    cd.dreoUpdate(flat, connected && !state.cloudDown)
    if (!held) return
    if (!connected) {
        log.warn "Dreo: ${cd.displayName} is offline in Dreo's cloud; ${held*.cmd.join(', ')} not sent"
        return
    }
    // The device is online after all: send what was held, each on top of the last.
    Map s = dreoState(flat)
    held.each { Map h ->
        Map params = sendNow(cd, s, h.cmd as String, h.args as List, h.opts as Map)
        if (params) {
            cd.dreoUpdate(params, null)
            s = s + params
        }
    }
}

// ---------------------------------------------------------------- commands held for offline devices

private Map heldFor(String sn) {
    HELD.putIfAbsent(sn, [cmds: []])
    return HELD.get(sn)
}

/** Hold a command; returns true if it's the first, so the device needs a refresh. */
private boolean hold(String sn, String cmd, List args, Map opts) {
    Map h = heldFor(sn)
    synchronized (h) {
        boolean first = !h.cmds
        if (first) h.at = now()
        if (h.cmds.size() < HELD_MAX) h.cmds << [cmd: cmd, args: args ?: [], opts: opts ?: [:]]
        return first
    }
}

private List takeHeld(String sn) {
    Map h = sn ? HELD.get(sn) : null
    if (h == null) return []
    synchronized (h) {
        List cmds = h.cmds
        h.cmds = []
        return cmds ?: []
    }
}

/** Drop held commands whose refresh never came back. */
def expireHeld(Map data) {
    Map h = HELD.get(data.sn as String)
    if (h == null) return
    List stale = []
    synchronized (h) {
        if (h.cmds && now() - ((h.at ?: 0L) as long) >= HELD_MAX_AGE_MS) {
            stale = h.cmds
            h.cmds = []
        }
    }
    if (stale) log.warn "Dreo: no answer from Dreo for ${fanBySn(data.sn as String)?.displayName ?: data.sn}; ${stale*.cmd.join(', ')} not sent"
}

// ---------------------------------------------------------------- cloud connection health

/**
 * The cloud connection has been down too long (or Dreo rejected the sign-in):
 * show every device offline, so "device offline" alerts fire. wsOpened
 * clears this and the catch-up poll restores each device's real status.
 */
private void cloudDown(String reason) {
    if (state.cloudDown) return
    state.cloudDown = reason
    log.warn "Dreo: cloud connection down (${reason}); showing every device offline until it is back"
    getFanDevices().each { it.dreoOnline(false) }
}

/** Scheduled CLOUD_DOWN_MS after the connection dropped. */
def cloudCheck() {
    if (!state.linkDownAt || getConnection()?.currentValue("connection") == "connected") return
    if (now() - (state.linkDownAt as long) >= CLOUD_DOWN_MS - 1000) cloudDown("not connected for ${(int) (CLOUD_DOWN_MS / 60000)} minutes")
}

// ---------------------------------------------------------------- WebSocket (via the connection device)

void connectSocket(Map data = [:]) {
    def conn = getConnection()
    if (!conn) return
    if (!currentToken()) {
        login("connectSocket", [:])
        return
    }
    String host = REGION_HOST[state.region ?: "NA"] ?: "us"
    String token = URLEncoder.encode(state.token as String, "UTF-8")
    conn.connect("wss://wsb-${host}.dreo-tech.com/websocket?accessToken=${token}&timestamp=${now()}")
}

/** The connection device opened the socket. */
void wsOpened() {
    state.remove("linkDownAt")
    if (state.cloudDown) {
        state.remove("cloudDown")
        log.info "Dreo: cloud connection back; re-reading every device"
    }
    // Catch up on anything missed while disconnected (and set healthStatus from it).
    runIn(5, "pollAll")
}

/** The socket closed or failed; `failures` counts attempts since the last open. */
void wsClosed(String reason, int failures) {
    // Repeated failures may mean the token expired: sign in afresh.
    if (failures >= 3) dropToken()
    int delay = Math.min(60, 5 * (1 << Math.max(0, Math.min(failures - 1, 4))))
    if (state.authFailedAt) delay = (int) (AUTH_RETRY_MS / 1000)
    if (state.authFailedAt) {
        cloudDown("Dreo rejected the sign-in")
    } else if (!state.linkDownAt) {
        state.linkDownAt = now()
        runIn((int) (CLOUD_DOWN_MS / 1000), "cloudCheck")
    } else {
        // Still failing: the scheduled check may have been replaced (e.g. by Done).
        cloudCheck()
    }
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
    if (kindOf(cd.getDataValue("model")) == "humidifier") return humidifierEvents(humidifierProfile(cd), s)
    Map p = fanProfile(cd, s, opts)
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
    if (osc) {
        ev << [name: "oscillation", value: osc]
        // setOscillation's list is fixed for every fan; this says which choices apply here.
        ev << [name: "supportedOscillation", value: JsonOutput.toJson(twoAxis(s) ? OSC_MODES : ["off", "on"])]
    }

    if (p.kind != "ceiling") {
        // fixedconf "vertical,horizontal": where the head points when not oscillating.
        List head = confParts(s.fixedconf, 2)
        if (head) {
            ev << [name: "headVertical", value: head[0], unit: "°"]
            ev << [name: "headHorizontal", value: head[1], unit: "°"]
        }
        // cruiseconf "top,right,bottom,left": the oscillation limits.
        List limits = confParts(s.cruiseconf, 4)
        if (limits) {
            ev << [name: "oscillationTop", value: limits[0], unit: "°"]
            ev << [name: "oscillationRight", value: limits[1], unit: "°"]
            ev << [name: "oscillationBottom", value: limits[2], unit: "°"]
            ev << [name: "oscillationLeft", value: limits[3], unit: "°"]
        }
        String display = displayOf(s)
        if (display) {
            ev << [name: "display", value: display]
            ev << [name: "supportedDisplay", value: JsonOutput.toJson(s.lightmode instanceof Number ? DISPLAY_MODES : ["off", "on"])]
        }
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
    if (cd.currentValue("healthStatus") == "offline") {
        // Dreo doesn't answer for an offline device, and a missing answer would
        // make the connection reconnect. But "offline" may be stale (a missed
        // device-online push): hold the command, re-read the device, and send
        // it from fanStateDone if Dreo says it is online.
        String sn = cd.getDataValue("sn")
        log.info "Dreo: ${cd.displayName} shows offline; checking with Dreo before sending ${cmd}"
        if (hold(sn, cmd, args, opts)) {
            refreshFan(sn)
            runIn((int) (HELD_MAX_AGE_MS / 1000) + 1, "expireHeld", [data: [sn: sn], overwrite: false])
        }
        return null
    }
    return sendNow(cd, s, cmd, args, opts)
}

private Map sendNow(cd, Map s, String cmd, List args, Map opts) {
    boolean humidifier = kindOf(cd.getDataValue("model")) == "humidifier"
    Map p = humidifier ? humidifierProfile(cd) : fanProfile(cd, s, opts)
    Map params
    try {
        params = humidifier ? humidifierCommand(p, s, cmd, args ?: []) : buildCommand(p, s, cmd, args ?: [], opts)
    } catch (IllegalArgumentException e) {
        log.warn "Dreo: ${cd.displayName}: ${e.message}"
        return null
    } catch (ClassCastException e) {
        // e.g. a rule passing an empty value where a number is expected.
        log.warn "Dreo: ${cd.displayName}: ${cmd} needs a value, got ${args}"
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
    String sn = cd.getDataValue("sn")
    if (params.containsKey("fixedconf") && p.settle && !headReady(sn, p.settle as int, params)) return params
    conn.sendCommand(sn, params)
    return params
}

private Map head(String sn) {
    HEAD.putIfAbsent(sn, [:])
    return HEAD.get(sn)
}

/**
 * Whether a head position can go now. If the head may still be moving, keep
 * only the newest position and send it once the settle time is up.
 */
private boolean headReady(String sn, int settle, Map params) {
    Map h = head(sn)
    long wait
    synchronized (h) {
        wait = ((h.at ?: 0L) as long) + settle * 1000L - now()
        if (wait <= 0 && !h.pending) {
            h.at = now()
            return true
        }
        h.pending = params
    }
    runInMillis(Math.max(wait, 0L), "flushHead", [data: [sn: sn, settle: settle], overwrite: false])
    logDebug "head position for ${sn} waits ${wait} ms for the head to settle"
    return false
}

def flushHead(Map data) {
    Map h = head(data.sn as String)
    Map params
    long wait
    synchronized (h) {
        if (!h.pending) return
        wait = ((h.at ?: 0L) as long) + (data.settle as int) * 1000L - now()
        if (wait <= 0) {
            params = h.pending
            h.remove("pending")
            h.at = now()
        }
    }
    if (wait > 0) {
        runInMillis(wait, "flushHead", [data: data, overwrite: false])
        return
    }
    getConnection()?.sendCommand(data.sn as String, params)
}

// ---------------------------------------------------------------- pure mapping logic

private String kindOf(String model) {
    String prefix = FAMILIES.keySet().find { model?.startsWith(it) }
    return prefix ? FAMILIES[prefix].kind : null
}

/** A fan device's profile, with the speed range and modes read from its controlsConf at discovery. */
private Map fanProfile(cd, Map s, Map opts) {
    return profile(cd.getDataValue("model"), s, opts,
        [speedRange: cd.getDataValue("speedRange"), modes: cd.getDataValue("modes")])
}

/**
 * What we know about this fan: its controlsConf data (`conf`, as fanLimits
 * stores it), else model and family data, adjusted by the state keys it reports.
 */
private Map profile(String model, Map s, Map opts, Map conf = [:]) {
    String prefix = FAMILIES.keySet().find { model?.startsWith(it) }
    Map fam = prefix ? FAMILIES[prefix] : FAMILIES["DR-HTF"]
    Map m = MODELS[model] ?: [:]
    String kind = fam.kind
    List speed = (m.speedFixed ? null : rangeOf(conf?.speedRange as String, null)) ?: m.speed ?: fam.speed
    List modes = modesOf(conf?.modes as String) ?: MODE_LISTS[m.modes ?: fam.modes]
    int min = speed[0]
    int max = speed[1]
    int override = (opts?.speedSteps ?: 0) as int
    if (override > 0) max = min + override - 1
    // A fan reporting a higher step than we expected knows better.
    if (s.windlevel instanceof Number && (s.windlevel as int) > max) max = s.windlevel as int

    String powerKey = (kind == "ceiling" && s.containsKey("fanon")) ? "fanon"
        : s.containsKey("poweron") ? "poweron" : s.containsKey("fanon") ? "fanon" : "poweron"
    String modeKey = s.containsKey("mode") ? "mode" : s.containsKey("windtype") ? "windtype" : "mode"
    // Head angle ranges, widened to any angle the fan itself reports.
    List h = [] + (m.h ?: fam.h ?: ANGLES_H)
    List v = [] + (m.v ?: fam.v ?: ANGLES_V)
    List head = confParts(s.fixedconf, 2)
    List osc = confParts(s.cruiseconf, 4)
    [head ? head[1] : null, osc ? osc[1] : null, osc ? osc[3] : null].findAll { it != null }.each { int a ->
        h = [Math.min(h[0] as int, a), Math.max(h[1] as int, a)]
    }
    [head ? head[0] : null, osc ? osc[0] : null, osc ? osc[2] : null].findAll { it != null }.each { int a ->
        v = [Math.min(v[0] as int, a), Math.max(v[1] as int, a)]
    }
    return [
        kind: kind, min: min, max: max, steps: max - min + 1,
        modes: modes, powerKey: powerKey, modeKey: modeKey,
        ring: m.ring ?: [1, 5], h: h, v: v, settle: m.settle ?: 0,
    ]
}

/**
 * Speed range and modes from a fan's controlsConf in the device list, as
 * device data values: speedRange "1-9", modes "normal=1,natural=2,...". Many
 * models get only a template reference ({template: ...}); then nothing is
 * returned and the model table applies. Per hass-dreo's parse_speed_range and
 * parse_preset_modes, except that the main control comes before extraConfigs.
 * Modes: the mode buttons (Mode, or CFFan on ceiling fans). Schedules can list
 * more (turbo 5 and custom 6 on the test DR-HPF004S, which ignores both), so
 * they count only when there are no mode buttons.
 */
Map fanLimits(def conf) {
    Map out = [:]
    if (!(conf instanceof Map)) return out
    List controls = (conf.control instanceof List ? conf.control : []).findAll { it instanceof Map && it.items instanceof List }
    // extraConfigs controls can hold per-firmware variants (e.g. a DR-HTF005S
    // MCU with 12 steps where the control says 9), so they count only when the
    // main control has no Speed. A fan reporting a higher step widens the range anyway.
    List extra = []
    (conf.extraConfigs instanceof List ? conf.extraConfigs : []).each { x ->
        if (x instanceof Map && x.key == "control" && x.value instanceof List) extra.addAll(x.value)
    }
    def speedControl = controls.find { it.type == "Speed" } ?:
        extra.find { it instanceof Map && it.items instanceof List && it.type == "Speed" }
    if (speedControl) {
        List steps = speedControl.items.findAll { it instanceof Map && "${it.value}" ==~ /\d+/ }.collect { "${it.value}" as int }
        if (steps.size() >= 2 && steps.min() < steps.max()) out.speedRange = "${steps.min()}-${steps.max()}".toString()
    }
    List modes = []
    Closure add = { def text, def value ->
        String name = MODE_TEXT[text] ?: text?.toString()
        if (name && "${value}" ==~ /\d+/ && !modes.any { it[0] == name || it[1] == ("${value}" as int) }) {
            modes << [name, "${value}" as int]
        }
    }
    // Ceiling fans list their modes under CFFan.
    controls.findAll { it.type in ["Mode", "CFFan"] }.each { c -> c.items.each { if (it instanceof Map) add(it.text, it.value) } }
    if (!modes && conf.schedule instanceof Map && conf.schedule.modes instanceof List) {
        conf.schedule.modes.each { if (it instanceof Map) add(it.title, it.value) }
    }
    if (modes) out.modes = modes.sort { it[1] }.collect { "${it[0]}=${it[1]}" }.join(",")
    return out
}

/** Modes as fanLimits stores them, as [id, value] pairs; null if absent or garbled. */
List modesOf(String v) {
    if (!v) return null
    List out = v.split(",").collect { String pair ->
        def m = pair =~ /^([^=]+)=(\d+)$/
        m.matches() ? [m.group(1), m.group(2) as int] : null
    }
    return out.every { it != null } ? out : null
}

/** The first `n` numbers of a "a,b,..." Dreo value, or null if it has fewer. */
List confParts(def conf, int n) {
    if (!(conf instanceof String)) return null
    List parts = conf.split(",").collect { it.trim() }
    if (parts.size() < n || !parts.take(n).every { it ==~ /-?\d+/ }) return null
    return parts.take(n).collect { it as int }
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

/** A percentage as the nearest step the fan has. */
int stepForPercent(Map p, def pct) {
    return clamp((pct as double) * p.max / 100d, p.min, p.max)
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
            int level = intArg(a, "speed")
            if (level <= 0) return power(false)
            // The dropdown offers 1-12 for every fan: a number above this fan's
            // top speed uses its top one, as with Govee fans.
            return wake(speedParams(p, s, Math.max(p.min as int, Math.min(p.max as int, level))))
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
            if (named >= 0) return wake(speedParams(p, s, levelForSpeed(named, p.min, p.steps)))
            // A standard name this fan doesn't list (e.g. "medium" on a 4-step fan,
            // from Hubitat's fixed speed list or a dashboard): the nearest step to
            // that name's share of the five standard speeds.
            int std = SPEED_NAMES.indexOf(speed)
            if (std < 0) throw new IllegalArgumentException("unsupported speed ${speed}")
            return wake(speedParams(p, s, stepForPercent(p, (std + 1) * 20)))
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
        case "setHeadPosition":
            List head = confParts(s.fixedconf, 2)
            if (!head || p.kind == "ceiling") throw new IllegalArgumentException("this fan has no head position setting")
            int vert = args.size() > 0 && args[0] != null && args[0] != "" ? clamp(intArg(args[0], "vertical angle"), p.v[0], p.v[1]) : head[0]
            int horiz = args.size() > 1 && args[1] != null && args[1] != "" ? clamp(intArg(args[1], "horizontal angle"), p.h[0], p.h[1]) : head[1]
            Map params = [fixedconf: "${vert},${horiz}".toString()]
            // A fixed position means not oscillating: the head would otherwise keep sweeping.
            String osc = oscillationOf(s)
            if (osc && osc != "off") params += oscillationParams(s, "off")
            return params
        case "setOscillationRange":
            List osc = confParts(s.cruiseconf, 4)
            if (!osc || p.kind == "ceiling") throw new IllegalArgumentException("this fan has no oscillation range setting")
            // Arguments: left, right, bottom, top; an empty one keeps that limit.
            Closure<Integer> arg = { int i, int cur, List range, String what ->
                args.size() > i && args[i] != null && args[i] != "" ? clamp(intArg(args[i], what), range[0], range[1]) : cur
            }
            int left = arg(0, osc[3], p.h, "left angle")
            int right = arg(1, osc[1], p.h, "right angle")
            int bottom = arg(2, osc[2], p.v, "bottom angle")
            int top = arg(3, osc[0], p.v, "top angle")
            if (right - left < MIN_OSC_SPAN) throw new IllegalArgumentException("left and right must be at least ${MIN_OSC_SPAN}° apart")
            if (top - bottom < MIN_OSC_SPAN) throw new IllegalArgumentException("bottom and top must be at least ${MIN_OSC_SPAN}° apart")
            return [cruiseconf: "${top},${right},${bottom},${left}".toString()]
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

private boolean twoAxis(Map s) {
    return s.containsKey("oscmode") || (s.containsKey("hoscon") && s.containsKey("voscon"))
}

private Map oscillationParams(Map s, String v) {
    if (twoAxis(s)) {
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

// ---------------------------------------------------------------- humidifiers
// Per hass-dreo's PyDreoHumidifier and the DR-HHM001S (HM311S) device list
// and state samples in its tests.

/**
 * Target-humidity and mist-level ranges from a humidifier's controlsConf in
 * the device list, as device data values ("30-80"). Missing ones are left out.
 */
Map humidifierLimits(def conf) {
    Map out = [:]
    if (!(conf instanceof Map)) return out
    def hr = conf.humRange
    if (hr instanceof Map && hr.controlMin instanceof Number && hr.controlMax instanceof Number) {
        out.humidityRange = "${hr.controlMin}-${hr.controlMax}".toString()
    }
    def modes = conf.schedule instanceof Map ? conf.schedule.modes : null
    (modes instanceof List ? modes : []).each { m ->
        (m instanceof Map && m.controls instanceof List ? m.controls : []).each { c ->
            if (!(c instanceof Map) || !(c.endValue instanceof Number)) return
            List cmds = c.cmd instanceof List ? c.cmd : [c.cmd]
            if ("rhautolevel" in cmds && !out.humidityRange && c.startValue instanceof Number) {
                out.humidityRange = "${c.startValue}-${c.endValue}".toString()
            }
            if ("foglevel" in cmds) {
                // startValue 0 is "off"; validValue is the lowest real level.
                def lo = c.validValue instanceof Number ? c.validValue : c.startValue instanceof Number ? c.startValue : 1
                out.mistRange = "${Math.max(1, lo as int)}-${c.endValue}".toString()
            }
        }
    }
    return out
}

private List rangeOf(String v, List dflt) {
    if (!v) return dflt
    def m = v =~ /^(\d+)-(\d+)$/
    return m.matches() ? [m.group(1) as int, m.group(2) as int] : dflt
}

Map humidifierProfile(cd) {
    return [
        kind: "humidifier", modes: MODE_LISTS.humidifier, modeKey: "mode", powerKey: "poweron",
        target: rangeOf(cd.getDataValue("humidityRange"), HUM_TARGET_RANGE),
        mist: rangeOf(cd.getDataValue("mistRange"), HUM_MIST_RANGE),
    ]
}

/** The target that applies now: sleep mode has its own (rhsleeplevel) where the model reports one. */
private String targetKey(Map p, Map s) {
    return modeOf(p, s) == "sleep" && s.containsKey("rhsleeplevel") ? "rhsleeplevel" : "rhautolevel"
}

List humidifierEvents(Map p, Map s) {
    List ev = []
    Boolean on = s.containsKey("poweron") ? s.poweron as boolean : null
    String modeId = modeOf(p, s)
    if (on != null) ev << [name: "switch", value: on ? "on" : "off"]
    if (s.rh instanceof Number) ev << [name: "humidity", value: s.rh as int, unit: "%"]
    def target = s[targetKey(p, s)]
    if (target instanceof Number) ev << [name: "targetHumidity", value: target as int, unit: "%"]
    ev << [name: "supportedModes", value: JsonOutput.toJson(p.modes.collect { it[0] })]
    if (modeId) ev << [name: "mode", value: modeId]
    if (s.foglevel instanceof Number) ev << [name: "mistLevel", value: s.foglevel as int]
    int mistMax = Math.max(p.mist[1] as int, s.foglevel instanceof Number ? s.foglevel as int : 0)
    ev << [name: "maxMistLevel", value: mistMax]
    if (on != null) {
        // suspend: the target is reached and the mist is paused.
        ev << [name: "operatingState", value: !on ? "off" : s.suspend == true ? "idle" : "humidifying"]
    }
    if (s.wrong instanceof Number) {
        int w = s.wrong as int
        ev << [name: "waterTank", value: w == 0 ? "ok" : w in HUM_TANK_EMPTY ? "empty" : "fault ${w}".toString()]
    }
    if (s.waterlevel instanceof Number) ev << [name: "waterLevel", value: s.waterlevel as int, unit: "%"]
    // hass-dreo's "Use since cleaning" (hours) and "Filter Life" (%).
    if (s.worktime instanceof Number) ev << [name: "useSinceCleaning", value: s.worktime as int, unit: "h"]
    if (s.filtertime instanceof Number) ev << [name: "filterLife", value: s.filtertime as int, unit: "%"]
    if (s.ledlevel instanceof Number) ev << [name: "display", value: (s.ledlevel as int) > 0 ? "on" : "off"]
    if (s.containsKey("muteon")) ev << [name: "panelSound", value: s.muteon ? "off" : "on"]
    if (s.containsKey("ambient_switch")) ev << [name: "ambientLight", value: s.ambient_switch ? "on" : "off"]
    else if (s.rgblevel instanceof Number) ev << [name: "ambientLight", value: (s.rgblevel as int) > 0 ? "on" : "off"]
    return ev
}

private int intArg(def v, String what) {
    try {
        return Math.round(v as double) as int
    } catch (e) {
        throw new IllegalArgumentException("${what} must be a number, not ${v}")
    }
}

/** Params for a humidifier command. Throws IllegalArgumentException for bad input. */
Map humidifierCommand(Map p, Map s, String cmd, List args) {
    def a = args ? args[0] : null
    boolean on = s.poweron as boolean
    // Mode, target or mist changes on a humidifier that is off also switch it on.
    Closure<Map> wake = { Map m -> on ? m : [poweron: true] + m }
    String modeId = modeOf(p, s)
    Closure<Integer> modeValue = { String id -> p.modes.find { it[0] == id }[1] as int }

    switch (cmd) {
        case "on": return [poweron: true]
        case "off": return [poweron: false]
        case "setMode":
            def mode = p.modes.find { it[0] == a?.toString() }
            if (!mode) throw new IllegalArgumentException("unsupported mode ${a}; this humidifier has ${p.modes.collect { it[0] }}")
            return wake([mode: mode[1]])
        case "setTargetHumidity":
            int t = intArg(a, "target humidity")
            if (t < p.target[0] || t > p.target[1]) throw new IllegalArgumentException("target humidity must be ${p.target[0]}-${p.target[1]} %")
            Map params = [(targetKey(p, s)): t]
            // A target only applies in auto or sleep; from manual, go to auto.
            if (modeId == "manual") params.mode = modeValue("auto")
            return wake(params)
        case "setMistLevel":
            int level = intArg(a, "mist level")
            if (level <= 0) return [poweron: false]
            if (level < p.mist[0] || level > p.mist[1]) throw new IllegalArgumentException("mist level must be ${p.mist[0]}-${p.mist[1]}")
            Map params = [foglevel: level]
            // A fixed mist level means manual control.
            if (modeId && modeId != "manual") params.mode = modeValue("manual")
            return wake(params)
        case "setDisplay":
            if (!s.containsKey("ledlevel")) throw new IllegalArgumentException("this humidifier has no display setting")
            return [ledlevel: onOffArg(a, "display") ? 2 : 0]
        case "setPanelSound":
            if (!s.containsKey("muteon")) throw new IllegalArgumentException("this humidifier has no panel sound setting")
            return [muteon: !onOffArg(a, "panel sound")]
        case "setAmbientLight":
            boolean lit = onOffArg(a, "ambient light")
            if (s.containsKey("ambient_switch")) return [ambient_switch: lit]
            if (s.containsKey("rgblevel")) return [rgblevel: lit ? 2 : 0]
            throw new IllegalArgumentException("this humidifier has no ambient light")
    }
    throw new IllegalArgumentException("unknown command ${cmd}")
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "Dreo: ${msg}"
}
