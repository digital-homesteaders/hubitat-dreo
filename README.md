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
| SwitchLevel `setLevel(%)`, `level` | `windlevel` as a percentage of the top step, like Homey's fan speed: step 5 of 9 is 56 %; a percentage picks the nearest step; 0 % turns the fan off |
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

A percentage you set stays as you set it while the fan is on the matching
step (50 % on a 9-step fan stays 50 %, not 56 %). Because fans have
SwitchLevel, some apps and voice assistants may offer them as dimmers; pick
the fan device type where the integration asks.

If a model's speed range is wrong, set **Number of speed steps** in the fan's
preferences. A fan reporting a higher step than expected also widens the range
by itself.

### Dreo Ceiling Fan

Switch, FanControl and SwitchLevel (speed as a percentage) drive the fan motor (`fanon`), with modes normal,
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

### Covered locally (no hub needed)

`tests/run.sh` compiles the app and every driver with Groovy 2.4 (the version
Hubitat runs) against small stand-ins for the hub, then runs:

- **CompileCheck**: every file compiles and its `definition`/`metadata`/
  `preferences` code runs; no method shadows a metadata word such as
  `command`; every method a driver calls on its parent (and the app calls on
  its child devices) exists and is not private.
- **MappingTest**: named speeds for 4, 8, 9, 10 and 12 steps; every command's
  Dreo params (speeds incl. auto and leaving auto, step bounds, cycling, modes,
  single- and two-axis oscillation, display incl. `lightmode`, panel sound via
  `voiceon`/`muteon`, child lock, ceiling light, light ring); the ceiling-fan
  power gate (cases from hass-dreo's `PyDreoCeilingFan`); temperature with
  `tempunit` 0/1, the >50 rule, offsets and °F/°C hubs; RGB/hue round-trips;
  and the events for sample DR-HAF001S, DR-HPF008S (with light ring) and
  DR-HCF ceiling fan states.
- **DriverFlowTest**: the app and real drivers together, with state passed
  through JSON as on a hub: discovery and rooms, child creation and removal,
  WebSocket open/reports/acks, the one-at-a-time command queue, retries and
  reconnect after a missing ack, light ring and ceiling light children, the
  gate on a real command, online/offline, REST refresh and the speed-step
  preference.

To run them, get a JDK and Groovy 2.4 once (downloaded into the git-ignored
`.tools/`; nothing is installed system-wide; or have `java` and `groovy` on
your PATH), then run the tests:

```bash
tests/setup-tools.sh
```

```bash
tests/run.sh
```

The stand-ins only model the hub as far as these files use it, so the hub
itself still needs checking.

### Still needs a hub

1. App and drivers save in Apps Code / Drivers Code (the local compile uses
   the same Groovy, but not Hubitat's sandbox or its class whitelist).
2. Password hashing: the password field clears and sign-in works (MD5 via
   `java.security.MessageDigest`).
3. Async sign-in against Dreo, the EU region redirect, and the 30-minute
   back-off after a wrong password.
4. **Choose fans** page: the page auto-refresh during discovery, how it looks,
   and whether it works before the app's first **Done**.
5. Real WebSocket behaviour: `pingInterval`, the status strings Hubitat
   actually sends, reconnect after a hub reboot (Initialize), and whether Dreo
   acknowledges commands as expected.
6. Per fan (DR-HAF001S, DR-HTF004S, DR-HPF008S, DR-HPF004S): that the real
   state keys match the samples in the tests, and the fan does what the params
   say. The DR-HTF004S is not in hass-dreo's table, so its speed range is a
   guess (1–9).
7. Light ring on the DR-HPF008S on real hardware: brightness steps, colours,
   effects.
8. Ceiling fan (when it arrives): the power gate and light child on the real
   fan.

## Credits and licence

MIT License, Copyright (c) 2026 Digital Homesteaders; see [LICENSE](LICENSE).

The Dreo protocol, model data and feature mappings are ported from
[hass-dreo](https://github.com/JeffSteinbok/hass-dreo) by Jeff Steinbok and
contributors (MIT License; notice included in LICENSE). Not affiliated with
or endorsed by Dreo.
