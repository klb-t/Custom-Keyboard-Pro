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

"Everything into everything" is kept finite by not writing conversions at all. There
are **kinds** (text, image, audio, MIDI, video) and **steps** between two kinds, each
saying where it runs: in the app's own arithmetic, in the phone's services, or at a
provider. A conversion is a path, found by a cheapest-path search over the steps that
can run right now — so one new step makes every path through it possible at once.

| step | from → to | where |
|---|---|---|
| read the picture as a spectrogram of notes | image → MIDI | here |
| play the picture as a spectrogram (a sine per row) | image → audio | here |
| draw its spectrogram | audio → image | here |
| the melody in the sound | audio → MIDI | here |
| draw the notes as a spectrogram | MIDI → image | here |
| play the notes / the notes' names / notes from names | MIDI ↔ audio, text | here |
| set the text as a picture | text → image | here |
| take the sound / a frame out of a video | video → audio, image | here |
| read aloud to a file | text → audio | the phone's speech engine |
| read the text in the picture, write down what is said | image, audio → text | a provider |
| a provider's voice, a picture or a video of what it describes | text → audio, image, video | a provider |

Things that are not obvious:

- **A spectrogram picture has no single layout.** Time may run right, left, down (a
  waterfall read from the top) or up (as radios draw it, newest at the top); the
  frequency axis may be logarithmic or linear, over any range, and flipped. All of it
  is a parameter (`time=`, `scale=`, `low=`, `high=`, `flip=`, `seconds=`), as is the
  part of the picture that is the spectrogram (`area=`, to leave out axes and labels)
  and whether dark means loud (`invert=`, by itself for mostly light pictures).
- **Each pixel row belongs to the nearest semitone and to no other.** On a linear
  axis low semitones are closer together than one row, so some get no row at all —
  that is what the picture can tell, and reading it as two notes would be worse.
- **A sung note is a stack of lines.** The note, its octave, its twelfth… The melody
  step folds the overtones into the note they belong to and keeps the loudest line
  at each moment (`harmonics=`, `voices=` to change either), at the price of real
  octaves played together.
- **"Vocal with its words"** is a MIDI file with lyric events: the melody from the
  spectrogram, the words from transcription laid over the notes' starts
  (`lyrics=on`, which needs a transcription provider, because it sends the recording).
- **Some steps reinterpret rather than convert.** Text read as note names is only
  used when the text *is* notes, and notes written out as names only when the input
  was a MIDI file or `to=notes` asks for it — so a recording into text is its words,
  never the names of the notes in it.
- **The phone is preferred to a provider**, and the plan — with where it sends things
  — is told before it runs. `use=` goes through given steps (`use=draw` for an AI
  picture rather than typeset text), `avoid=` leaves some out.
- **Text hidden in sound** is text set in one line as a picture, then played as a
  spectrogram on a linear axis, which is what a spectrogram app shows by default.

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
