# Goal assistant and reactive-light implementation checkpoint

This document records the initial 2026-09-29 slice. The integrated host,
phone bindings, system voice service and validation state are recorded in
[RESUME.md](RESUME.md), [PHONE_TOOLS.md](PHONE_TOOLS.md) and
[SYSTEM_VOICE_ASSISTANT.md](SYSTEM_VOICE_ASSISTANT.md).

2026-09-29. Source implementation on `feat/goal-assistant-2026-09-29`, stacked on
capture PR #3 at `b3db2c8`. **Not a new verified Android release. Keep APK 4.4.2.**
The open-ended product contract remains [UNIVERSAL_ASSISTANT.md](UNIVERSAL_ASSISTANT.md).
This is the first executable slice, not a claim that every phone capability or
arbitrary external API is already integrated.

## Shared agent lifecycle — 2026-10-02

`GoalAssistantActivity` routes both typed edits and speech transcripts to the same
`GoalAgent.editGoal`. The source is recorded as TEXT or VOICE. A new edit revokes
prior planner turns, proposals and execution approvals. `beginPlanning`/`finishPlanning`
accept only the current goal, turn identity and controller; cancelled/late/other-host
results cannot replace it. Validated alternatives are detached from caller mutation.

`selectPlan` creates the existing `GoalSession`. Review, begin, receipt and observed
effect confirmation still use that session's frozen catalogue, current capability
evidence and typed `GoalPerformer`/`Performer`. Pause/cancel revoke active turns and
approval epochs while preserving uncertain dispatched effects honestly. Ten new
GoalAgent tests plus the existing thirteen session-binding tests cover these paths.

The optional [ecosystem agent bridge](ECOSYSTEM_AGENT_BRIDGE.md) injects a proposal
Tool into the real Loom prototype and exchanges existing version-1 plan definitions.
The existing Android import feeds the same controller and review path. The current
bridge transports proposals; remote phone execution and observation return remain
future integration work.

## Open the host

Two new default action profiles open **Goal assistant** and **Colour organ setup**.
Existing customized profiles are not overwritten. The corresponding custom-key
commands are:

```text
do:open iomatrix://assistant
do:open iomatrix://colour-organ
```

Both exported activity links open their own UI only. Caller extras, URI parameters,
assist context and imported files cannot start a recording, grant permission or
execute a plan. The existing capture link and typing path are unchanged.

The assistant supports text and explicit on-device push-to-talk on API 31+ when a
recognizer is installed. Speech becomes an editable transcript, not an action.
No on-device recognizer means an explained text/previous keyboard-dictation fallback,
not a silent network recognizer. Microphone permission is requested in context;
after granting it the user must press Speak again. No hotword/background listening.

An `ACTION_ASSIST` activity and explicit `RoleManager` selection are wired. Android
allows an activity-based assistant role; this checkpoint does not register a dummy
recognition service or claim a complete `VoiceInteractionService` implementation.
Actual role eligibility, system gestures and OEM behavior still need device testing.

## From a goal to a reviewed plan

**Plan with configured model** uses the existing `AiConfig`/`AiClient`, rather than
another provider client. The explicit confirmation identifies provider/model/host
and what is sent: entered goal, canonical action descriptions and capability status.
No editor, screen, clipboard, accounts, bulb address or credentials are added to
model context. A cancelled call's late result is ignored; an HTTP request already
sent through the existing client can still finish remotely.

`GoalCatalogue` projects the existing `Verbs.ALL` and `Abilities.ALL`. The first
assistant host executes six audited verbs: `volume_set` (music), `brightness`,
`media`, `vibrate`, `open` and `search`. Other existing verbs remain in the proposal
catalogue, explicitly without an audited host execution path. Unknown operations
remain named integration gaps. Neither is relabelled as a fundamentally impossible
goal. This is a bounded initial host, not a permanent product command whitelist.

