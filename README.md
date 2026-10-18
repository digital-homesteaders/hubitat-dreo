# Dreo Connect for Hubitat

Control Dreo cloud fans and humidifiers from a Hubitat Elevation hub: tower
fans, air circulators and ceiling fans, including ceiling lights and light
rings, and DR-HHM humidifiers.

> **Status: v0.1, early testing on a Hubitat C-8.** Installing, signing in,
> discovery and device creation work, and on a DR-HPF008S live updates and
> commands from Hubitat (power, speeds, modes, display, temperature) behave as
> expected. The other features follow the protocol verified in the author's
> Homey Dreo app and in
> [hass-dreo](https://github.com/JeffSteinbok/hass-dreo), but haven't been
> checked on a hub yet. See [What needs testing](#what-needs-testing).

## How it works

- **Dreo Connect** (app) holds your Dreo account, signs in, lists the fans and
  humidifiers on the account and creates one device for each one you pick. All Dreo-specific
  mapping (speeds, modes, oscillation, lights, temperature) lives in the app.
- **Dreo Cloud Connection** (device) holds the single WebSocket to Dreo's
  cloud. Hubitat only lets drivers open WebSockets, which is why this is a
  device. It receives live state from every fan and sends commands one at a
  time, waiting for Dreo's acknowledgement. Leave it in place: without it
  nothing updates or responds (pressing **Done** in the app adds it back).
- **Dreo Fan** (tower fans, air circulators), **Dreo Ceiling Fan** and
  **Dreo Humidifier** devices.
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
   `dreo-light-ring.groovy`, `dreo-humidifier.groovy`.
2. **Apps Code → New App**: paste and save `apps/dreo-connect.groovy`.
3. **Apps → Add User App → Dreo Connect**. Enter your Dreo email and
   password, open **Choose devices**, tick the fans and humidifiers you want,
   then press **Done**.

If a device isn't mapped yet or shows the wrong thing, press **Log raw Dreo
data** in the app (under Logging): it writes each device's raw device-list
entry and full state to **Logs**. Only model data is kept: no owner, home,
room or device names, MAC or IP addresses, Wi-Fi name, or full serial numbers
(the last four characters tell devices apart), so the log is safe to paste
into a forum post.

Only an MD5 hash of the password is stored (that is what Dreo's sign-in
expects); the password field is cleared once it is hashed. The hash signs in
to Dreo just like the password would, and it shows on the app's status page
and in hub backups, so keep those private.

**Choose devices** lists the account's devices each time it opens (at most
once a minute); **Look again** on that page lists them again straight away,
for example after adding a device in the Dreo app. North American and
European accounts are both supported; the region is picked up at sign-in.

## Devices

### Dreo Fan (tower fans, air circulators)

| Hubitat | Dreo |
| --- | --- |
| Switch | `poweron` |
| FanControl `setSpeed(low … high / on / off / auto)` | `windlevel`; named speeds are spread evenly over the fan's steps. `auto` selects the fan's Auto mode. |
| `setSpeedLevel(1 … 12)`, `speedLevel`, `maxSpeedLevel` | `windlevel`, the speed number as in the Dreo app. The dropdown offers 1–12 for every fan; a number above the fan's top speed (`maxSpeedLevel`) uses the top speed, and 0 (from a rule) turns the fan off. |
| `setMode(mode)`, `mode`, `supportedModes` | `mode` / `windtype`: normal, natural, sleep, auto, turbo, custom (per model) |
| `setOscillation(off/on/horizontal/vertical/both)`, `oscillation`, `supportedOscillation` | `shakehorizon` / `oscon` / `hoscon`, or `oscmode` / `hoscon`+`voscon` on two-axis fans |
| `setDisplay(on/off/auto)`, `display`, `supportedDisplay` | `ledkepton` / `ledalwayson` / `lighton`, or `lightmode` |
| `setPanelSound(on/off)`, `panelSound` | `voiceon` / `muteon` |
| `setChildLock(on/off)`, `childLock` | `childlockon` |
| TemperatureMeasurement | `temperature` + `tempoffset`, in the unit set by `tempunit` (1 = °F), shown in the hub's scale |
| `setHeadPosition(vertical, horizontal)`, `headVertical`, `headHorizontal` | `fixedconf` ("vertical,horizontal"): where the head points when not oscillating, in degrees. Leave one empty to keep it; an oscillating fan stops oscillating. Fans that report it (e.g. DR-HPF004S, DR-HPF008S). |
| `setOscillationRange(left, right, bottom, top)`, `oscillationLeft` … `oscillationTop` | `cruiseconf` ("top,right,bottom,left"): the oscillation limits, at least 30° apart on each axis; empty keeps a limit |
| `savePosition(slot, name)`, `goToPosition(slot)`, `deletePosition(slot)`, `savedPositions` | Up to five head positions kept on the Hubitat device (Dreo's cloud has no saved positions). `goToPosition` also takes a saved name, for rules. |
| `healthStatus` | fan online/offline |

Angles are clamped to the head's range: hass-dreo's per-model ranges (for
example DR-HPF004S ±75° across, −30° to 90° up), widened to any angle the fan
reports. The DR-HPF017S and DR-HAF004S need about 8 s between head moves, so a
newer position sent sooner waits and only the latest one is sent.

Named speeds: fans with 5+ steps use low, medium-low, medium, medium-high and
high (for 9 steps: 2, 4, 5, 7, 9). Fans with fewer steps use a subset (for 4
steps: low, medium-low, medium-high, high = 1, 2, 3, 4); `supportedFanSpeeds`
lists them. A standard name the fan doesn't list, such as `medium` from
Hubitat's own speed list or a dashboard tile, picks the nearest step (on 4
steps, `medium` is step 2). Setting a named speed or a step while in Auto mode
switches the fan to Normal.

Hubitat gives every fan the same choices in the device page's command lists
(modes, oscillation, display), because a driver can't vary them per device.
The `supportedModes`, `supportedOscillation` and `supportedDisplay`
attributes show which ones this fan has; any other choice is ignored with a
warning in **Logs**. Panel sound and child lock only work on fans that show a
`panelSound` or `childLock` attribute.

Fans have no percentage level (SwitchLevel), as with the Govee integration:
speed is the Dreo app's number, so voice assistants and dashboards see a fan,
not a dimmer.

If a model's speed range is wrong, set **Number of speed steps** in the fan's
preferences. A fan reporting a higher step than expected also widens the range
by itself.

### Dreo Ceiling Fan

Switch, FanControl and the 1–12 speed number drive the fan motor (`fanon`), with modes normal,
natural, sleep and reverse. The main light becomes a **Dreo Ceiling Light**
child (Switch, SwitchLevel, ColorTemperature 2700–6500 K). Ceiling fans put
every load behind a `poweron` gate; the app adds the gate keys to each command
the way the Dreo app does.

### Dreo Light Ring

Switch, SwitchLevel (mapped to the ring's brightness steps, e.g. 1–3 on the
DR-HPF008S), ColorControl (`atmcolor`, 24-bit RGB) and LightEffects
(1 constant, 2 circle, 3 breath). Changing colour, brightness or effect on a
dark ring turns it on, like the Dreo app.

### Dreo Humidifier (DR-HHM)

Mapped from hass-dreo's `PyDreoHumidifier` and its DR-HHM001S (HM311S)
samples.

| Hubitat | Dreo |
| --- | --- |
| Switch | `poweron` |
| RelativeHumidityMeasurement `humidity` | `rh` |
| `setTargetHumidity(%)`, `targetHumidity` | `rhautolevel`, or `rhsleeplevel` in Sleep mode. Setting a target in Manual mode switches to Auto. |
| `setMode(manual/auto/sleep)`, `mode`, `supportedModes` | `mode` 0 / 1 / 2 |
| `setMistLevel(n)`, `mistLevel`, `maxMistLevel` | `foglevel`, 1..N; switches to Manual mode; 0 turns it off |
| `operatingState` | off, humidifying, or idle (`suspend`: target reached, mist paused) |
| `waterTank` | `wrong`: ok, empty (1 or 4), or "fault n" for other codes |
| `waterLevel` | `waterlevel` %, on newer models that report it |
| `useSinceCleaning` | `worktime`, hours since the tank was last cleaned (per hass-dreo) |
| `filterLife` | `filtertime`, filter life % (per hass-dreo), on models that report it |
| `setDisplay(on/off)`, `display` | `ledlevel` (0 off, 2 on) |
| `setPanelSound(on/off)`, `panelSound` | `muteon` |
| `setAmbientLight(on/off)`, `ambientLight` | `ambient_switch`, or `rgblevel` (0 off, 2 on) |
| `healthStatus` | online/offline |

The target-humidity and mist ranges come from the device list Dreo sends at
discovery (30–80 % and mist 1–6 on the HM311S), falling back to 30–90 % and
1–6. Mode, target or mist changes on a humidifier that is off also switch it on.
Hubitat has no humidifier capability, so rules use the custom commands and
attributes.

## Limitations

- Needs Dreo's cloud; there is no local control.
- Not in v1: preset sweep widths (`shakehorizonangle`), on/off timers,
  schedules, misting, temperature calibration offset, air purifiers, heaters
  and other Dreo devices; on humidifiers, the RGB humidity-indicator light
  (DR-HHM015S), light colours and brightness levels, resetting the filter and
  cleaning counters.
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
  `voiceon`/`muteon`, child lock, head position and oscillation range with
  their angle ranges and the 8 s head settle, ceiling light, light ring); the ceiling-fan
  power gate (cases from hass-dreo's `PyDreoCeilingFan`); temperature with
  `tempunit` 0/1, the >50 rule, offsets and °F/°C hubs; RGB/hue round-trips;
  and the events for sample DR-HAF001S, DR-HPF008S (with light ring) and
  DR-HCF ceiling fan states; humidifier limits from `controlsConf`, events for
  the DR-HHM001S sample state and its variants (sleep target, paused, empty
  tank), and every humidifier command's params.
- **DriverFlowTest**: the app and real drivers together, with state passed
  through JSON as on a hub: discovery and rooms, **Look again**, child
  creation and removal, saved head positions,
  WebSocket open/reports/acks, the one-at-a-time command queue, retries and
  reconnect after a missing ack, light ring and ceiling light children, the
  gate on a real command, online/offline, REST refresh, the speed-step
  preference, and a humidifier from discovery through reports and commands.

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

### Confirmed on a hub (Hubitat C-8, October 2026)

- The app and all drivers save in Apps Code / Drivers Code.
- Sign-in (North America) with the hashed password, discovery, and device
  creation for all the author's fans; the humidifier (DR-HHM001S) is
  discovered and added.
- DR-HPF008S: pushed updates for power, named speeds and steps, all five
  modes (Auto shows as speed `auto`), display auto/on/off and temperature in
  °F; commands sent from Hubitat take effect about as fast as the pushes.

### Still needs a hub

1. The EU region redirect, and the 30-minute back-off after a wrong password.
2. **Choose devices** page: whether it works before the app's first **Done**.
3. WebSocket over time: reconnect after a hub reboot (Initialize) or a lost
   network, and the no-acknowledgement reconnect.
4. DR-HPF008S: oscillation, panel sound, child lock, and leaving Auto mode by
   setting a speed; the 1–12 speed dropdown; head position, oscillation range
   and saved positions (and whether a fixed position needs oscillation off,
   which the app sends).
5. The other fans (DR-HAF001S, DR-HTF004S, 2× DR-HPF004S): that the real state
   keys match the samples in the tests, and each fan does what the params say,
   including `medium` on the 4-step DR-HAF001S. The DR-HTF004S is not in
   hass-dreo's table, so its speed range is a guess (1–9).
6. Light ring on the DR-HPF008S on real hardware: brightness steps, colours,
   effects.
7. Ceiling fan (when it arrives): the power gate and light child on the real
   fan.
8. Humidifier (DR-HHM001S): the real state keys and `controlsConf` match the
   hass-dreo samples; target humidity in Auto and Sleep, mist levels 1–6,
   display, panel sound, ambient light; what `wrong` reports with the tank
   lifted off.

## Credits and licence

MIT License, Copyright (c) 2026 Digital Homesteaders; see [LICENSE](LICENSE).

The Dreo protocol, model data and feature mappings are ported from
[hass-dreo](https://github.com/JeffSteinbok/hass-dreo) by Jeff Steinbok and
contributors (MIT License; notice included in LICENSE). Not affiliated with
or endorsed by Dreo.
