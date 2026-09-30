# Android phone capability research

Researched: 2026-09-30. Baseline: IO Matrix minSdk 24, targetSdk 36.

This is a researched capability inventory, not an executable-feature or device-test
report. A row means that an integration route exists or that a platform boundary
has been identified. It does not mean IO Matrix implements that route, has the
necessary access, or has verified it on a phone. The current runtime must publish
its smaller executable subset separately. See `UNIVERSAL_ASSISTANT.md` and
`GOAL_ASSISTANT_IMPLEMENTATION.md` for product scope and existing execution rules.

## Profile model and discovery

Keep mechanism, instance, policy and observed state separate. A capability profile
should identify an operation and its typed arguments, input/output data, API range,
hardware features, permissions/special access/roles, host requirements, adapter ID,
setup routes, effects, verification, fallback and official-source evidence. External
devices add protocol/version, pairing/authentication and a selected device instance.

Runtime state should have independent fields for:

| Dimension | Example values |
| --- | --- |
| Platform/hardware | supported, unsupported, unknown |
| Authorization | granted, denied, requires system settings, requires role, session expired |
| Integration | implemented, missing adapter, provider setup required, bridge unavailable |
| Observation | not attempted, registered, no sample yet, dispatched, verified, failed, cancelled |
| Evidence | source URL and checked date; runtime timestamp, device/API, verified outcome |

An enabled Android access is not evidence of a running service. A successful intent
launch is not evidence that a setting changed. A discovered BLE service is not
evidence that its payload format is supported. A missing adapter is not proof of
physical impossibility. Refresh grants and conditions immediately before execution;
recheck special access on return from system settings [S3].

Settings scope is orthogonal to expertise: a sensor-instance sampling policy belongs
to that instance; a panel's geometry belongs to that panel; a global retention policy
belongs to the application. Basic/Advanced/Expert/Debugger are projections of these
canonical values, not four stores. Runtime observations belong to Debugger; editable
values need typed setters and validation. Do not expose arbitrary memory mutation.

## Sensor and measurement inventory

Enumerate `SensorManager.getSensorList(Sensor.TYPE_ALL)`, preserving multiple sensors
of one type and unknown vendor `stringType` entries. Capture name, vendor, version,
range, resolution, power, min/max delay, FIFO, reporting mode and wake-up capability.
Do not require optional hardware in the manifest [S1, S2].

| Family | Android route and versions | Access and verification |
| --- | --- | --- |
| Motion and orientation | Accelerometer, gyroscope, gravity, linear acceleration, magnetometer; calibrated/uncalibrated and rotation-vector variants; heading and limited-axis variants added at API 33 | Usually no dangerous grant for ordinary motion sensors. Register the actual selected instance and report timestamp, accuracy, values and measured delivery rate. |
| Environment and proximity | Light, proximity, pressure, ambient temperature, relative humidity | Hardware is optional. Missing temperature/humidity must remain unavailable; battery temperature is a different measurement. |
| Activity and trigger sensors | Step counter/detector (API 19), significant motion (18), stationary/motion detect (24), low-latency off-body (26) | Steps require `ACTIVITY_RECOGNITION` on current Android. Significant motion uses `requestTriggerSensor`, not the generic streaming listener. Respect each reporting mode. |
| Fold and wearables | Hinge angle (30), heart rate (20), heart beat (24); head tracker (33) | Heart-rate access uses `BODY_SENSORS` on older platforms; on Android 16 for target 36+ use granular `health.READ_HEART_RATE`. Health permission integration also needs the documented mobile privacy/rationale activity. Head tracker is generally unavailable to ordinary apps. |
| Vendor or unknown types | Preserve dynamically discovered sensor metadata | Inventory is safe to expose; do not automatically read a type whose permission/privacy contract has not been identified. |
| Location and GNSS | `LocationManager`/location providers; GNSS callbacks and raw measurements where supported | Coarse/fine location grants; background location is a separate API 29+ grant. Approximate location is a valid result, not a denied precise reading [S4]. |
| Camera, microphone, touch and input | Camera/Camera2 or CameraX; `AudioRecord`; focused key/motion events and input-device inventory | `CAMERA`/`RECORD_AUDIO` where applicable. A route needs an actual host and session; input observation does not authorize injecting events globally. |
| Battery and thermal diagnostics | Battery state; `PowerManager` thermal status (29), headroom (30), remaining-life estimate (31) | Thermal headroom can be unavailable/NaN and is not a temperature. Avoid frequent polling; the API recommends about once per second at most [S5]. |
| Biometrics | `BiometricPrompt`, platform authentication and crypto binding | This is an authentication result, not access to raw fingerprint/face data or a general-purpose imaging sensor [S6]. |

