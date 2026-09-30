# Architecture

## The idea

A keyboard is a rendering of a document. `LayoutDef` describes every key, what it
looks like, and what it does under each way of touching it. Everything else — the
renderer, the hit test, the popups, the action executor — reads that document and has
no opinions of its own.

That has one practical consequence worth stating plainly: a layout a user writes can
do everything a built-in layout does, because they are the same kind of object. There
is no privileged path.

`LayoutDef` supplies the authored data. `KeyPlacement` resolves geometry;
`KeySurface` recognizes a trigger and retains its original binding. The host
dispatches the resulting `KeyAction`. A phone command then follows canonical
`Verbs` metadata through `Performer` to its audited platform adapter.

Read root [ECOSYSTEM.md](../ECOSYSTEM.md) and
[ECOSYSTEM_ADAPTER_BOUNDARIES.md](ECOSYSTEM_ADAPTER_BOUNDARIES.md) when extending
these contracts across interfaces, learned knowledge or other projects.

## Packages

| Package | Holds |
|---|---|
| `core.layout` | The layout model, its JSON codec, key geometry and hit testing, the built-in layouts, and the two authoring paths (bitmap and model-written). |
| `core.config` | Shared persisted `Settings`, schema/levels/ownership, sparse instance overrides and resolved host-local snapshots. |
| `core.io`, `core.phone` | Canonical verb metadata, typed phone requests, prerequisites and outcome contracts. |
| `io`, `phone`, `assistant` | Host/platform adapters, explicit local phone sessions and reviewed goal/voice execution. |
| `core.text` | Grapheme-safe deletion, word and line boundaries, capitalisation rules, dead-key composition. |
| `core.data` | Room: clipboard history, the personal dictionary, the word-bigram model, text shortcuts. |
| `core.suggest` | Merges local and model suggestions for the strip. |
| `core.ai` | One HTTP client for three provider wire formats, and the catalogue of text tasks. |
| `core.asr` | Dictation: the system recogniser and any Whisper-compatible endpoint. |
| `core.hitmap` | Turning a picture into key rectangles. |
| `ime` | The service, the modifier/layer state machine, the editor controller, feedback. |
| `ui.kb` | The keyboard itself: key surface, panels, themes. |
| `ui.settings` | The settings app. |

## Where the decisions are

**Canonical intents and adapters.** `CustomKeyboardIme.perform` dispatches keyboard
actions, including field editing and modifier state. Commands beyond the field use
`Verbs` and `Performer`; phone requests are validated and executed by `PhoneRuntime`.
The goal assistant reuses those operations with its own reviewed, bounded host.
Extend an existing catalogue/adapter when adding another instance of a capability;
add a new `KeyAction` only for a genuinely new kind of keyboard intent. A declared
operation does not prove that every host has an audited executable binding.

**One editor.** Every change to the user's text goes through `EditorController`. The
rules about what backspace removes, when a capital is implied, and what counts as a
password field are stated once.

**One persisted base, resolved instance snapshots.** The settings app and the IME
read the same `StateFlow` for shared defaults.
They previously read two different `SharedPreferences` files, so nothing in settings
had any effect. Layout, panel and exact authored key/layer instances now hold sparse
typed overrides. The resolver applies the defaults policy and records provenance;
bounded host-local caches avoid JSON work in pointer samples. Basic/Advanced/Expert/
Debugger change visibility, not precedence or permissions. One IO writer coalesces
updates and persists an encrypted AtomicFile; pending/error state distinguishes
visible changes from durable storage. The device-bound key is held by Android
Keystore, with hardware backing where the device supports it. Settings and
credentials are excluded from backup, and portable profiles omit credentials.

**Geometry is computed, not measured.** `KeyPlacement` produces the rectangles, and
the renderer, the hit test and the popups all read them. The old code let Compose
dispatch a click and then re-decided which key was meant, so the key that lit up and
the key that typed could differ.

**Presentation is a transform.** Split and one-handed modes rewrite the layer
(`LayerTransforms`) rather than adding a rendering mode, so hit testing, popups and
the touch model handle them without knowing they exist.

## Privacy

Two rules, enforced in the layers that could break them rather than at call sites:

1. In a password field, or an editor that set `IME_FLAG_NO_PERSONALIZED_LEARNING`,
   nothing is suggested, learned, recorded to the clipboard, or sent to a model.
   `EditorController.isSensitive` is the single check; `SuggestionEngine.update`,
   the clipboard listener and `runAiTask` all consult it.
2. User content leaves through configured features or an explicit request. Public
   catalogue refreshes need no account and send neither typed content nor API keys.
   Generic system dictation has unknown processing locality; the distinct
   on-device-only route is offered when Android reports it available and uses the
   on-device recogniser for both initial requests and retries.

