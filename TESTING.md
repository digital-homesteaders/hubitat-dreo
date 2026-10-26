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
  manifest's link for that file; only the app and the cloud connection are
  required in the manifest.
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
  WebSocket open/reports/acks, socket hygiene (close before connect even after
  new code, the old socket's close notice ignored while connecting and just
  after the open, one report per drop, the 30 s connect timeout, an open after
  Disconnect or the timeout closed again, taking over a live socket after new
  code with the app told, a command after new code asking for a socket, a
  newer URL tried when the attempt it waited on fails), the one-at-a-time command queue, retries and
  reconnect after a missing ack, light ring and ceiling light children, the
  gate on a real command, online/offline, commands held for a device shown
  offline (sent if a refresh finds it online, dropped if it is still offline,
  the refresh fails or never answers), every device offline after 2 minutes
  without the cloud connection or on a rejected sign-in and back on
  reconnect, REST refresh, the speed-step
  preference, a humidifier from discovery through reports and commands, and
  the password hash: written as a password-type setting, never left in state,
  and moved there from a 0.2 install's state; the drivers **Choose devices**
  lists for the ticked devices, a device whose driver is missing named on
  the main page until the driver is installed, and a fan whose light ring
  driver is missing: error logged once, named on the main page, child created
  once the driver is there; the 0.2 light child names updated on Done.
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
- DR-HHM001S humidifier: every command from Hubitat appeared to work (power,
  mode, target humidity, mist level, display, panel sound, ambient light).
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
- 2026-10-21, the author's checks of the then-unreleased code (reported as
  done, with no problems):
  - DR-HPF008S: light ring brightness, colours and effects, turbo, and
    power through its `fanon` key.
  - DR-HPF004S: oscillation, panel sound, child lock, leaving Auto by
    setting a speed, the 1–12 speed dropdown, head position, oscillation range
    and saved positions.
  - DR-HAF001S, DR-HTF004S and the second DR-HPF004S: their commands do what
    the params say, including `medium` on the 4-step DR-HAF001S.
  - The app's Status page shows the `passwordMd5` setting masked (`***`),
    and State shows `region: NA` (North America).

## Still needs a hub

1. The EU region redirect, and the 30-minute back-off after a wrong password.
2. **Choose devices** page: whether it works before the app's first **Done**.
3. WebSocket over time: reconnect after a hub reboot (Initialize) or a lost
   network, and the no-acknowledgement reconnect.
4. Ceiling fan (when it arrives): the power gate and light child on the real
   fan.
5. Humidifier (DR-HHM001S): its commands work (see above), and its real state
   and device list are in the tests (target range 30–90 %; its
   `connected: false` that day was right: it was unplugged, and the app showed
   it offline). Left open (the author's test unit has gone back to its
   owner; reports from other owners welcome): what `wrong` reports with the
   tank lifted off, and targets above 80 % (the range the device list gives
   newer firmware).
6. Offline handling: unplug a fan and plug it back in, then send a command
   straight away (it should go out once the app has re-read the fan); with
   the hub's internet cut for over 2 minutes, every Dreo device's
   `healthStatus` turns `offline`, and back to its real status a few seconds
   after the connection returns.
7. The password hash setting (see below): masking is confirmed; still to
   check are the 0.2.0 upgrade, a hub reboot and re-entering the password.
8. Optional drivers: Hubitat Package Manager offers the five device drivers
   as optional on install and under **Modify**; **Choose devices** shows the
   drivers list as the ticks change; the missing-driver note on the main page
   with one driver left out, and with a fan's light ring driver left out (one
   error in Logs, the fan named on the main page, the ring device appearing
   once the driver is pasted in and the fan next reports).
9. The 0.3.0 review fixes to the cloud connection: that Hubitat's `status:
   closing` for a socket closed by `connect()` can indeed arrive after the
   new socket's `status: open` (the 3 s grace covers it), and that a connect
   attempt the hub gives up on never leaves a socket open.

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