The model may propose multiple routes and unimplemented steps with API research
leads. Those leads are labelled unverified; this version does **not** automatically
browse documentation, install integrations, discover arbitrary device APIs or run
model-generated scripts/HTTP bodies. Known internal links only open setup. A plan
that opens a panel must not falsely claim the final task is thereby completed.

Offline, explicit commands work without a model, for example:

```text
do:volume_set level=0.3 stream=music
do:brightness level=0.4
```

`GoalPlan`, `Requirement` and `GoalSession` provide:
- AND/OR prerequisites with retained nested evidence and distinct readiness states:
  ready, missing permission, setup, missing adapter, unsupported device and unknown.
- Bounded DAGs (up to 32 steps), dependency validation, typed/limited arguments,
  detached plan snapshots and hashes binding approvals to actual parameters.
- Per-step review, fresh foreground/unlocked and capability checks, expiring
  approvals, one dispatch per ticket and invalidation on pause/stop/edit.
- Separate requested-but-unverified, platform-verified, user-confirmed, failed and
  cancelled states. A dependent step cannot continue after mere dispatch.

The bridge calls the existing `Performer` rather than a second action executor.
Volume and brightness are read back with the performer's actual quantization/clamp.
Media/navigation requests have no general effect receipt in that executor; the UI
therefore asks the user to confirm the observed effect before dependent steps.
Stopping a potentially dispatched operation records uncertainty, not fake rollback.
The first host requires per-step confirmation. Broader plan-bound approval policies
can be added when execution/verification coverage supports them; arbitrary future
model changes must not inherit authorization.

Plan JSON import/export uses the platform `org.json`, a strict field schema, a
64-Ki-character cap and a depth preflight. Import binds to the goal entered locally
and restores no permission, device selection, approval or runtime status. Files can
contain the plan's user-authored parameters/reasons; save them deliberately. The
original goal and app/provider credentials are not added as export fields. Source
implements eight Robolectric codec tests that still require the real Gradle run.

## Concrete composition: a microphone-driven Wi-Fi light

The explicit **Colour organ setup** panel currently uses one provider adapter:
**Yeelight LAN control with advertised `get_prop`, `set_music` and `set_scene`**.
This does not identify the user's bulb or imply support for every Wi-Fi bulb.
The assistant can open the panel; it does not silently select hardware or start it.
The session still requires a user-entered target and a separate Start confirmation.

The signal path is:

```text
mono microphone PCM16, 16 kHz
  -> RMS level (waveform, phase and frequency detail are discarded)
  -> existing Matrix Sample / Stages.Smooth
  -> configurable level-to-colour/brightness mapping
  -> rate-limited, single-slot latest-value handoff
  -> LightSink
  -> Yeelight native LAN music channel
```

This is **level-reactive colour**, not frequency-band analysis, tempo estimation or
beat tracking. There is no model call per audio frame or colour update. The colour
mapping adds a presentation policy; it does not discover a colour inside the music.
`ReactiveLightPolicy` bounds gain, brightness, frequency and duration. Default maximum
brightness is 30%, rate 10/s and duration 120 seconds. Current session controls reset
on recreation; persistent reusable light profiles are not yet implemented.

`LightSink` separates provider transport from PCM analysis and mapping. The explicit
session uses the Matrix smoothing primitive, but these source/sink adapters are not
yet dynamically routed by the general batch conversion planner or saved as Wires.
Further providers should reuse this path, not duplicate a colour-organ engine.

The Yeelight connection:
- Accepts only an explicitly entered numeric RFC1918 IPv4 address, with conservative
  host-octet restrictions. No DNS, public-host search or subnet scanning.
- Sends a unicast discovery request to that address. The response must agree on
  source/Location/port and declare the required identity/capabilities. Devices that
  do not answer this unicast route are not silently assumed compatible.
- Uses the fixed native control port, bounded replies, correlated request IDs and
  readback of the current power/brightness/colour state before starting music mode.