Sensor listeners must stop when their explicit session ends. API 28+ restricts
background sensor delivery; an appropriate foreground service is a separate host
with its own start rules. For target 31+, motion/position listener rates above
200 Hz require `HIGH_SAMPLING_RATE_SENSORS`; requested rate still does not guarantee
delivery rate [S1]. Start the toolbox with bounded foreground samples, and label
no events separately from registration failure. One-shot/special-trigger profiles
need dedicated adapters rather than pretending every sensor is a continuous stream.

## Special access and system roles

Request access in the context of a selected operation. Each row needs a real check
and a setup route; a general list of manifest grants is insufficient [S3].

| Capability | Actual gate/check | Boundary and implementation route |
| --- | --- | --- |
| Draw above apps | `Settings.canDrawOverlays()`; `ACTION_MANAGE_OVERLAY_PERMISSION` (23+) | `TYPE_APPLICATION_OVERLAY` (26+) is subject to OS window rules; it is not privileged input control [S7]. |
| Write user system settings | `Settings.System.canWrite()`; `ACTION_MANAGE_WRITE_SETTINGS` (23+) | Supports applicable `Settings.System` values; does not grant write access to Secure/Global [S8]. |
| Application usage | `PACKAGE_USAGE_STATS` with actual AppOps access; `ACTION_USAGE_ACCESS_SETTINGS` | `UsageStatsManager` provides usage/events, not an exhaustive process/task list [S9]. |
| Accessibility actions | Explicit user-enabled service, required metadata and connected service instance | Node actions, declared gestures, available global actions. Return values and callbacks can fail; view trees can be incomplete [S10]. |
| Read/control notifications | Enabled `NotificationListenerService` plus `onListenerConnected` | Wait for connection before reading/cancelling. Access may be restricted for work profiles and older low-RAM devices [S11]. Posting own notifications is a different gate (`POST_NOTIFICATIONS`, 33+). |
| Do Not Disturb | `isNotificationPolicyAccessGranted()`; `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` | For target 35+ ordinary apps change an app-owned `AutomaticZenRule`, rather than directly replacing the global filter [S12]. |
| Exact alarms | `AlarmManager.canScheduleExactAlarms()` (31+); `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` | `SCHEDULE_EXACT_ALARM` is revocable and not generally pregranted to fresh target 33+ installs; revocation cancels future exact alarms [S13]. |
| Battery-optimization exemption | `PowerManager.isIgnoringBatteryOptimizations(package)`; relevant system settings | Exemption does not remove all background/FGS/OEM restrictions. Use only for an operation that needs it [S5, S14]. |
| All-files access | `Environment.isExternalStorageManager()` (30+); all-files settings | Shared-storage manager route; still excludes other apps' app-specific directories. SAF remains a useful narrower route [S15]. |
| Package installation | `PackageManager.canRequestPackageInstalls()` (26+); unknown-source settings | Actual install uses `PackageInstaller` and may require system confirmation; this is not a silent installer privilege [S7]. |
| VPN | `VpnService.prepare()` consent and own running tunnel | One authorized VPN app at a time; handle `onRevoke`. TUN access does not decrypt arbitrary TLS traffic [S16]. |
| Assistant | `RoleManager.isRoleAvailable/isRoleHeld(ROLE_ASSISTANT)` (29+) | User-selected role and qualifying host. A real `VoiceInteractionService`/session host is distinct from an `ACTION_ASSIST` activity [S17, S18]. |
| Dialer, call screening/redirection, SMS, home, wallet | Check the particular available/held role and qualifying service/activity | Roles are independent. Default dialer requires a real `InCallService`; role possession does not implement an entire dialer, SMS stack or payment provider [S17, S19]. |
| Autofill and credentials | Enabled `AutofillService` (26+); enabled credential provider (34+) | Explicit provider integration, authentication and destination trust; keyboard credentials do not automatically fill every browser or provide passkeys [S20, S21]. |
| Media projection | New per-session system consent and live token; projection service where needed | A session token is not a permanent special grant. Do not show it as permanently enabled [S22]. |
| Device administration/owner | Actual active admin/owner state for the exact DPM method | Legacy admin, profile owner and device owner differ. Owner provisioning is not a normal permission dialog [S23]. |

