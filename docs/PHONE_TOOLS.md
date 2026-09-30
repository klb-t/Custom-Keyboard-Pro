# Phone toolbox and canonical platform operations

Implementation checkpoint: 2026-09-30. This extends the existing `Abilities`,
`Verbs` and `Performer`; it is not an independent command runner for model output.
`PhoneCatalogue` stores routes and operation parameters. `PhoneRequest` validates
typed arguments before `PhoneRuntime` touches Android. The native toolbox and
reviewed goal assistant consume these same bindings.

## Implemented surface

`PhoneToolsActivity` has Overview, Sensors, Apps, Access and Actions tabs. System
bars, cutouts and the IME are applied as insets; the status area scrolls within a
bounded height instead of pushing controls outside the working area. Expensive
toolbox diagnostic/package queries run off the main thread. Returning from a system
grant screen rechecks current access. Opening the panel requests no permission,
starts no recording and creates no sensor session.

| Canonical verb | Arguments | Actual effect / observation |
| --- | --- | --- |
| `phone_tools` | optional `tab=overview,sensors,apps,access,actions` | Opens the selected review panel. |
| `phone_info` | optional `section=device,memory,processes,apps,usage,access` | Local snapshot. Device/debug flags are read-only; processes are only those Android returns. Usage requires its special grant. |
| `sensors` | none | Lists static and currently connected dynamic sensors returned by the two `SensorManager.TYPE_ALL` inventory APIs, with instance key, vendor, type, reporting mode, range, resolution and power. |
| `sensor_monitor` | current `sensor` inventory key; optional `hz=1..20`, `seconds=1..300` | Opens a sensor review. The user must press Start; dispatch itself samples nothing. |
| `special_access` | catalogue `target` | Opens the selected Android access/role screen; it does not grant access. |
| `app_info` | visible installed `package` | Public package metadata only. |
| `app_settings` | visible installed `package` | Opens system details for the chosen app. The user controls permissions/force-stop there. |
| `rotation` | `state=auto` or `state=locked`, optional fixed `orientation` | Writes selected `Settings.System` rotation values only with Modify system settings, then reads values back. Human orientations need agreeing default-display readouts; explicit natural-axis quarter turns map directly to the setting. |
| `screen_timeout` | `seconds=15..1800` | Writes `SCREEN_OFF_TIMEOUT` only with Modify system settings, then reads the configured value back. |
| Existing `torch` | `state=on,off,toggle` | Checks flash hardware and Camera permission, submits a torch request, maintains state from Android's callback. The request receipt does not claim physical verification. |
| Existing `system_settings` | catalogue `page` | Version-guarded system routes including Developer options and Wireless debugging. No secure flags are written. |

Existing audio controls, launcher, Home/Recents/navigation remain canonical
`Performer` operations. Notification settings and battery-saver navigation remain
separate from notification-listener grants and battery-optimization exemptions.
Unknown settings intents are not executed from arbitrary command text.

Fixed orientation accepts `portrait`, `landscape` and their reverse variants, or
the precise `natural`, `quarter_turn`, `half_turn`, `three_quarter_turn` values
(`USER_ROTATION` 0–3). Activity-window configuration is not used to infer the
default display's natural orientation. Human variants use application-context
default-display rotation/real dimensions corroborated by display-mode shape; this
is a guarded inference, not an API guarantee. Square/unknown/disagreeing data stops
before any rotation write. Natural-axis values remain available without inference.
The two Android settings writes are not a cross-setting transaction; a refused
mode write after an accepted orientation write is reported as a partial failure
without pretending rollback. Readback still verifies configuration only.

## Sensor sessions

Inventory does not imply sampling permission. Known continuous and on-change
profiles use `registerListener`; one-shot reporting uses `requestTriggerSensor`.
Other reporting modes and unknown/physiological/head-tracker profiles remain
inspect-only until an audited adapter and permission/privacy flow exist. Step
counter/detector access is requested only after the selected session needs
`ACTIVITY_RECOGNITION` (API 29+). Health permissions are not opportunistically
requested; Android 16's granular health permission/rationale requirements need a
complete health adapter.

