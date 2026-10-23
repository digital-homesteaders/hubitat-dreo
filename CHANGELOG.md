# Changelog

Versions are stamped by `scripts/release.sh`. After updating, re-paste the app
and every driver, then press **Done** in the app.

## Unreleased

- **Clearer child driver names.** *Dreo Light Ring* is now **Dreo Fan Light
  Ring** and *Dreo Ceiling Light* is now **Dreo Ceiling Fan Light**, so each
  name says what it belongs to and sorts under its fan (files renamed to
  match). **Updating by hand:** paste each renamed driver *over its existing
  entry* in Drivers Code; don't create a new one, or you'll have two. HPM
  updates the same entries itself. Existing light devices keep working and
  take the new name on their fan's next update.
- **No more stray sockets (memory).** The cloud connection now closes any old
  WebSocket before opening a new one, closes a socket that failed, ignores the
  old socket's close notice while connecting, skips a connect while one is
  already in progress, and gives up on a connect after 30 s. After new driver
  code, incoming data takes over the still-open socket instead of leaving it
  idle with commands stuck. Found while chasing a hub that ran out of memory:
  two Dreo Connect apps reconnecting against each other leaked about 21 MB an
  hour.
- **Pick the drivers you need.** In Hubitat Package Manager only the app and
  the Dreo Cloud Connection driver are required; the fan, ceiling fan,
  ceiling light, light ring and humidifier drivers are optional. The README
  has a table of which models need which drivers, **Choose devices** lists the
  drivers the ticked devices use, and the app's main page names any device it
  couldn't add because its driver is missing.
- The Dreo password hash moves from the app's state to a password-type
  setting, so the app's status page doesn't show it in plain text. Existing
  installs move it the next time the app's page is opened or **Done** is
  pressed; nothing needs re-entering.
- `scripts/release.sh` fills every file's `importUrl` and the HPM manifest's
  GitHub links from one `OWNER` setting, ready for when the repository is
  published.
- Docs: Troubleshooting section, this changelog, and the testing notes moved
  to [TESTING.md](TESTING.md).
- **Speed range and modes from each fan's own device list** (`controlsConf`),
  read when devices are listed; the built-in table only covers models whose
  list is a bare template. The DR-HTF004S now goes to 12 (was a 1–9 guess);
  ceiling fans get their real modes (the DR-HCF002S has auto, not reverse); the
  ceiling-fan mode dropdown gains auto. **Done** now reads the device list
  again, so existing devices pick this up without a visit to **Choose
  devices** (before, a re-pasted app kept capping the DR-HTF004S at 9).
- **Log raw Dreo data** tags each device with a short hash of its serial
  number instead of its last four characters, which were the same on every fan.
- Tests: the author's real devices (and hass-dreo's samples) run through
  discovery, events and every command (`FixtureTest`).

## 0.2.0 (2026-10-18)

- **Humidifiers** (DR-HHM): power, humidity, target humidity, modes, mist
  level, display, panel sound, ambient light, water tank, cleaning and filter
  counters.
- **Fan speed is a 1–12 number** (`setSpeedLevel`), as in the Dreo app,
  instead of a percentage. Fans no longer have SwitchLevel; the old `level`
  disappears at the fan's next report. Any standard speed name works
  (`medium` picks the nearest step).
- Fan head position, oscillation range and up to five saved positions.
- `supportedModes`, `supportedOscillation`, `supportedDisplay` and
  `supportedFanSpeeds` attributes per fan.
- **Look again** on the Choose devices page; **Log raw Dreo data** button.
- Commands waiting for the same device merge into one; an offline device gets
  no commands (it is re-read instead); a missing acknowledgement only
  reconnects when Dreo has gone quiet.
- Devices keep no network identifiers (Wi-Fi name, MAC, IP, signal) in their
  state; firmware goes in a data value.
- One version in the app, every driver and the manifest; the app lists
  devices whose driver is from another version.
- Debug logging turns itself off after 30 minutes.

## 0.1.0 (2026-10-16)

First version tested on a Hubitat C-8.

- Dreo Connect app: sign-in (North America and Europe), discovery, one
  device per chosen fan.
- Dreo Cloud Connection device: the WebSocket, with commands sent one at a
  time and retried until Dreo acknowledges them; a REST refresh every 30
  minutes.
- Dreo Fan (tower fans, air circulators) and Dreo Ceiling Fan, with speed as
  a percentage, modes, oscillation, display, panel sound, child lock and
  temperature; Dreo Ceiling Light and Dreo Light Ring children.
