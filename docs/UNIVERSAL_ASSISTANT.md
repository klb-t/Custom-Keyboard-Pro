# Universal capabilities and the goal-driven assistant

User direction recorded: 2026-09-29.
Status: **product requirements and architecture direction, not a completed feature**.

## Product target

IO Matrix is not ultimately a keyboard with a finite list of extra commands. Its
target is to support **all special functions, special permissions and advanced
capabilities of the phone**, and to compose them with applications, services and
external devices. The IME is one host and control surface, not the boundary of the
product. This includes a **voice assistant that analyses arbitrary user goals for
feasibility, required APIs, permissions, integrations and execution steps**, rather
than merely matching spoken phrases to predefined commands.

The working ambition is: the user can ask for anything, and the system tries to
find, prepare and execute a valid route. A request is not out of scope merely
because nobody wrote a dedicated feature for it. A missing adapter is a capability
gap to identify and resolve, not proof that the goal is impossible. Keep alternative
routes and describe concrete blockers when no route can currently be executed.

This is an open-ended coverage target, not a claim that every phone permits every
operation or that every adapter exists. Distinguish unsupported in this build,
missing permission/account/hardware, unverified API, unavailable on this device,
and a demonstrated platform restriction. Never translate all of these into a
generic "cannot do that", or advertise a goal as achieved because a request was sent.

## Capability coverage and permissions

Extend the existing `core/caps/Abilities.kt`, `Need`, `Verbs`, provider discovery,
`Wires`, settings schema and Matrix abstractions. Do not create an unconsumed second
catalogue or a separate execution path exclusively for the voice assistant.

Coverage includes, without making this a closed list: accessibility observation and
actions; overlays and controls; system settings and special app access; notification,
media, screen/audio capture and background-operation capabilities; sensors, camera,
microphone, storage and user-granted data; platform roles including the default
assistant; app APIs/intents; networking, Bluetooth, USB and external-device control.
Advanced or privileged integration routes must declare their actual prerequisites
and deployment context separately, rather than pretending an ordinary runtime
permission enables them. Add newly encountered platform capabilities to the same
coverage process.

For each capability/implementation, represent its input/output or effect, required
API and version, device/provider support, permissions or role, user setup, current
availability, implementation/test status, fallback, privacy/cost and verification
method. Support conjunctions and alternative prerequisites where the real case
requires them. Discovery should report evidence and freshness, not invent endpoints
or assume that a documentation example is an installed, authorized integration.

All capabilities being in scope does not mean requesting all permissions at install
or enabling everything by default. Ask in the context of the chosen task, recheck
access when executing, and retain a useful unprivileged mode. Merely opening a
settings screen or declaring a permission is not implementation of the capability.

## Assistant execution contract

The following is the target workflow; it is not a description of an existing
end-to-end assistant:

1. Interpret the user's goal and relevant, authorized context from speech or text.
   Dictation, fixed voice wires and the semantic goal planner are separate layers.
   The same goal representation should also accept keyboard, UI and other inputs.
2. Discover relevant local capabilities, devices, app/provider interfaces and their
   current state. Check API documentation or other versioned evidence when needed;
   inspect supported operations and prerequisites instead of guessing from names.
3. Produce candidate executable plans, including setup steps, dependency order,
   data transformations and external effects. Analyse feasibility, missing grants,
   API availability, latency, cost, privacy and uncertainty. Preserve alternatives;
   do not stop at the subset of adapters already bundled.
4. Resolve gaps through an existing alternative, user-authorized setup, an additional
   integration or a concrete adapter implementation task with tests. An unimplemented
   or merely model-proposed operation must not silently become executable code.
5. Execute the selected plan through the existing typed, validated mechanisms.
   Obtain necessary grants and appropriate confirmation for consequential effects.
   Treat page/device/document text as data, not authority to change the user's goal
   or grant permissions. Keep a visible stop/cancel path and bounded retries.