The session has a visible Stop control and ends on timeout, panel switch, pause or
destruction. It does not resume automatically. UI delivery is bounded to 1–20/s,
copies at most 16 finite values and retains no recording. Queued events whose
monotonic nanosecond sensor timestamp precedes the current session cannot be
attributed to a stopped/rearmed session. Each one-shot arm gets its own listener
identity, so Android's automatic cancellation of an old delivery cannot cancel a
newly armed session. Sensor registration is reported separately
from receiving an event; an on-change sensor can correctly
produce no event during a session. Requested sampling/delivery rates are not a
claim about the actual hardware rate.

## Access and privilege boundaries

The access inspector reads actual app-specific overlay, write-settings,
accessibility, usage AppOps, battery-exemption, exact-alarm, all-files, assistant
role and Autofill state where the API allows it. Unknown checks stay unknown.
Notification reading, exact-alarm scheduling and all-files adapters remain marked
absent even if a platform grant exists. This build does not declare/request broad
all-files access, exact-alarm grants or a notification listener. Existing SAF
documents and authenticated Autofill remain the implemented paths.

Assistant selection prefers Android's assistant-role dialog where available, then
the system voice-input screen. The system voice host is documented separately in
`SYSTEM_VOICE_ASSISTANT.md`; selecting it neither starts recording nor grants
developer/process privileges.

`PACKAGE_USAGE_STATS` is a system special access, not a runtime permission dialog.
Its snapshot is historical aggregate usage for the last 24 hours, not the live
process list. Package visibility is limited to the declared launcher interest and
Android's own visibility rules. No application private data is read.

An ordinary app cannot enable developer options/ADB or write arbitrary
`Settings.Secure`/`Settings.Global` values. Modern Android also restricts process
and task visibility and killing other apps. Developer/Wireless debugging routes
therefore navigate only; this build has no root/DPC/ADB/Shizuku bridge and no
arbitrary shell evaluator. These are named integration gaps, not proof that an
authorized future bridge is impossible.

## Receipts and validation

`PerformerHost.phoneReceipt` carries the same invocation's immutable command and
arguments, plus one of `OBSERVED`, `VERIFIED`, `OPENED`, `REQUESTED`, `NEEDS_ACCESS`,
`UNAVAILABLE`, `FAILED`. Hosts must not reuse a receipt from an earlier call or
dispatch the operation twice to obtain a receipt. `VERIFIED` for settings means a
matching configuration readback; administrator policy, OEM behavior and app
orientation/screen-awake locks may alter the visible/effective result. An opened
activity is not verification of the user's eventual grant or intended goal.

Focused tests cover typed bounds, missing/unexpected arguments, package and route
validation, measurement gating/copying, grant/API refusal, settings/grant route
separation and a single canonical dispatch/receipt. The full real SDK/Gradle gate
is tracked in `RESUME.md`; Robolectric validates control flow, not sensor hardware,
OEM settings, Binder/roles, actual grant transitions or physical effects. These
still need a phone.

Primary platform references:

* [Special-access checks](https://developer.android.com/training/permissions/requesting-special)
* [Sensor overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
* [Sensor metadata and reporting modes](https://developer.android.com/reference/android/hardware/Sensor)
* [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
* [ActivityManager process limits](https://developer.android.com/reference/android/app/ActivityManager)
* [Settings.System](https://developer.android.com/reference/android/provider/Settings.System)
* [Display rotation and accessible display size](https://developer.android.com/reference/android/view/Display)
* [Display mode physical dimensions](https://developer.android.com/reference/android/view/Display.Mode)
* [Android 16 health changes](https://developer.android.com/about/versions/16/behavior-changes-16#health-fitness)
* [ADB and wireless pairing](https://developer.android.com/tools/adb)