- Binds the short-lived reverse music listener to the interface used for that route,
  accepts only the selected bulb's IP, and closes setup sockets after connection.
- Writes typed colour/brightness messages on the documented music channel. A native
  music-mode write has no physical-effect acknowledgement here; counters mean sent
  commands, never verified changes.

The manufacturer protocol is unencrypted/unauthenticated. IP pinning is not
cryptographic device authentication: **trusted LAN only**. The app's global HTTP/TLS
policy is not relaxed. The selected firmware may need LAN control enabled in its
own app and may differ from the protocol document; physical tests remain mandatory.

Stop, pause, error and timeout close the mailbox and sockets; the bounded,
non-blocking microphone loop releases AudioRecord on cancellation. A main-thread
watchdog can close a stalled music write independently of its worker. Old unsent
colours are dropped, not queued for delayed playback. No foreground/background
recording service is added. The stop policy deliberately leaves the last bulb state
in place: an unconditional restore could overwrite a later user's change. There is
no transactional rollback or synchronized device-clock/beat claim.

A synthetic preview uses neither microphone nor network. Actual app-audio playback
capture, other audio sources, frequency/beat analysis, IPv6/discovery pickers, other
bulb protocols, profile persistence and full streaming-graph planning remain work
for subsequent slices, not hidden fallbacks. Failed LAN access is a distinct possible
cause from missing firmware support; the UI does not assert which without evidence.

## What was actually checked

- `bash tools/test-goal-core.sh`: **78/78** standalone JVM methods passed against
  production goal/light mechanisms. Includes approval revocation/replay, cancellation,
  nested prerequisites, parser/argument bounds, PCM/mapping, latest-value behavior,
  target/discovery constraints and protocol frame limits. The runner supplies only
  the JUnit annotation; it does not emulate Android or claim a complete test suite.
- `bash tools/test-capture-core.sh`: **51/51** existing capture regressions passed.
- A local source syntax/type smoke compile used minimal signature stand-ins
  (harness retained in the accompanying validation archive). It is **NOT an Android SDK/API compatibility build,
  runtime test, permission check or physical-device test**.
- XML/JSON wiring parsed; original manifest/profile entries were preserved except
  the described additions. Typed outgoing light request fixtures were independently
  parsed as JSON. Eight Robolectric JSON tests were written, **not run here**.

The environment had no Android SDK/Gradle/full dependency checkout. Direct GitHub
clone failed DNS resolution; attempts to retrieve build dependencies also failed.
No full Gradle compile/lint/project suite, actual configured-model call, phone
microphone, system assistant-role or physical-bulb acceptance ran. No APK, signing
change, dependency/SDK upgrade, merge or release was produced.

Next gate: run the repository-version
`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, fix concrete
compiler/test diagnostics, then test API 24/31/36+ lifecycle/role/permission flows and
a real supported bulb (including LAN isolation, denial/revocation, disconnect and
stop). Do not promote this source checkpoint into a verified release before then.

## Primary implementation references

Checked on 2026-09-29; recheck for the target SDK and actual device/firmware.

- [Assistant-role alternatives](https://source.android.com/docs/core/permissions/android-roles)
- [RoleManager](https://developer.android.com/reference/android/app/role/RoleManager)
- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
- [Local-network permission rollout](https://developer.android.com/privacy-and-security/local-network-permission)
- Yeelight, *Inter-Operation Specification* (manufacturer-authored protocol, 2015):
  [manufacturer URL](https://www.yeelight.com/download/Yeelight_Inter-Operation_Spec.pdf);
  [mirror actually read](https://downloads.vodnici.net/uploads/wpforo/attachments/170/448-YeelightInter-OperationSpec.pdf).
  Music mode and `set_scene` were checked in the PDF's page images; do not treat its
  age or declared protocol as proof of current firmware support.

The current target remains SDK 36: no SDK 37-only local-network permission is added
prematurely. Handle denied/blocked access now and adopt the appropriate permission
or picker when upgrading the target, following current platform guidance.
