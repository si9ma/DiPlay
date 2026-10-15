# Initial UI and map appearance

Author: 寒叙 (@Hanxu4131).

The local BYD Carplay implementation was tested on a 2023 BYD Tang DM-i Champion Edition, platform/controller 21. The owner confirmed that ordinary CarPlay followed light/dark head-unit changes after these initial display declarations were added. This is the source implementation's vehicle result, not a vehicle test of this upstream port, and is not a claim of CarPlay Ultra support.

Both main and alternate displays now declare automatic UI and map appearance in `/info`, before the existing runtime `setNightMode` commands. The upstream theme selection, sensors and configuration-change handling remain unchanged. No reconnect, Wi-Fi configuration, vehicle identifiers or extra polling are introduced.

The four fields describe the initial automatic UI/map mode; they are not a substitute for runtime theme updates or evidence that an iPhone acknowledged a particular command. Existing day/night controls still choose the desired runtime mode. The protocol test covers both displays. This upstream integration needs an in-car test of initial connection and live light/dark switching, including forced modes and the instrument display.

---

## Which head-unit input "Follow the car" believes

DiLink firmware does not agree on where the car's day/night state lives. BYD keeps its own switch in
the settings key `key_byd_night_mode` (observed on a Di150VCP: `1` = night, `2` = day, the opposite of
Android's `ui_night_mode`) and on that car leaves every `Configuration` reporting night around the
clock; other firmware updates the `Configuration` and never touches the key. One fixed order therefore
cannot follow every car, so **Which input to follow** sits under *CarPlay day/night → Follow the car*:

| Choice | Read from |
|---|---|
| Automatic | the first of the rows below that reports anything, BYD's key first |
| The car's day/night switch | `key_byd_night_mode` in `Settings.System`, `.Secure` or `.Global` |
| The CarPlay screen's configuration | the host activity's `Configuration.uiMode` |
| The app's configuration | the application `Configuration.uiMode` |
| Android's night mode | `Resources.getSystem().configuration.uiMode` |
| Android's UiModeManager | `UiModeManager.getNightMode()`, ignoring AUTO and CUSTOM |

A named choice answers alone and does not fall back, which is the point: falling back would bring back
the input the driver chose it to avoid. The settings page prints every input live underneath, so the
driver switches the car and picks the row that moves. A choice made by the pre-8.0.19 day/night card is
migrated once from the `diplay_day_night` preferences; its "always day"/"always night" options are
modes of their own now and leave the source alone.

Each sampled `THEME_DIAGNOSTIC` line in the session log carries the same reading, so a report shows
which inputs moved over the minutes it covers rather than only at startup.
