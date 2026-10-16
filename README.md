# Dreo Connect for Hubitat

Control Dreo cloud fans from a Hubitat Elevation hub: tower fans, air
circulators and ceiling fans, including ceiling lights and light rings.

> **Status: v0.1, not yet tested on a hub.** The Dreo protocol is the one
> verified on real fans in the author's Homey Dreo app (itself a port of
> [hass-dreo](https://github.com/JeffSteinbok/hass-dreo)), but this Hubitat
> code has not run yet. See
> [What needs testing](#what-needs-testing).

## How it works

- **Dreo Connect** (app) holds your Dreo account, signs in, lists the fans on
  the account and creates one device per fan you pick. All Dreo-specific
  mapping (speeds, modes, oscillation, lights, temperature) lives in the app.
- **Dreo Cloud Connection** (device) holds the single WebSocket to Dreo's
  cloud. Hubitat only lets drivers open WebSockets, which is why this is a
  device. It receives live state from every fan and sends commands one at a
  time, waiting for Dreo's acknowledgement.
- **Dreo Fan** (tower fans, air circulators) and **Dreo Ceiling Fan** devices.
- **Dreo Ceiling Light** and **Dreo Light Ring** child devices, created
  automatically when a fan reports a light or a light ring, so dashboards,
  Room Lighting and voice assistants see them as ordinary lights.

State arrives over the WebSocket within a second or so. Each fan is also
re-read over REST every 30 minutes and after every reconnect, as a safety net.
The app ships a small table of speed ranges and modes per model rather than
downloading Dreo's 700 KB app configuration on the hub.

## Install

### Hubitat Package Manager

Not published yet. Once the repository is on GitHub, `packageManifest.json`
needs its `GITHUB_OWNER` placeholders replaced.

### Manually

1. **Drivers Code → New Driver**: paste and save each file in `drivers/`:
   `dreo-cloud-connection.groovy`, `dreo-fan.groovy`,
   `dreo-ceiling-fan.groovy`, `dreo-ceiling-light.groovy`,
   `dreo-light-ring.groovy`.
2. **Apps Code → New App**: paste and save `apps/dreo-connect.groovy`.
3. **Apps → Add User App → Dreo Connect**. Enter your Dreo email and
   password, open **Choose fans**, tick the fans you want, then press **Done**.

Only an MD5 hash of the password is stored (that is what Dreo's sign-in
expects); the password field is cleared once it is hashed. North American and
European accounts are both supported; the region is picked up at sign-in.

## Devices

### Dreo Fan (tower fans, air circulators)

| Hubitat | Dreo |
| --- | --- |
| Switch | `poweron` |
| FanControl `setSpeed(low … high / on / off / auto)` | `windlevel`; named speeds are spread evenly over the fan's steps. `auto` selects the fan's Auto mode. |
| `setSpeedLevel(n)`, `speedLevel`, `maxSpeedLevel` | the exact step, 1..N |
| `setMode(mode)`, `mode`, `supportedModes` | `mode` / `windtype`: normal, natural, sleep, auto, turbo, custom (per model) |
| `setOscillation(off/on/horizontal/vertical/both)`, `oscillation` | `shakehorizon` / `oscon` / `hoscon`, or `oscmode` / `hoscon`+`voscon` on two-axis fans |
| `setDisplay(on/off/auto)`, `display` | `ledkepton` / `ledalwayson` / `lighton`, or `lightmode` |
| `setPanelSound(on/off)`, `panelSound` | `voiceon` / `muteon` |
| `setChildLock(on/off)`, `childLock` | `childlockon` |
| TemperatureMeasurement | `temperature` + `tempoffset`, in the unit set by `tempunit` (1 = °F), shown in the hub's scale |
| `healthStatus` | fan online/offline |

Named speeds: fans with 5+ steps use low, medium-low, medium, medium-high and
high (for 9 steps: 2, 4, 5, 7, 9). Fans with fewer steps use a subset (for 4
steps: low, medium-low, medium-high, high = 1, 2, 3, 4). Setting a named speed
or a step while in Auto mode switches the fan to Normal.

If a model's speed range is wrong, set **Number of speed steps** in the fan's
preferences. A fan reporting a higher step than expected also widens the range
by itself.

### Dreo Ceiling Fan

Switch and FanControl drive the fan motor (`fanon`), with modes normal,
natural, sleep and reverse. The main light becomes a **Dreo Ceiling Light**
child (Switch, SwitchLevel, ColorTemperature 2700–6500 K). Ceiling fans put
every load behind a `poweron` gate; the app adds the gate keys to each command
the way the Dreo app does.

### Dreo Light Ring

Switch, SwitchLevel (mapped to the ring's brightness steps, e.g. 1–3 on the
DR-HPF008S), ColorControl (`atmcolor`, 24-bit RGB) and LightEffects
(1 constant, 2 circle, 3 breath). Changing colour, brightness or effect on a
dark ring turns it on, like the Dreo app.

## Limitations

- Needs Dreo's cloud; there is no local control.
- Not in v1: oscillation angles and sweep width, head position, on/off timers,
  schedules, misting, temperature calibration offset, air purifiers, heaters
  and other Dreo devices.
- Models not in the built-in table use their family's defaults (towers and
  circulators 1–9 with normal/natural/sleep/auto; ceiling fans 1–12 with
  normal/natural/sleep/reverse).
- Fan state is kept on the fan device. Two reports arriving at exactly the
  same moment can, rarely, overwrite each other; the next report or the
  30-minute refresh corrects it.

## What needs testing

Everything below is written but has never run on a hub:

1. App and drivers save without compile errors in Apps Code / Drivers Code.
2. Password hashing: the password field clears and sign-in works (MD5 via
   `java.security.MessageDigest`).
3. Async sign-in, the EU region redirect, and the 30-minute back-off after a
   wrong password.
4. **Choose fans** page: the async discovery with page auto-refresh, rooms shown
   next to names, and whether it works before the app's first **Done**.
5. Child device creation and removal when fans are ticked or unticked.
6. WebSocket: connect with `pingInterval`, the "2" keepalive, status
   handling, reconnect back-off, reconnect after a hub reboot (Initialize).
7. Command queue: acknowledgements, retries, and recovery after a silent dead
   link.
8. Per fan (DR-HAF001S, DR-HTF004S, DR-HPF008S, DR-HPF004S): switch, named
   speeds, `setSpeedLevel`, modes, oscillation, display, panel sound, child
   lock, temperature in °F and °C hub scales. The DR-HTF004S is not in
   hass-dreo's table, so its speed range is a guess (1–9).
9. Light ring on the DR-HPF008S: on/off, level 1–3, colour round-trip, effects.
10. Ceiling fan (when it arrives): power gate, light child, colour temperature.

## Credits and licence

MIT License, Copyright (c) 2026 Digital Homesteaders; see [LICENSE](LICENSE).

The Dreo protocol, model data and feature mappings are ported from
[hass-dreo](https://github.com/JeffSteinbok/hass-dreo) by Jeff Steinbok and
contributors (MIT License; notice included in LICENSE). Not affiliated with
or endorsed by Dreo.