Google Play currently permits narrow deterministic, human-defined accessibility
automation but prohibits an ordinary app's use of Accessibility API for autonomous
initiation, planning and execution. The exception is for verified tools whose core
purpose is assisting people with disabilities. A general voice assistant does not
qualify merely because it is useful to some disabled users [S24]. Keep the goal
planner's open-ended API/protocol composition separate from that distribution
boundary; do not mark the whole package `isAccessibilityTool=true` to bypass it.

## Phone control and developer boundaries

| Requested outcome | Ordinary app route | Elevated route or limit |
| --- | --- | --- |
| Brightness, rotation, screen timeout | Applicable `Settings.System` setters with write access; read back actual values | Do not infer Secure/Global authorization from `WRITE_SETTINGS` [S8]. |
| Volume, haptics, torch and playback | Audio/haptic/camera APIs and actual media session | Torch availability can change; another camera session can prevent it. Media dispatch needs outcome acknowledgement if dependent steps rely on playback. |
| Home/back/recents/shade/lock/screenshot | Available accessibility global actions; lock/screenshot actions added at API 28 | Probe the current action list/return value; physical-button and navigation suppression is not universal [S10]. |
| Inspect own execution | Own runtime variables, memory, services/tasks and exit diagnostics | Typed in-app debugger is implementable without OS escalation. |
| Inspect other apps/processes | Limited package visibility, launchable apps, user-granted usage history | `getRunningServices` API 26+ returns own services; task APIs are limited. Do not label these results as all processes [S25]. |
| Stop arbitrary apps | Open package details for an explicit system/user action | Android 14+ `killBackgroundProcesses` only kills the caller's own processes. Force-stop or full process diagnostics require a separately authorized bridge [S25]. |
| Open developer settings | `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` | Opening a page does not enable developer mode, debugging or alter a selected option [S26]. |
| Write developer/Secure/Global values | Read applicable public values; ordinary apps cannot write Secure | A dedicated DPC or authenticated ADB/root/system adapter must declare the actual supported operation; no unvalidated generic setter [S8, S23]. |
| Reboot, kiosk and managed policy | Separate provisioning/delegation capability | DPM reboot (24+) requires device-owner/eligible privilege. Lock task policies require their own DPC/allowlist support [S23, S27]. |
| Toggle Wi-Fi/Bluetooth | System settings/panel or user-approved Bluetooth enable dialog | Target 29+ Wi-Fi and target 33+ Bluetooth direct toggles fail for ordinary apps; DO/PO/system exceptions do not apply automatically [S28, S29]. |

ADB is an explicit developer transport, not an Android runtime permission. Wireless
debug pairing is supported from API 30; USB/host authorization and bridge lifecycle
must be verified. `adb shell` privileges are different from root. Keep bridge
operations typed, allowlisted, bounded and separately disclosed; preserve device
identity and disconnect/revocation state [S30]. No bridge is claimed implemented by
this research. Do not require an external IO Matrix server for local bridge use.

## Connectivity, capture and external-device composition