6. Observe results, distinguish requested from verified effects, and replan on failure.
   Report partial completion and remaining blockers precisely. Save reusable working
   plans as profiles/graphs, usable without asking the model to rediscover them.

System-assistant integration (for example `VoiceInteractionService` and its session
host) is explicitly in scope, alongside ordinary push-to-talk. It remains a platform
adapter with its own selection, lifecycle and permission requirements. It is not
implied by microphone access or by the current `voice` input in `Wires`.

Separate the model's planning/configuration work from deterministic high-frequency
execution. Do not put an LLM call in every sensor sample, audio frame or light update.
Branching, joins, streaming lifetimes and effect verification extend the existing
model when a real task needs them; they are not solved by renaming the current
single-path conversion planner.

## Required example: a Wi-Fi LED bulb as a music-responsive colour organ

The user explicitly supplied this as an acceptance scenario, not as a request for a
standalone hardcoded "disco mode" or as evidence that a particular bulb is connected.

Goal: "Make this Wi-Fi LED bulb react to the music."

A candidate composed route is:

```text
authorized audio source
  -> audio features (level, frequency bands and/or detected beat)
  -> configurable mapping to colour/brightness
  -> smoothing, limits and update scheduling
  -> authenticated device API/transport
  -> selected LED bulb
```

The assistant must identify the selected bulb, supported controls and a real local,
bridge or provider API. Wi-Fi is a transport, not a universal light-control protocol.
Inspect colour/dimming support, pairing/authentication and usable update behaviour;
keep integrations as profiles/adapters rather than fixing one vendor in the planner.

Select an audio route appropriate to the goal: microphone, a user-provided file or
permitted playback capture. Report that playback capture can depend on source-app
policy and user approval; do not claim every app's audio is readable. A missing live
capture adapter should remain an explicit prerequisite, not a fabricated feature.

Use the measured/declared device limits to schedule updates and handle disconnects
without building an ever-growing queue of stale colours. Provide preview and stop,
configurable brightness/transition limits, and a defined policy for restoring the
prior light state without overwriting a later user change. Offer an explained reduced
mode when only dimming or slow updates are available; do not call it RGB beat-sync.
The same source/transform/sink machinery should then support other authorized lights,
controls and sensors without a second colour-organ-specific execution architecture.

## Existing groundwork versus work still required

At the inspected checkpoint `aa3adf7`, `Abilities.kt` already states that almost every
special permission can buy an I/O capability and requires a reduced form without it.
`IO-MATRIX.md` describes any-input/any-output modelling, conversion planning and
network streams. `Wires.kt` contains phrase-triggered voice inputs. These are useful
building blocks, **not evidence that the goal-driven assistant or bulb integration
above is implemented**. The current Matrix planner is described as single-path
transform planning, not a complete action/setup/effect planner.

Acceptance work should include a versioned capability-coverage inventory, a real
speech/text-to-plan-to-execution slice, permission/setup and failure paths, reusable
plan storage, and the real audio-to-bulb scenario with measured timing and verified
state changes. Test missing APIs/grants, revocation, ambiguous device selection,
unsupported colour control, latency/disconnection and cancellation. Mock/unit tests
must not be reported as phone, voice-service or real-bulb acceptance.

This requirements update adds no runtime code, permissions, adapter, APK or release.
It does not supersede the current capture build/device gates in `RESUME.md`.

## Related project records and platform references

- [IO Matrix model and planner](IO-MATRIX.md)
- [Use-case-derived development](USE-CASES.md)
- [Existing permission-backed extension direction](HANDOFF_2026-09-27.md)
- [Current implementation checkpoint and remaining gates](RESUME.md)

Primary platform references consulted on 2026-09-29; recheck against the target
SDK/device when implementing:

- [Special permission workflow](https://developer.android.com/training/permissions/requesting-special)
- [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
- [Playback capture and source-app controls](https://developer.android.com/media/platform/av-capture)
- [Foreground-service prerequisites](https://developer.android.com/develop/background-work/services/fgs/launch)
