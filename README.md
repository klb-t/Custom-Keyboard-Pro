# IO Matrix

IO Matrix is a configurable Android input and automation layer. Its first host is a
keyboard: layouts, gestures, settings and action bindings are data that users can
inspect, edit and reuse. The same action contracts support phone tools and a
voice/text assistant that proposes plans, checks prerequisites and executes reviewed
steps.

Core typing works offline without an account, model, accessibility service or vault.
Android 7.0+ (API 24) is supported; the system voice-service host and explicit
on-device speech route require Android 12+ (API 31) and an available local engine.

## What is implemented

| Area | Available in source | Acceptance and remaining scope |
|---|---|---|
| Keyboard and layouts | JSON layouts; visual geometry editing; colour masks; screenshot tracing; optional model authoring; modifiers, compose sequences, macros and swipe/chord bindings | Editor, terminal, multi-touch and OEM behavior need device coverage. Glide typing is not implemented. |
| Settings and profiles | One canonical settings model; Basic/Advanced/Expert/Debugger views; layout/panel/key inheritance; validated partial imports; contextual controls | Debugger exposes registered variables. Profile sharing transfers configuration, not permissions or credentials. |
| Matrix and automation | Explicit data types, representations, transports and transform provenance; ranked conversion routes; Wires, sensors and network streams | Availability depends on configured sources and adapters. Arbitrary API discovery and general streaming-graph composition remain open. |
| Voice and goal assistant | Editable on-device transcripts; typed plan proposals; prerequisite/DAG validation; reviewed execution through shared action adapters; explicit outcome states | Speech alone does not authorize an effect. System role selection, recognition Binder behavior and physical effects need device acceptance. |
| Phone tools | Local device/app/sensor inventories, bounded foreground sensor sessions, contextual access setup and typed platform actions | An opened settings screen grants no access. Privileged ADB/root/device-owner execution has no connected adapter. |
| Reactive light | Explicit microphone RMS → smoothing → colour/brightness → Yeelight LAN music-mode session, with synthetic preview | This is level-reactive output. Physical bulb/firmware acceptance, beat analysis and additional protocols remain open. |
| Capture and clipboard | Reviewed accessibility-tree/DOM capture, image-byte retention, persistent Trash and batch Undo | Only exposed or deliberately inspected content is captured. Browser helper is a standalone script; receiving apps may accept only one clipboard item. |
| Models, media and vault | Capability-first provider setup; optional chat/OCR/speech/embedding routes; user-granted local/cloud files; authenticated vault/Autofill, backup and manual sync | Provider, account, real Keystore, browser Autofill and cloud grants need their own acceptance checks. Vault sync and payment processing are not implemented. |

This table describes implementation scope, not certification of every integration.
[Current recovery and validation state](docs/RESUME.md) identifies the source revision,
build evidence and remaining gates. Dated receipts are retained in
[development history](docs/history/README.md).

## Start using it

1. Install a verified APK from the
   [repository releases](https://github.com/klb-t/Custom-Keyboard-Pro/releases), or
   build the current source below. Check the release or recovery receipt for the
   exact version and validation state.
2. Open **IO Matrix**, choose **Enable the keyboard**, then **Choose this keyboard**.
   The default layout is usable immediately.
3. Use the toolbar's settings button for layout and contextual customization;
   long-press it for Expert controls. Features requiring accessibility, microphone,
   storage grants, a model or special access explain their prerequisites in context.
4. Open **Goal assistant** from the action panel for a text goal or explicit
   push-to-talk. System assistant selection is available from that workspace.
   Review the proposed steps and their effects before execution.

Layouts support tap, long press, double tap, hold-repeat, eight swipe directions and
chords. Shift, Ctrl, Alt, Meta, Fn and AltGr have momentary, one-shot, toggled and
locked states with visible indicators. Real key events support terminals and remote
desktops. Built-in English/Polish suggestions and local dictionary learning work
without a model.

Optional AI uses configured OpenAI-compatible, Anthropic or Gemini routes, including
local endpoints such as Ollama, LM Studio and vLLM. System dictation, an explicit
on-device-only route and remote Whisper-compatible endpoints have distinct privacy
and setup requirements. See [provider setup](docs/PROVIDER_ONBOARDING.md) and
[system speech lifecycle](docs/SYSTEM_VOICE_ASSISTANT.md).

## Build and verify

The project declares Gradle **9.7.1**, JDK **17**, Android SDK **36.1** and target SDK
**36**. There is no committed Gradle wrapper binary. The pinned local bootstrap and
build scripts restore the exact toolchain and run compilation, unit tests, lint and
APK assembly in separate stages:

```sh
python3 tools/bootstrap-android.py --accept-licenses
bash tools/local-android-build.sh
```

With an existing matching toolchain, the standard Gradle gate is:

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. See
[local build details](docs/LOCAL_ANDROID_BUILD.md) for environment setup, logs,
focused tests and constrained-memory builds. Unit tests, lint, assembly and device
acceptance establish different things; the latest result belongs in the current
receipt rather than a permanent badge.

`debug.keystore` is intentionally versioned **only for debug builds**, so debug APKs
from another machine can update an existing installation without losing local test
state. It is not a release-signing credential and must never be reused for a
production release. Release signing requires a separate supplied keystore.

## Architecture and privacy

Hosts share `Verbs`/`Performer` and audited platform adapters. Keyboard layouts use
`LayoutDef`, resolved geometry and a common editor controller. Settings retain
ownership and inheritance provenance. Matrix transformations distinguish the data
from its representation and record lost, inferred and generated information.
[Architecture](docs/ARCHITECTURE.md) explains the runtime paths;
[the documentation index](docs/README.md) maps the contracts and feature guides.

Password fields and editors requesting no personalized learning are excluded from
suggestions, learning, macro capture, clipboard history and model context. User
content leaves through configured features or an explicit request; public catalogue
refreshes send neither typed content nor API keys. Generic system dictation follows
the installed recognizer's policy; an explicit local route is available when Android
reports an on-device engine.

Settings use a device-bound Android Keystore key and are excluded from backup;
hardware backing depends on the device. Portable profiles omit API credentials.
The separate vault requires device authentication and has explicit recovery limits:
read [vault/Autofill](docs/VAULT_AND_AUTOFILL.md) and
[portable vault backup](docs/VAULT_BACKUP.md) before relying on it as a sole store.

## Direction

The long-term assistant target includes advanced phone capabilities, external
devices, API and prerequisite discovery, composed execution and effect verification.
A music-responsive Wi-Fi light is one concrete composition scenario. The current
adapters are incremental steps toward that target, not a permanent command-list
boundary. See [universal assistant requirements](docs/UNIVERSAL_ASSISTANT.md).

[ECOSYSTEM.md](ECOSYSTEM.md) records iOmatrix's possible role across the wider
projects. Shared representations and procedures do not transfer access or execution
authority; those integrations remain independently scoped.

## Licensing

This project is **source-available**, not OSI open-source. Noncommercial use is
licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use
requires a separate written license; see [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md).
Bundled third-party material is documented in [docs/LICENSES.md](docs/LICENSES.md).
