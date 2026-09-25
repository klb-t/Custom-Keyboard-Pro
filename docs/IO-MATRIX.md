# IO Matrix — the model

IO Matrix takes information from any input, represents it faithfully, transforms it,
routes it, and emits it to any output. The keyboard is one input and one output among
many. This document says what the model is, what is built, and which decisions were
made deliberately and why.

> Preserve information. Preserve optionality. Preserve provenance. Generalize only from
> real structure.

## Four things kept apart

| | question | examples | where |
|---|---|---|---|
| **DataType** | what the information *is* | text, picture, sound, time–frequency field, notes, video, acceleration, control value, event | `core/matrix/Model.kt` |
| **Representation** | how it is kept | UTF-8, note names, MIDI file, PNG, JPEG, WAV, an in-memory field | `core/matrix/Model.kt` |
| **Transport** | how it arrives or leaves | field, selection, clipboard, file, sensor, provider API, UDP, TCP | `core/matrix/Model.kt` |
| **Transform** | how one becomes another | OCR, transcription, STFT, CWT, reading a picture as a field, extracting notes | `core/matrix/Transforms.kt` |

A PNG is not a type — it is how a picture is kept. A spectrogram is not a type either:
it is how a time–frequency field is *shown*. A picture that looks like a spectrogram is
a picture until something reads it as one, and that reading (`read_field`) is an
inference with assumptions ("brighter means louder", "time runs down") and parameters it
needs (the axes), all of which go into the result's history.

The same bytes can be more than one thing: "C4 E4 G4" is text, and it is notes written
out. Which is a question about the content, answered by trying: an `Interpretation` per
reading, the most specific first.

## A transform is not its implementation

"Write down what is said" is one transform whether the phone or a provider does it.
What it does to the information — what it keeps, throws away, aggregates or makes up,
whether it is a calculation, an estimate or an invention, whether it can be undone — is
a property of the transform (`TransformSpec`). What it costs and whether the content
leaves the phone is a property of each way of doing it (`Implementation`). A provider
step names the *capability* it needs, never a provider.

`TransformSpec` carries: types in and out, mapping (deterministic / stochastic /
inferential / generative), changes (preserved / discarded / aggregated / synthesized /
externally added — each saying *what*), invertibility (exact / conditional /
approximate / impossible) and its inverse, assumptions, the parameters it requires,
facts it needs about its input, facts that make it pointless, facts it produces.
Unknown stays unknown: a cost that is not known is `null`, scored as middling and never
as free; a confidence not reported is `null`, never "certain".

## Provenance is part of the data

Every result carries a `Provenance`: where the input came from and how it was taken,
then one `Record` per step — the implementation, where it ran, every parameter it ran
with, which of them nobody gave (so a default stood in: an *assumption*), what it
assumed about the world, what it changed. `origins` keeps every kind of origin in the
history, so an inference early on is never hidden behind calculations after it.

Histories are written into files that have room — a PNG text chunk, a WAV INFO comment,
a MIDI text event — and read back. So a spectrogram drawn here, converted again later,
is read in the layout it was drawn in (a fact, not an assumption), and is known to be a
spectrogram (so nothing tries to read text off it). The `provenance` verb shows the
history of whatever is on the clipboard.

## The planner keeps every way

`Planner.plan` enumerates every way from the input's readings to the goal type through
transforms that can run now, and ranks them by an explicit `Policy` (weights for
latency, compute, energy, network, money, information thrown away, information made up,
leaving the phone, estimating, inventing). The best is the default; the others are
kept, counted in the notice and listed in expert mode, and any can be chosen with
`use=` or excluded with `avoid=`.

Choosing a default is not the arbitrary choice the principles warn against: a button
has to do something. What makes it acceptable is that the policy is written down, the
alternatives are visible, and nothing irreversible or made up is chosen silently —
generative steps are never planned unless asked for, and steps that leave the phone
only through providers the user set up (or never, with "Convert only on the phone").

Facts flow along a way. A rendered spectrogram produces the fact `spectrogram`, and OCR
is pointless for it; resynthesized or synthesized sound produces `tones`, and
transcription is pointless for it. Without that, "recording → text" with only a picture
reader available would be planned as *draw the spectrogram, read text off it* — a path
in the graph and nonsense in the world. Asking for a way explicitly overrides
pointlessness: hiding text in a spectrogram is reading a picture of text as one, on
purpose.

## Tests that try to break the ontology

