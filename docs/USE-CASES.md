# Use cases, and what absorbs them

The working method, written down because it only survives if it is:

> One side brings a use case. The other builds the abstraction around **that** case
> and the ones next to it, with the rest of this list in mind.

This file is the "rest of this list". It exists because neither party can hold it in
their head — and an abstraction built for cases nobody wrote down is an abstraction
built for cases nobody has.

Each entry says which abstraction took it, or why none could.

## The rule that separates this from inventing things

An abstraction earns its place when it is **derived from a case that actually
arrived** and makes the **next** case cheaper without making the current one worse.
Anything else is speculative generality, which costs the same and buys nothing.

The proof runs the other way too: when a new case is cheap, the earlier abstraction was
right. Paste-an-MP3-get-a-transcript took one sitting only because the provider
catalogue had been built as *capabilities* rather than as a chat client — the reading
and the transcribing were already there, unused, waiting for somebody to ask.

And the honest half: some cases are not absorbable, and saying so is part of the
method. A gamepad is not a keyboard problem badly modelled. It is a platform with no
API for it.

## Absorbed

| Use case | What took it |
|---|---|
| "no automatic shift, it puts allcaps everywhere" | capitalisation as rules: situation × mechanism × moment, all data |
| "capitalise after a colon" / Title Case / "fix it after the word" | the same rules, no new code |
| "I keep trying to put my key in" | a profile per provider: key, base URL, model, parameters |
| "a dozen providers, each with its profile" | the same profiles; the catalogue was already data |
| "auto-discovery for the unsupported ones" | catalogue merge order: user → fetched → bundled |
| "what are the suggestions computed from?" | word lists as data, bundled *and* downloadable |
| "a language the app ships nothing for" | the same lists; German, Spanish, French, Ukrainian are one tap |
| "paste a photo of a document, get its text" | OCR capability + described-call engine, already present |
| "paste an MP3, get the transcript" | the transcribe capability, already present |
| "Ctrl+Z does nothing" | the keyboard's own undo history, with the chord as fallback |
| "the nav bar covers the keyboard" | window insets asked for rather than assumed |
| "AltGr should give Polish letters" | AltGr as a layer; the characters are a fact about a language |
| "every permission buys something, none is required" | the ability registry, with an enforced `without` for each |
| "the floating block drifts, Esc is stretched in landscape" | element geometry: size physical, position as shares of free space, docked panel avoided |
| "the toolbar does nothing on Workbench" | one set of rows and one panel renderer for every shape of keyboard |
| "the settings screen is squeezed behind the keyboard" | pieces report where they are; only the docked one reserves space |
| "select many posts in a feed" | `sweep`: one verb, four ways feeds select (checkboxes, long-press-then-tap, tap, match) |
| "Back, Home, notifications from the keyboard" | verbs as data, bound to any key as `do:<line>` |
| "a mouse for a remote desktop" | pointer overlay + trackpad panel; without access, the trackpad moves the text cursor |
| "the AI should know what post I am answering" | `read_screen to=ai`: screen text as context, never as the input |
| "media, volume, torch, open an app" | the same verbs; they need no permission at all |
| "I tap the arrow back to a typo and miss by a few" | cursor magnet: a lone unknown word near where the presses stop; four strengths |
| "the long-press delay should adapt to me" | a tuner kept clear of plain taps, nudged by deleted alternates and retried presses |
| "capitals at names and surnames" | `proper_noun`: a situation learned from what the user capitalises mid-sentence |
| "shake / flip / tilt / volume keys should do things" | the engine's inputs: wires from any input to anything a key can do |
| "show it once, have it repeat" | macros: recorded as performed, pauses kept only around actions on other apps |
| "lock touch, screen and buttons so a talking app can go in a pocket" | pocket lock: overlay + key filter + proximity guard; quick settings tile, `do:pocket_lock` |
| "triggers with the keyboard closed" | EngineRuntime: one engine per process, fed by the keyboard and the accessibility service; wire scopes |
| "a spoken password to unlock / lock" | the `voice` input with a phrase; the pocket lock's phrases are shorthand wires |
| "overlay permission for the lock" | a second lock host; abilities grantable more than one way (`Need.AnyOf`) |
| "the keyboard from quick settings with no field, for shortcuts" | a tile that asks the keyboard to show itself; keys go to the app as key presses |
| "shortcuts in the suggestion pool, by context and statistics" | ActionStats (per situation / app / anywhere, with defaults) and `/name` commands |
| "everything a user might want different, in settings" | knobs: every hardcoded number declared once and shown as an expert setting |
| "a keyboard that writes could also read aloud" | Speaker: one per process, pause as stop-and-resume-at-the-word, headset control via a media session |
| "read the article while the phone is in my pocket" | PageReader: read, scroll, read only the new lines; composes with the pocket lock |
| "read with a better voice" | the catalogue's speech capability; placeholders get defaults and known choices |
| "floating controls, potentiometers" | controls as elements: slider, knob, pad, switch — an action with `{v}` in it, and an engine input |
| "any setting from a key, a knob or a wire" | the `set` and `toggle_setting` verbs, through the settings schema's own checks |
| "the phone as a steering wheel for a game streamed from elsewhere" | streams: acceleration → tilt → calibrate → dead zone → curve → UDP, and a receiver that makes it a virtual pad |
| "a model of information, not of formats" | IO Matrix: types, representations, transports and transforms kept apart; provenance carried with results ([IO-MATRIX.md](IO-MATRIX.md)) |
| "a converter of everything into everything — a waterfall spectrogram JPEG into MIDI, a vocal with its words" | `convert`: a graph of small steps (`core/convert/ConvertGraph.kt`) and a planner that finds the cheapest path through the ones that can run now |

