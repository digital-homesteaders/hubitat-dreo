# Dreo Connect for Hubitat

Control Dreo cloud fans and humidifiers from a Hubitat Elevation hub: tower
fans, air circulators and ceiling fans, including ceiling lights and light
rings, and DR-HHM humidifiers.

> **Status: 0.3.0, early testing on a Hubitat C-8.** Installing, signing in,
> discovery and device creation work for fans and a DR-HHM001S humidifier,
> and on a DR-HPF008S live updates and commands from Hubitat (power, speeds,
> modes, display, temperature) behave as expected. The other features follow
> the protocol verified in the author's Homey Dreo app and in
> [hass-dreo](https://github.com/JeffSteinbok/hass-dreo), but haven't been
> checked on a hub yet. [TESTING.md](TESTING.md) lists what has and hasn't
> been confirmed; [CHANGELOG.md](CHANGELOG.md) lists what changed.

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
- **Dreo Ceiling Fan Light** and **Dreo Fan Light Ring** child devices, created
  automatically when a fan reports a light or a light ring, so dashboards,
  Room Lighting and voice assistants see them as ordinary lights.

State arrives over the WebSocket within a second or so. Each fan is also
re-read over REST every 30 minutes and after every reconnect, as a safety net.
The app ships a small table of speed ranges and modes per model rather than
downloading Dreo's 700 KB app configuration on the hub.

Commands go out one at a time. Commands for the same device that are still
waiting merge into one (the newest value of each setting), so a rule or a
burst of taps sends fewer messages. A command for a device that shows offline
is held while the app re-reads the device from Dreo: if the device is online
after all (Dreo's "back online" message was missed), the command goes out;
if it is still offline, or Dreo doesn't answer within 30 seconds, the command
is dropped with a warning in the log. Offline devices are never sent commands
directly, since Dreo doesn't answer for them. If a command gets no answer
while Dreo keeps sending other messages, only that device is re-read; the
connection reconnects only when Dreo has gone quiet altogether.

`healthStatus` also covers the cloud connection: when the Dreo Cloud
Connection has been disconnected for 2 minutes, or Dreo rejects the sign-in
while it is disconnected, every Dreo device shows `offline` (the app logs
"cloud connection down"), so a "device offline" alert fires. When the
connection is back, each device is re-read and shows its real status.

Devices keep Dreo's state for their own settings only: network details (Wi-Fi
name, MAC and IP addresses, signal strength) are not stored, and the firmware
versions go in the device's `firmware` data value.

## Install

The **Dreo Connect** app and the **Dreo Cloud Connection** driver are always
needed. Pick the other drivers by the devices you have (the model number is
on the device's label and in the Dreo app under the device's settings):

| If you have | Install these drivers |
| --- | --- |
| Tower fans (DR-HTF…), air circulators (DR-HAF…), pedestal fans (DR-HPF…) | Dreo Fan |
| DR-HPF008S (pedestal fan with a light ring) | Dreo Fan **and** Dreo Fan Light Ring |
| Ceiling fans (DR-HCF…) | Dreo Ceiling Fan **and** Dreo Ceiling Fan Light |
| DR-HCF002S, DR-HCF007S, DR-HCF008S, DR-HCF010S (ceiling fans with a light ring) | Dreo Ceiling Fan, Dreo Ceiling Fan Light **and** Dreo Fan Light Ring |
| Humidifiers (DR-HHM…) | Dreo Humidifier |

If unsure, install them all: an unused driver does nothing. Another fan with
a light ring creates a Dreo Fan Light Ring device too, so add that driver if a fan
not listed above has one. **Choose devices** in the app lists the drivers the
ticked devices need, and the app's main page names any device, or light
child of a fan, it couldn't add because its driver is missing.

### Hubitat Package Manager

In **Hubitat Package Manager**, choose **Install → From a URL** and paste:

```
https://raw.githubusercontent.com/digital-homesteaders/hubitat-dreo/main/packageManifest.json
```

When it asks which optional parts to install, tick the drivers from the table
above. To add one later (a new device, or a driver missed out), use
**Modify a Package** → Dreo Connect.

### Manually

1. **Drivers Code → New Driver**: paste and save
   `drivers/dreo-cloud-connection.groovy`, then the drivers from the table
   above: `dreo-fan.groovy`, `dreo-ceiling-fan.groovy`,
   `dreo-ceiling-fan-light.groovy`, `dreo-fan-light-ring.groovy`,
   `dreo-humidifier.groovy`.
2. **Apps Code → New App**: paste and save `apps/dreo-connect.groovy`.
3. **Apps → Add User App → Dreo Connect**. Enter your Dreo email and
   password, open **Choose devices**, tick the fans and humidifiers you want,
   check the drivers it lists are installed, then press **Done**.

**Updating:** re-paste the app and every driver you installed, from the same version, then
press **Done** in the app. The app's Logging section shows its version and
names any device whose driver comes from another version (each device's Data
shows its `driverVersion`). Debug logging, in the app and on each device,
turns itself off after 30 minutes.

Fans used to have a percentage level (SwitchLevel). After updating from 0.1,
the old `level` value disappears at the fan's next report; if Alexa or Google
Home still show a fan as a dimmer, remove and re-add it there.

Only an MD5 hash of the password is stored (that is what Dreo's sign-in
expects); the password field is cleared once it is hashed. The hash is kept
in a password-type setting rather than in the app's state, so the app's
status page shouldn't show it in plain text (still to be confirmed on a hub,
see [TESTING.md](TESTING.md#the-password-hash-setting)). It signs in to Dreo
just like the password would and it is in hub backups, so keep those
private. Installs from 0.2.0 move the hash out of state the next time the
app's page is opened or **Done** is pressed.

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
| `setOscillationSweep(horizontal, vertical)` | `cruiseconf` set the way the Dreo app's quick presets do: a horizontal width centred straight ahead (10° steps), a vertical width opening upward from the lowest tilt (5° steps); leave one empty to keep it. `setOscillationRange` rounds its limits to 5° too. |
| `setOscillationAngle(angle)`, `oscillationAngle`, `supportedOscillationAngles` | `shakehorizonangle`: the sweep width of a tower fan, from the presets in its device list (30, 60, 90, 120° on the DR-HTF004S) |
| `savePosition(slot, name)`, `goToPosition(slot)`, `deletePosition(slot)`, `savedPositions` | Up to five head positions kept on the Hubitat device (Dreo's cloud has no saved positions). `goToPosition` also takes a saved name, for rules. |
| `setTemperatureOffset(n)`, `temperatureOffset` | `tempoffset`: the Dreo app's temperature calibration, −10 to 10 in the fan's own unit, on fans that report it |
| `setAdaptiveBrightness(on/off)`, `adaptiveBrightness` | `lightsensoron`: dim the display with the room light, on fans that report it |
| `setMist(on/off)`, `mist` | `miston` on misting tower fans (DR-HTF021S, DR-HTF027S) |
| `setTimerOn(minutes)`, `setTimerOff(minutes)`, `timerOn`, `timerOff` | `timeron` / `timeroff`: switch on or off after 0–719 minutes (0 cancels). The attributes show the minutes Dreo last reported. |
| `setSchedules(on/off)`, `schedules` | `scheon`: run or pause the schedules set in the Dreo app (the schedules themselves aren't shown yet) |
| `healthStatus` | fan online/offline; also offline while the cloud connection is down (see [How it works](#how-it-works)) |

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

Each fan's speed range and modes come from its own entry in Dreo's device
list (`controlsConf`, the data the Dreo app builds its buttons from), read at
discovery and kept as the device's `speedRange` and `modes` data values. For
example, the DR-HTF004S, which isn't in hass-dreo's table, gets 1–12. Some
models only get a template reference there (the DR-HPF008S, for one); those
use the built-in model table from hass-dreo, and models missing from it use
their family's defaults. Turbo and custom, which some fans offer outside the
mode buttons, count where the table has them and the fan's own schedule lists
them (the DR-HPF004S has both). The DR-HTF007S always uses the table
(8 steps), since its device list says 4 on every hardware revision.

If a speed range is still wrong, set **Number of speed steps** in the fan's
preferences. A fan reporting a higher step than expected also widens the range
by itself. Each **Done** in the app reads the device list again, so a newer
range (after a Dreo update, or a newer Dreo Connect) reaches existing devices.

### Dreo Ceiling Fan

Switch, FanControl and the 1–12 speed number drive the fan motor (`fanon`), with the
modes the fan's device list gives (normal, natural, sleep and reverse on most;
auto instead of reverse on the DR-HCF002S). The main light becomes a **Dreo Ceiling Fan Light**
child (Switch, SwitchLevel, ColorTemperature 2700–6500 K). Ceiling fans put
every load behind a `poweron` gate; the app adds the gate keys to each command
the way the Dreo app does. Timers (`setTimerOn`, `setTimerOff`) and the
schedules switch (`setSchedules`) work as on the other fans.

### Dreo Fan Light Ring

Switch, SwitchLevel (mapped to the ring's brightness steps, e.g. 1–3 on the
DR-HPF008S), ColorControl (`atmcolor`, 24-bit RGB) and LightEffects
(1 constant, 2 circle, 3 breath), plus `setEffectSpeed(low/medium/high)` and
`effectSpeed` (`atmspeed`) for how fast the circle and breath effects run.
Changing colour, brightness, effect or effect speed on a dark ring turns it
on, like the Dreo app.

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
| `setDisplay(on/off)`, `display` | `ledlevel`: 0 off; on restores the level last seen (the Dreo app lets you pick one), else 2 |
| `setPanelSound(on/off)`, `panelSound` | `muteon` |
| `setAmbientLight(on/off)`, `ambientLight` | `ambient_switch`, or `rgblevel` (0 off; on restores the level last seen, else 2) |
| `setAdaptiveBrightness(on/off)`, `adaptiveBrightness` | `lightsensoron`: dim the display with the room light |
| `setTimerOn(minutes)`, `setTimerOff(minutes)`, `timerOn`, `timerOff` | `timeron` / `timeroff`, 0–719 minutes (0 cancels) |
| `setSchedules(on/off)`, `schedules` | `scheon`: run or pause the schedules set in the Dreo app |
| `healthStatus` | online/offline; also offline while the cloud connection is down |

The target-humidity and mist ranges come from the device list Dreo sends at
discovery (30–90 % and mist 1–6 on the author's HM311S), falling back to 30–90 % and
1–6. (Humidifiers with MCU firmware older than 0.7.1 may stop at 80 %, which
the device list says only in a part the app doesn't read; a higher target is
then refused by the humidifier.) Mode, target or mist changes on a humidifier that is off also switch it on.
Hubitat has no humidifier capability, so rules use the custom commands and
attributes.

## Limitations

- Needs Dreo's cloud; there is no local control.
- Not yet: the schedules themselves (only their on/off switch; the Homey app
  shows the list), air purifiers, heaters and other Dreo devices; on
  humidifiers, the RGB humidity-indicator light (DR-HHM015S), light colours
  and brightness levels, resetting the filter and cleaning counters.
- Which settings a device has is read from the state it reports, so a
  command for a setting the device doesn't report is refused with a warning
  in **Logs**. The feature list both the Hubitat and Homey apps work from is
  kept in the maintainers' `dreo-research/dreo-feature-matrix.md`.
- Models whose device list has no speed or mode data and that aren't in the
  built-in table use their family's defaults (towers and circulators 1–9 with
  normal/natural/sleep/auto; ceiling fans 1–12 with
  normal/natural/sleep/reverse).
- Fan state is kept on the fan device. Two reports arriving at exactly the
  same moment can, rarely, overwrite each other; the next report or the
  30-minute refresh corrects it.

## Troubleshooting

**Nothing updates or responds.** Open the **Dreo Cloud Connection** device.
Its `connection` attribute should be `connected`. If it is `disconnected`
or stuck on `connecting`, press **Reconnect** (or **Initialize**), then check
**Logs** for its messages. If the device is missing, press **Done** in the
app to add it back. The app's main page also shows the connection and each
device's online status, and its **Status** line shows sign-in problems
(after a rejected password the app waits 30 minutes before trying again;
entering the password again retries straight away).

**One device is wrong or shows `offline`.** Check it in the Dreo app first:
Dreo Connect only sees what Dreo's cloud reports. **Refresh** on the device
re-reads it.

**A model isn't mapped, or a value looks wrong.** Press **Log raw Dreo
data** in the app (under Logging): it writes each device's raw device-list
entry and full state to **Logs**. Only model data is kept: no owner, home,
room or device names, MAC or IP addresses, Wi-Fi name, or full serial numbers
(a short tag made from the serial number tells devices apart), so the log is safe to paste
into a forum post.

**Debug logs.** Turn on **Debug logging** in the app (Logging section), on
the **Dreo Cloud Connection** device and on the device in question. Each
turns itself off after 30 minutes. Then repeat what went wrong and open
**Logs**.

**After updating**, if something breaks quietly, look at the app's Logging
section: it names any device whose driver comes from another version.
Re-paste that driver and press **Done**.

### Reporting a problem

Please include:

- The Dreo Connect version (app page, Logging section) and your hub model
  and platform version.
- The device model (for example DR-HPF008S) and what you did, what you
  expected and what happened.
- The **Log raw Dreo data** lines for that device.
- The debug log lines from the app, the cloud connection and the device
  around the time it happened.

Unlike the raw-data lines, debug logs show full serial numbers and Dreo's
reports as they arrive, which can include the Wi-Fi name or IP address. Look
them over and blank those out before posting in public.

## Credits and licence

MIT License, Copyright (c) 2026 Digital Homesteaders; see [LICENSE](LICENSE).

The Dreo protocol, model data and feature mappings are ported from
[hass-dreo](https://github.com/JeffSteinbok/hass-dreo) by Jeff Steinbok and
contributors (MIT License; notice in [NOTICE](NOTICE)). Not affiliated with
or endorsed by Dreo.
