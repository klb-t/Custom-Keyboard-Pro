# System assistant host — 2026-09-30

This is the Android activation adapter for the existing goal assistant. It adds a
real `VoiceInteractionService`, `VoiceInteractionSessionService` / session and
`RecognitionService`; it does not define a second planner or a phrase-command list.
The system assist gesture opens `GoalAssistantActivity`. The transcript remains
editable; model planning and individual typed effects retain the existing explicit
review/approval flow in `GOAL_ASSISTANT_IMPLEMENTATION.md`.
Since the 2026-10-02 integration, `GoalAgent` owns the shared voice/text planner turn,
proposal and session lifecycle. Voice session visibility and Android microphone
lifetime remain host responsibilities; a transcript never restores an approval.

## Selection and version boundary

The user chooses IO Matrix through the assistant-role/system voice-input selector.
Selection grants no privileged process/developer-mode control. The voice services
are resource-enabled on API 31+ only. API 24–30 keep the `ACTION_ASSIST` activity;
they do not advertise a recognizer that they cannot implement locally.

Metadata uses fully qualified class names because the application package and
source namespace differ. Both interaction services are protected with the platform
`BIND_VOICE_INTERACTION` permission. The top-level service runs in
`:voice_interactor`, starts no microphone, model, detector, network request or data
store, and only disables implicit assist/screenshot delivery. The session service
and goal activity share the normal app process for their ephemeral visibility lease.
The app's existing recognition-service package query and runtime `RECORD_AUDIO`
request remain the prerequisites for actual speech.

The metadata's required recognition endpoint is a **real bounded on-device relay**.
It uses `SpeechRecognizer.createOnDeviceSpeechRecognizer`, which addresses the
framework-selected on-device engine separately from the user's default recognizer.
It uses `<recognition-service>`, never `<on-device-recognition-service>`, and never
registers itself as the framework engine. One active turn bounds even an incorrectly
configured engine route back to the relay: a nested request returns busy, rather
than constructing an endless service loop. Default dictation routing on actual
OEM devices still requires acceptance testing after role selection.

There is no cloud fallback or engine/language download hidden in this host. A
missing local engine or unavailable downloaded language produces an error; typing
and already configured keyboard dictation remain alternatives. Support/model
download requests are not advertised as implemented by the relay.

## Privacy and lifetime

System show bundles, URLs, `AssistStructure`, screenshots, clipboard/account/editor
content and arbitrary incoming activity extras are not imported. The metadata does
not opt into lockscreen voice launch; the session also checks the keyguard before
opening the workspace. The startup service rejects late lifecycle startup work
after retirement.

The session creates only a random visibility lease, not an execution approval. It
disables its own extra window and uses the platform assistant-activity gateway.
Session hide, destruction, replacement or lockscreen display revokes that lease,
cancels pending activity work/approvals and closes foreground recognition. The user
may explicitly switch to the independent foreground workspace. That switch does
not restore an approval, launch a model or start listening. Activity pause/stop/
destruction independently close recognition; edits made during recognition cancel
the pending transcript so a late callback cannot overwrite new typing.

`OnDeviceSpeechTurn` is shared by foreground dictation and the recognition relay:

* At most one start and at most 60 seconds per object, without restart loops.
* Delegate cancel/destruction precede delivery of final results; callbacks after
  close or a hidden activity do not reach the transcript or planner.
* `stopListening` requests final recognition and retains the bounded deadline;
  cancellation drops results. The service closes on client cancellation/destruction
  and screen-off; loss of an unlocked state is checked on every callback/deadline.
* The recognition relay uses `ContextParams` with the caller's attribution source,
  so the platform can check/attribute microphone access through the proxy chain.
* External recognition intents can supply only bounded language/model/partial-result
  preferences. Supplied audio/segmented-stream requests are rejected before any
  engine is opened; they never silently fall back to microphone capture. URI routing,
  callback intents, screen context and execution claims are dropped; device-context
  biasing is explicitly disabled in the delegate request.
* Returned data is bounded to three alternatives of at most 8,000 characters and
  finite/valid confidence values. Raw audio buffers and untyped provider events are
  not forwarded. The relay returns transcripts only, never calls the goal executor.

There is no wake-word detector, enrolled sound model, always-listening microphone,
background recording, lockscreen execution, assistant screenshot capture or
platform-wide arbitrary-code executor in this slice.

## Validation boundary

Added focused tests cover delegate lifetime, repeated start, stop versus cancel,
hidden/late callbacks, terminal ordering, timeout, intent/result data bounds and
session-lease replacement/revocation. A fake engine exercises production adapter
control flow with Robolectric; this does not establish recognizer Binder, OEM role
selection, actual downloaded language, microphone privacy indicators or system
gesture acceptance. Record actual Gradle results in the current handoff after the
combined build completes.

Device checks still required: choose/unchoose the assistant, invoke it from system
navigation, verify no automatic context/audio/model request, grant/deny/revoke the
microphone, unavailable language/engine, stop and timeout, backgrounding and screen
lock, repeated invocations/rotation, native-client dictation through the relay and
late callbacks after cancellation. Confirm the relay does not replace or recurse
into the framework on-device target and that older Android retains text entry.

## Primary platform contracts checked

Checked 2026-09-30; device results may differ by OEM:

* [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
* [VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession)
* [RecognitionService](https://developer.android.com/reference/android/speech/RecognitionService)
* [RecognitionService.Callback attribution](https://developer.android.com/reference/android/speech/RecognitionService.Callback)
* [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
* [AOSP on-device engine selection](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/speech/SpeechRecognizer.java)
* [AOSP assistant role recognition-service requirement](https://android.googlesource.com/platform/frameworks/base/+/c439c7e75e73056e6201fa4f4fe340e715196182/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java)
