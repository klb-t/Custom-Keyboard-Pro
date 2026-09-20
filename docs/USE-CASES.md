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

## Waiting on one decision

Reading what is on screen, acting in other apps, and a pointer for a remote desktop all
need an **accessibility service**: the most powerful permission Android has, able to see
every app on the phone. There is no narrower way to do any of them — an input method
reaches the text field and no further. Listed in the ability registry, built by nobody
yet, because a permission of that reach is the owner's call and not the builder's.

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

**Capturing audio that is playing.** `MediaProjection` exists, and apps are free to
refuse capture. Most do.

## Open

- Model parameters in expert mode: standard ones and model-specific ones, separately
- The suggestion tree: descending is a longer slice, or regenerating from what was taken
- The touch model as a distribution rather than a fixed offset per key
- On-device OCR and speech, which is the only thing that would let paste conversion be
  on by default without sending anything anywhere
- `aiCompletionEnabled`: still adds text to the *correction* row, which the two-lane
  split exists to prevent
- The default catalogue address: proposed in the wizard rather than silently set, so a
  fresh install still reaches nowhere on its own