## Building

There is no Gradle wrapper binary in the repository. CI provisions Gradle through
`gradle/actions/setup-gradle` and runs `gradle` directly. Use the declared Gradle
9.7.1, JDK 17 and SDK 36.1; see [LOCAL_ANDROID_BUILD.md](LOCAL_ANDROID_BUILD.md).

```
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

A release build signs only when a keystore is actually present; without one it
produces an unsigned APK rather than failing.

## Settings are data too

`Settings` is a typed Kotlin snapshot consumed by runtime code. Its canonical
schema supplies shared controls and discovery instead of duplicating their lists.

`SettingsSchema` derives the complete list of settings **from the persistence codec** —
`SettingsStore.toJson(Settings())` is the authoritative statement of what exists and
what type it is. A field added there gets a key, a type, a control and a search entry
by existing. A small hand-written table adds only what JSON cannot know: a readable
label, a slider range, which strings are really enums, which are secret.

`SettingsStore.setByKey` / `getByKey` are the door that lets code written before a
setting existed still change it. Three things go through that door:

- **AllSettingsScreen** projects the schema by level and actual owner, with search
  reporting matching options on higher levels. Expert exposes every persisted user
  option. Selected-instance controls share that schema and show effective sources.
- **SettingControl** renders one setting from its description, so every screen has one
  implementation of each kind of control.
- **PanelGenerator** hands a model the whole schema and asks which settings answer a
  request in prose. The model arranges; it never invents. A control naming a key this
  build does not have is dropped before rendering and reported by name — a panel that
  looks real and does nothing would be worse than no panel.

## Discovery

`core/discovery` holds the shape of "ask somewhere, get a list, merge it with what you
had, keep it when the network is gone", and mentions neither models nor HTTP.
`ProviderCatalog` reads providers from a bundled `providers.json`, so adding a provider
is an entry in a file. `AiConfig` resolves its *wire format* from that catalogue rather
than from a `when` over three ids — the three request shapes are the invariant, the
list of providers is not.

A provider is also *what it can do*: dictation, reading text out of a picture, making
one, and so on are fields rather than separate lists, and how to reach each is either a
coded request shape or a described call. The pipeline that keeps the catalogue current,
probes what is actually reachable, and advises somebody who has no account anywhere is
in **`docs/PROVIDERS.md`**, along with the reason the advisor cannot ask a model to do
its job for it.

## Corrections and predictions

Two lanes with different rules, because one changes text that already exists and the
other adds text the user must accept. The modifier registry, what a modifier is allowed
to do, why deciding is not the same as ranking, and why nothing is ever silently
substituted across a difference in cost are in **`docs/PREDICTION.md`**.

## The clipboard keeps bytes

A `content://` URI on the clipboard carries a *temporary* read grant tied to that clip.
Storing the URI produces a history entry that is unreadable by the time anybody wants
it, and says nothing about it. So the bytes are copied at the moment of capture, which
is the only moment it is possible, and handed back out through a `FileProvider` scoped
to one directory. Rows own files. Reviewed bulk deletion moves entries into persistent
Trash with restore, preserves pins/newer copies and keeps referenced image bytes
until actual cleanup succeeds. See [CLIPBOARD_WORKSPACE.md](CLIPBOARD_WORKSPACE.md).

Android's clipboard has one slot and no "add to", so two things copied in two apps can
never meet there. They meet in the history instead: several entries can be emitted as
one multi-item clip. What that cannot do is make the receiving app read them — most
read `getItemAt(0)` and nothing else — so the parts are ordered and a text rendition of
the whole goes first. The degradation is the honest best available, not a shortcoming
of the code.

## Diagnostics

The crash handler is installed by the `Application`, not by whichever component starts
first. That matters more than it sounds: an Activity's field initialisers run before
its `onCreate`, so a handler installed in `onCreate` cannot see them — which is exactly
where a bug hid once, producing a report of a crash alongside a log containing no
crash and no process restart, both true and both useless.


Two more things are checked rather than trusted, for the same reason — the only test
device belongs to the user, so a fault that is silent is a fault nobody finds:

- `LayoutDoctor` reads a layout and reports what would make it draw wrongly, and
  repairs what can be repaired without guessing. See `docs/LAYOUT_FORMAT.md`.
- `SettingsSchema.orphanedMetadata` names entries in the settings metadata table that
  describe a setting which does not exist. A typo there is invisible in every other
  way: the setting it was written for falls back to a generated label and a guessed
  group, which reads as an oversight rather than as a mistake.

Both surface in Diagnostics, and both fail a unit test.
