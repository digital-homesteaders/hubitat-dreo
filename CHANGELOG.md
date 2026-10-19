# Changelog

Versions are stamped by `scripts/release.sh`. After updating, re-paste the app
and every driver, then press **Done** in the app.

## Unreleased

- The Dreo password hash moves from the app's state to a password-type
  setting, so the app's status page doesn't show it in plain text. Existing
  installs move it the next time the app's page is opened or **Done** is
  pressed; nothing needs re-entering.
- `scripts/release.sh` fills every file's `importUrl` and the HPM manifest's
  GitHub links from one `OWNER` setting, ready for when the repository is
  published.
- Docs: Troubleshooting section, this changelog, and the testing notes moved
  to [TESTING.md](TESTING.md).

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