| Domain | Public API route | Requirements and outcome evidence |
| --- | --- | --- |
| Bluetooth Classic/BLE | `BluetoothManager`, scanner, GATT, RFCOMM, advertising | Target 31+: runtime `BLUETOOTH_SCAN/CONNECT/ADVERTISE` as used. Legacy grants on older OS. `neverForLocation` can filter beacons; protocol/service support and pairing still need adapters [S31]. |
| Wi-Fi discovery/connection | Specifier/suggestion, P2P, local-only hotspot (26+) | Target 33+ nearby-device grant applies to relevant APIs. `startScan/getScanResults` still require fine location and enabled location; scans are throttled [S32, S33]. |
| Wi-Fi Aware and RTT | Aware/NAN (26+), RTT (28+) | Hardware/availability checks; RTT requires suitable APs, nearby/location grants by version and visible/FGS operation. Discovery does not guarantee a usable channel [S34, S35]. |
| LAN protocols | `NsdManager` DNS-SD/mDNS, bounded selected-address sockets | Target 36 uses `INTERNET` unless opted into Android 16 local-network restrictions. Target 37 adds the separate local-network grant; do not request it at target 36. Device protocol/pairing remains separate [S36, S37]. |
| USB host/accessory | `UsbManager`, interfaces, endpoints and transfers | Per-device system authorization lasts until disconnect. A detected USB device is not a working serial/HID/audio adapter; identify descriptors and protocol [S38]. |
| NFC tags/HCE | NDEF, supported tag technologies; `HostApduService` (19+) | `NFC`, hardware and protocol/AID profiles. HCE is not cloning a bank card or provisioned payment credential. Android 16 changes URL-tag dispatch and lets users disallow tag-intent scanning [S39, S40]. |
| UWB | Jetpack UWB, supported device roles and ranging session | `UWB_RANGING` (31+), hardware, secure out-of-band parameter exchange. Background reports are limited; use actual session/capability feedback [S41, S7]. |
| Infrared | `ConsumerIrManager` (19+) | Hardware emitter, `TRANSMIT_IR`, supported carrier range and a real code/protocol profile [S42, S7]. |
| MIDI/controllers/external input | `MidiManager`, input-device and focused key/motion APIs | Device/transport support, selected ports and actual events; global injection needs a different privilege/host [S43, S44]. |
| Screen capture | `MediaProjection` (21+) or explicit accessibility screenshot adapter | Consent each projection session; target 34+ projection FGS declarations and one-use token. Protected content can be blank/refused; react to token stop and resized capture [S22]. |
| Playback audio | `AudioPlaybackCaptureConfiguration` (29+) | `RECORD_AUDIO`, projection consent, same user profile, compatible player usage and capture policy. It does not capture every app or all telephone calls [S45]. |
| Media/files/clouds | Photo picker, MediaStore, SAF/DocumentsProviders | Picker can include eligible cloud providers. Broad multi-provider browsing requires installed document providers or separately authenticated provider adapters [S15, S46]. |

A colour-organ goal therefore composes an explicit audio source, analysis/transform
nodes and a selected protocol sink. A Yeelight LAN adapter does not imply support
for Hue, Matter, Tuya or every Wi-Fi bulb. A transport that can send bytes is not a
device-control adapter. Profiles should state which capabilities were advertised,
which command was accepted and what state was read back. Unknown verification
must remain unknown.

## AppFunctions: future typed interoperability

Android 16/API 36 adds AppFunctions: apps can expose typed operations and eligible
callers can execute them through `AppFunctionManager`. This is a concrete future
adapter option for the goal graph [S47, S48].

`EXECUTE_APP_FUNCTIONS` has normal protection level, but runtime allowlists and
further requirements can still apply [S7]. Declaration is not universal execution
authority. Probe manager availability, access, target function enabled state and
current metadata; handle cancellation and structured errors. Own functions and
functions from other packages have different access conditions. Do not depend on
API 37 discovery APIs in this target-36 build. This document adds no AppFunctions
service, runtime execution or approval claim.

## Verification and resource policy