## The outputs engine, first half

Everything that leaves the text field is a **verb** in one catalogue
(`core/io/Verbs.kt`): what it means, what it needs, what it does without that. A line
such as `tap 0.5 0.8` or `sweep mode=checkboxes pages=3` names one, and any key, macro
step or toolbar preset can carry it. The platform is touched in exactly one place
(`io/Performer.kt`), so the catalogue can be listed, searched and tested without a phone.

The accessibility service (`io/IoAccessibilityService.kt`) is what most of them go
through. It is off until the user switches it on, listens only to which app is in
front, and reads the screen only when a verb asks. Every verb that needs it still
answers without it: Back becomes the Back key, scrolling becomes Page Down, the
trackpad moves the text cursor, and the rest say what they need and offer the screen
that grants it.

The second half is **inputs** (`core/engine/Wires.kt`): shake, face down/up, four
tilts, a hand over the proximity sensor, the side keys, the keyboard opening and
closing. A wire says `{"on": input, "do": action, "in": [apps]}`, and the action is
anything a key can do — so every input reaches every output, including the ones not
written yet. Sensors nobody wired are never switched on.

Still to come on the same wires: floating controls (sliders, knobs, pads), timers,
network ports and known services, and listening with the keyboard closed (through
the accessibility service).

## The converter

Converting is the first vertical slice of the IO Matrix model — types apart from
formats, transforms apart from who carries them out, a planner that keeps every way,
and a history carried with every result. See [IO-MATRIX.md](IO-MATRIX.md).

Things that are not obvious:

- **A spectrogram picture has no single layout.** Time may run right, left, down (a
  waterfall read from the top) or up (as radios draw it, newest at the top); the
  frequency axis may be logarithmic or linear, over any range, and flipped. All of it
  is a parameter (`time=`, `scale=`, `low=`, `high=`, `flip=`, `seconds=`), as is the
  part of the picture that is the spectrogram (`area=`) and whether dark means loud
  (`invert=`). Parameters nobody gave are recorded as assumptions; a picture drawn here
  carries its own, so reading it back assumes nothing.
- **Each row belongs to the nearest semitone and to no other.** On a linear axis low
  semitones are closer together than one row, so some get no row at all.
- **A sung note is a stack of lines.** The melody from a recording folds overtones
  into their note and keeps the loudest line at each moment (`harmonics=`, `voices=`).
- **"Vocal with its words"** is a MIDI file with lyric events (`lyrics=on`, which needs
  a transcription provider, because it sends the recording).
- **MIDI into text is its words**, not its note names: note names are a way of
  *keeping* notes (`to=notes`), not a conversion into text.
- **A spectrogram or a scalogram** (`to=spectrogram`, `to=scalogram`) are two ways to a
  picture of a sound's field: Fourier (even resolution) or Morlet wavelets (finer in
  time high up, finer in pitch low down; `omega=` trades one for the other).
- **Text hidden in sound** (`use=typeset`) is text set in one line, read as a
  spectrogram on a linear axis, and played.

## Not absorbable, and why

**A gamepad for a remote machine.** An input method can send key events and nothing
else — `InputConnection` has no path for a `MotionEvent`, so analog sticks and triggers
cannot be delivered at all. Buttons sent as gamepad keycodes arrive without
`SOURCE_GAMEPAD`, which clients filter on. And during a game there is no focused text
field, so the keyboard is not on screen to send anything.

The ways that do exist all leave the keyboard behind: a receiver on the host that
creates a virtual pad (real analog, needs software on the PC), or Bluetooth HID (real
analog, only to a machine in the room). Both say the same thing — this is a **different
surface of the same app**, not another keyboard mode.

**Locking the power button.** The system handles it before any app sees it. The
pocket lock takes touch and the volume keys; the power button still switches the
screen off, which for most talking apps is harmless.

**Capturing audio that is playing.** `MediaProjection` exists, and apps are free to
refuse capture. Most do.

## Open

- More inputs on the same wires: floating controls and potentiometers, timers,
  network ports and known services, gyroscope and light, inputs while the keyboard
  is closed
- Reading text drawn as pictures on screen (the service already may take screenshots)
- A pointer speed and acceleration setting; long-press on the pad itself

- Model parameters in expert mode: standard ones and model-specific ones, separately
- The suggestion tree: descending is a longer slice, or regenerating from what was taken
- The touch model as a distribution rather than a fixed offset per key
- On-device OCR and speech, which is the only thing that would let paste conversion be
  on by default without sending anything anywhere
- `aiCompletionEnabled`: still adds text to the *correction* row, which the two-lane
  split exists to prevent
- The default catalogue address: proposed in the wizard rather than silently set, so a
  fresh install still reaches nowhere on its own