`MatrixTest` holds the architecture's boundary cases, written to fail if a wrong idea
creeps in rather than to catch a bug:

- a format is never a type (`png`, `waterfall`, `spectrogram` are not types);
- a picture is not text until something reads it, and that needs a reader;
- a spectrogram this app drew is never read for text;
- text out of a recording is what was said, never a detour through a picture;
- note names into MIDI is a change of form, not a conversion;
- made-up content is never a hidden default;
- a spectrogram and a scalogram are two ways to the same kind of picture, both kept;
- a waterfall is redrawn in another layout by way of its field;
- every provider step names a capability, never a vendor.

## Built so far

- Converting (`convert`): 20 transforms over 9 types, implementations on the phone and
  at providers, provenance written into PNG/WAV/MIDI, the `provenance` verb.
- The time–frequency field as the information behind spectrograms and scalograms:
  STFT, Morlet CWT (computed band by band in the frequency domain), rendering in any
  orientation, reading pictures back, notes out of fields, fields out of notes,
  resynthesis.
- The engine's wires (`input event → action`) are the event case of the same model: a
  gesture is `detect_gesture: acceleration → event` (an inference with thresholds as
  its assumption), a threshold on a control value is `control → event`.

## Streams: values, continuously

The engine used to reduce the accelerometer to events — shake, tilt left, face down.
A stream keeps the values: `acceleration → tilt → calibrate → range → dead zone →
curve → smooth → sink`, each stage an instance of a transform (`tilt`: acceleration →
control, assuming gravity dominates; `shape`: control → control). Written in
Settings › Converting and streams:

```json
[{"id": "steer", "from": "acceleration",
  "via": ["tilt wheel", "calibrate", "range -45 45", "deadzone 0.05", "curve 1.5", "smooth"],
  "to": ["udp 192.168.1.20:26760"]}]
```

- **Sources**: `acceleration`, `control:<element id>` (a floating slider, knob or pad),
  `net:<channel>` (next).
- **Sinks**: `udp host:port` sends `io <id> <values>` lines (newest value wins while
  the network is busy — coarser rather than later); `do <action with {v}>` runs any key
  action with the value put in, only on real changes and at most ~30 times a second;
  `events level` turns crossings into engine inputs `stream:<id>:high|low|center`, so
  wires hear a held tilt as they hear a shake.
- **Provenance** is kept per stream, not per reading: a hundred readings a second each
  carrying a history would cost more than the readings. `streams` shows each stream's
  path and whether it leaves the phone.
- **The wheel angle** is the rotation about the screen's normal, right turns positive,
  wrapping at ±180 after calibration — so holding the phone in landscape (−90° at
  rest) is centred by `calibrate`, and `recenter` does it again.
- `tools/io_matrix_receiver.py` turns the lines into a virtual Xbox pad on a PC
  (vgamepad on Windows, uinput on Linux) or prints them.

A wire is the event case of a stream: its source is an event, its only stage "when
it happens", its sink an action.

## The network, both ways

The phone speaks and hears the same line protocol. Out: a stream's `udp` sink. In:
with a port and a token set (expert settings), it listens on UDP and TCP both for

```
<token> io <channel> [<value> …]
<token> do <verb line>          # only with "Let the network run actions"
```

`io` lines feed streams `from: net:<channel>` and fire wires `on: net:<channel>` with
the values put into `{v}`, `{v1}`… So another phone can steer this one, a script can
press Back, a PC can move a fader — each a stream or a wire, not new code.

A listening socket that can make the phone act is a door, and the defaults keep it
shut: nothing listens without a port, a token of 8+ characters, and a stream or wire
that reads the network (or commands allowed); a wrong token gets no answer; tokens are
compared in constant time; lines are capped at 512 characters, TCP connections at four;
`do` lines are refused unless separately allowed. The token travels unencrypted, which
the setting says: trusted networks only.

## Open, found by the real cases

- **Joins.** "A song → melody with its words" needs two branches of the same recording
  (notes, and transcription) merged. The planner's ways are single paths; the join is
  done as a step of its own and recorded as one. A second real join would justify
  planning over DAGs.
- **Streams with the keyboard closed** rely on the accessibility service keeping the
  process alive; whether every phone delivers accelerometer readings to it in the
  background is not yet known. A foreground service is the fallback if not.
- **Colour maps.** Reading a picture as a field assumes brightness rises with level;
  rainbow maps ("jet") break that, and need a colour → level step of their own.
