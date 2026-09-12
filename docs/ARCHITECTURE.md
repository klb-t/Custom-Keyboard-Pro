# Architecture

## The idea

A keyboard is a rendering of a document. `LayoutDef` describes every key, what it
looks like, and what it does under each way of touching it. Everything else — the
renderer, the hit test, the popups, the action executor — reads that document and has
no opinions of its own.

That has one practical consequence worth stating plainly: a layout a user writes can
do everything a built-in layout does, because they are the same kind of object. There
is no privileged path.

```
   LayoutDef ──► KeyPlacement ──► KeySurface ──► KeyAction ──► CustomKeyboardIme
   (data)        (geometry)       (touch)        (intent)       (effect)
```

## Packages

| Package | Holds |
|---|---|
| `core.layout` | The layout model, its JSON codec, key geometry and hit testing, the built-in layouts, and the two authoring paths (bitmap and model-written). |
| `core.config` | `Settings` — one immutable snapshot of every option — and the store that persists it. |
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

**One action switch.** `CustomKeyboardIme.perform` is the only place a `KeyAction`
becomes an effect. To know everything this keyboard can do, read that function. To add
a capability, add a case there and a variant to `KeyAction` — and it is immediately
bindable to any key, any swipe, any panel button, and expressible in layout JSON.

**One editor.** Every change to the user's text goes through `EditorController`. The
rules about what backspace removes, when a capital is implied, and what counts as a
password field are stated once.

**One settings snapshot.** The settings app and the IME read the same `StateFlow`.
They previously read two different `SharedPreferences` files, so nothing in settings
had any effect; that class of bug is now structurally impossible.

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
2. Anything that leaves the device is off by default and needs the user to supply
   their own provider.

## Building

There is no Gradle wrapper binary in the repository. CI provisions Gradle through
`gradle/actions/setup-gradle` and runs `gradle` directly. Locally, use any Gradle 9.x:

```
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

A release build signs only when a keystore is actually present; without one it
produces an unsigned APK rather than failing.

## Settings are data too

`Settings` is still a Kotlin data class, but nothing reads it field by field any more.

`SettingsSchema` derives the complete list of settings **from the persistence codec** —
`SettingsStore.toJson(Settings())` is the authoritative statement of what exists and
what type it is. A field added there gets a key, a type, a control and a search entry
by existing. A small hand-written table adds only what JSON cannot know: a readable
label, a slider range, which strings are really enums, which are secret.

`SettingsStore.setByKey` / `getByKey` are the door that lets code written before a
setting existed still change it. Three things go through that door:

- **AllSettingsScreen** renders the whole schema with a search box — "nothing is
  hidden", structurally rather than by discipline.
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

## Diagnostics

The crash handler is installed by the `Application`, not by whichever component starts
first. That matters more than it sounds: an Activity's field initialisers run before
its `onCreate`, so a handler installed in `onCreate` cannot see them — which is exactly
where a bug hid once, producing a report of a crash alongside a log containing no
crash and no process restart, both true and both useless.

