# Testing Dreo Connect

What is checked without a hub, what has been confirmed on one, and what still
needs a real hub and real Dreo devices. Results from other hubs and models are
welcome: see [Reporting a problem](README.md#reporting-a-problem).

## Covered locally (no hub needed)

`tests/run.sh` compiles the app and every driver with Groovy 2.4 (the version
Hubitat runs) against small stand-ins for the hub, then runs:

- **CompileCheck**: every file compiles and its `definition`/`metadata`/
  `preferences` code runs; no method shadows a metadata word such as
  `command`; every method a driver calls on its parent (and the app calls on
  its child devices) exists and is not private; the version is the same in
  every file and the manifest; each `importUrl` is empty or matches the
  manifest's link for that file.
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
  gate on a real command, online/offline, commands held for a device shown
  offline (sent if a refresh finds it online, dropped if it is still offline,
  the refresh fails or never answers), every device offline after 2 minutes
  without the cloud connection or on a rejected sign-in and back on
  reconnect, REST refresh, the speed-step
  preference, a humidifier from discovery through reports and commands, and
  the password hash: written as a password-type setting, never left in state,
  and moved there from a 0.2 install's state.
- **FixtureTest**: every file in `tests/fixtures/`, a real device-list entry
  and full state in the **Log raw Dreo data** shape, goes through discovery,
  device creation, a REST refresh, the events and every driver and light
  command, on a fresh hub each. Commands may only send keys the device itself
  reports, speeds within its top speed and mode values from its own list.
  `digital-homesteaders/` holds the maintainers' test devices, logged on 2026-10-18 (DR-HAF001S,
  DR-HTF004S, DR-HPF008S, 2× DR-HPF004S, DR-HHM001S); `hass-dreo/` holds
  hass-dreo's samples (DR-HTF005S, DR-HTF007S, DR-HPF025S, DR-HCF002S and
  others).

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

## Confirmed on a hub (Hubitat C-8, October 2026)

- The app and all drivers save in Apps Code / Drivers Code.
- Sign-in (North America) with the hashed password, discovery, and device
  creation for all the author's fans; the humidifier (DR-HHM001S) is
  discovered and added.
- DR-HPF004S ("the DR-HPF004S"; first written up here as the DR-HPF008S, a guess
  that turned out wrong): pushed updates for power, named speeds and steps,
  modes (Auto shows as speed `auto`), display auto/on/off and temperature in
  °F; commands sent from Hubitat take effect about as fast as the pushes.
  Turbo and custom from Hubitat work (they come from hass-dreo's table and the
  fan's schedule, not its mode buttons).
- DR-HTF004S (unreleased code, 2026-10-18): after re-pasting the app and
  pressing **Done**, speeds 11 and 12 from Hubitat change the fan's speed
  (its 1–12 range comes from its device list).
  Normal, natural, sleep and auto work; turbo and custom, which it doesn't
  have, are refused (warning in **Logs**).

## Still needs a hub

1. The EU region redirect, and the 30-minute back-off after a wrong password.
2. **Choose devices** page: whether it works before the app's first **Done**.
3. WebSocket over time: reconnect after a hub reboot (Initialize) or a lost
   network, and the no-acknowledgement reconnect.
4. DR-HPF004S: oscillation, panel sound, child lock, and leaving Auto mode by
   setting a speed; the 1–12 speed dropdown; head position, oscillation range
   and saved positions (and whether a fixed position needs oscillation off,
   which the app sends).
5. The other fans (DR-HPF008S, DR-HAF001S, DR-HTF004S, the second DR-HPF004S):
   their real state and device lists are now in the tests, so what remains is
   that each fan does what the params say, including turbo and the `fanon`
   power key on the DR-HPF008S, `medium` on the 4-step DR-HAF001S, and the
   DR-HTF004S's other commands (its speeds 11–12 are confirmed above).
6. Light ring on a DR-HPF008S on real hardware: brightness steps, colours,
   effects.
7. Ceiling fan (when it arrives): the power gate and light child on the real
   fan.
8. Humidifier (DR-HHM001S): its real state and device list are in the tests
   (target range 30–90 %; its `connected: false` that day was right: it was
   unplugged, and the app showed it offline). Still to check: target humidity in Auto and Sleep, mist levels 1–6,
   display, panel sound, ambient light; what `wrong` reports with the tank
   lifted off.
9. Offline handling: unplug a fan and plug it back in, then send a command
   straight away (it should go out once the app has re-read the fan); with
   the hub's internet cut for over 2 minutes, every Dreo device's
   `healthStatus` turns `offline`, and back to its real status a few seconds
   after the connection returns.
10. The password hash setting (see below).

### The password hash setting

The app keeps the password's MD5 in a setting named `passwordMd5`, written
with `app.updateSetting("passwordMd5", [value: hash, type: "password"])` and
not shown as an input on any page. The stand-in only records that call, so on
a hub check:

- **App Status page** (the gear icon on the app): `passwordMd5` is listed
  with its value masked or hidden, and `passwordHash` no longer appears under
  State. If the value shows in plain text, the setting gains nothing over
  state; say so in an issue.
- **Upgrade from 0.2.0**: after pasting the new app, open its page (or press
  **Done**); the app still signs in, the State entry is gone, and the hub log
  shows no sign-in errors.
- **The setting survives**: pressing **Done**, changing other settings and a
  hub reboot keep `passwordMd5` (Hubitat keeps settings that no page shows),
  and a scheduled refresh 30 minutes later still signs in.
- **Re-entering the password** replaces the setting and clears the field.

If `settings` doesn't read back a value written in the same execution, the
app keeps the hash in state as well until a later run sees the setting, so
signing in keeps working either way.