Keep sensor/audio/capture sessions explicit and bounded; stop listeners, buffers,
sockets and foreground work when their owner ends or access is revoked. Heavy
analysis and network work belong off the input/UI thread. FGS type, runtime grant,
while-in-use eligibility and background-start rules are separate prerequisites,
especially for microphone/camera/location [S14].

Discovery must not transmit sensor streams, screen contents, credentials or app
history to a model by default. A requested model operation needs selected context
and an explicit data boundary. Debugger summaries should reveal state and errors
without revealing vault values, tokens, password inputs or captured private fields.

Acceptance layers: pure profile/plan tests; Android SDK compilation/lint; simulated
API/error paths; emulator where useful; physical device/OEM tests; real protocol and
provider tests. A unit test or permission toggle cannot certify microphone quality,
screen protection, Bluetooth pairing, foreground-service survival, an assistant
gesture or a physical light's state.

## Official sources

The URLs below were read directly; no third-party tutorials were used for platform
claims. Android reference pages also contain APIs newer than target 36: do not infer
availability merely from their appearance on the current documentation page.

- [S1: Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [S2: Sensor reference](https://developer.android.com/reference/android/hardware/Sensor) and [Android 16 health permissions](https://developer.android.com/about/versions/16/behavior-changes-16)
- [S3: Request special permissions](https://developer.android.com/training/permissions/requesting-special)
- [S4: Location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [S5: PowerManager](https://developer.android.com/reference/android/os/PowerManager)
- [S6: BiometricPrompt](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt)
- [S7: Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission)
- [S8: Settings.System](https://developer.android.com/reference/android/provider/Settings.System) and [Settings.Secure](https://developer.android.com/reference/android/provider/Settings.Secure)
- [S9: UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [S10: AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [S11: NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [S12: NotificationManager](https://developer.android.com/reference/android/app/NotificationManager)
- [S13: Schedule alarms](https://developer.android.com/develop/background-work/services/alarms)
- [S14: Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [S15: All-files access](https://developer.android.com/training/data-storage/manage-all-files)
- [S16: VpnService](https://developer.android.com/reference/android/net/VpnService)
- [S17: RoleManager](https://developer.android.com/reference/android/app/role/RoleManager)
- [S18: VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) and [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [S19: Default phone application](https://developer.android.com/develop/connectivity/telecom/dialer-app)
- [S20: Autofill framework](https://developer.android.com/identity/autofill)
- [S21: Credential provider integration](https://developer.android.com/identity/sign-in/credential-provider)
- [S22: Media projection](https://developer.android.com/media/grow/media-projection)
- [S23: DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [S24: Google Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491)
- [S25: ActivityManager](https://developer.android.com/reference/android/app/ActivityManager)
- [S26: Settings actions](https://developer.android.com/reference/android/provider/Settings)
- [S27: Lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)
- [S28: WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager)
- [S29: BluetoothAdapter](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)
- [S30: Android Debug Bridge](https://developer.android.com/tools/adb)
- [S31: Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [S32: Nearby Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- [S33: Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [S34: Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware)
- [S35: Wi-Fi RTT](https://developer.android.com/develop/connectivity/wifi/wifi-rtt)
- [S36: Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [S37: Network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [S38: USB host](https://developer.android.com/develop/connectivity/usb/host)
- [S39: NFC basics](https://developer.android.com/develop/connectivity/nfc/nfc)
- [S40: Host card emulation](https://developer.android.com/develop/connectivity/nfc/hce)
- [S41: UWB communication](https://developer.android.com/develop/connectivity/uwb)
- [S42: ConsumerIrManager](https://developer.android.com/reference/android/hardware/ConsumerIrManager)
- [S43: MidiManager](https://developer.android.com/reference/android/media/midi/MidiManager)
- [S44: InputManager](https://developer.android.com/reference/android/hardware/input/InputManager)
- [S45: Capture video/audio playback](https://developer.android.com/media/platform/av-capture)
- [S46: Photo picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [S47: AppFunctions overview](https://developer.android.com/ai/appfunctions)
- [S48: AppFunctionManager](https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager)
