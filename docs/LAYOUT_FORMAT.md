# Layout format

A layout is one JSON object. It is designed to be written by hand, by the layout
importer, and by a language model, so it forgives shorthand on the way in and is
canonical on the way out.

The authoritative copy of this reference is in the app, under **About & help**, and in
`LayoutGenerator.SCHEMA` — that string is what the model is shown, so it cannot drift
from what the parser accepts without someone noticing.

## Skeleton

```json
{
  "id": "my_layout",
  "name": "My layout",
  "locale": "pl",
  "defaultLayer": "base",
  "layers": {
    "base": { "rows": [ { "height": 1.0, "keys": [ ... ] } ] }
  }
}
```

A layer may also carry `"free"`: keys placed by absolute `bounds` over a background
image instead of flowing in rows. That is what a layout traced from a bitmap uses, and
it is why a key can be any rectangle anywhere.

## Keys

The shortest legal key is a bare string: `"q"` draws `q` and types `q`.

```json
{
  "id": "q", "label": "q", "hint": "1", "width": 1.0,
  "style": "normal|special|modifier|accent",
  "repeat": false,
  "popup": ["ą", "á"],
  "bounds": [0.0, 0.0, 0.1, 0.25],
  "touch": 1.0,
  "tap":       {"type": "text", "text": "q"},
  "longPress": {"type": "text", "text": "1"},
  "doubleTap": {"type": "select", "unit": "word"},
  "swipe":     {"up": {"type": "text", "text": "1"}},
  "chords":    {"shift": {"type": "clipboard", "op": "cut"}},
  "indicators":[{"source": "lock:shift", "style": "dot_top_right", "on": "#FF4ADE80"}]
}
```

Triggers: `tap`, `longPress`, `doubleTap`, `repeatAction`, eight `swipe` directions
(`up`, `down`, `left`, `right`, `up_left`, `up_right`, `down_left`, `down_right`), and
`chords` keyed by another key's id.

## Actions

`text`, `key` (raw key event with modifiers), `modifier`, `layer`, `layout`,
`language`, `backspace`, `delete`, `enter`, `space`, `cursor`, `select`, `clipboard`,
`undo`, `redo`, `panel`, `voice`, `ai`, `macro`, `dead`, `compose`, `unicode`,
`presentation`, `hide`, `settings`, `switch_ime`, `repeat_last`, `none`.

Modifier modes: `momentary`, `one_shot`, `toggle`, `lock` — the distinction the
indicator lamps make visible.

## Indicators

`source` is `mod:<kind>` (held or one-shot), `lock:<kind>` (locked),
`layer:<name>`, or a runtime flag: `caps_lock`, `num_lock`, `scroll_lock`, `ai_busy`,
`asr_listening`, `has_selection`, `password_field`, `composing`, `dead_key_pending`.

`style` is one of `dot_top_left`, `dot_top_right`, `dot_bottom_left`,
`dot_bottom_right`, `bar_top`, `bar_bottom`, `bar_left`, `bar_right`, `outline`,
`fill`, `glow`, `label_tint`.

## Four ways to get one

1. **Colour mask** — paint one flat colour per key. Exact; nothing is inferred. The
   picture becomes the background and each colour becomes a key you then bind.
2. **Trace a screenshot** — on device, offline. Finds rows and keys by looking for the
   gaps. A guess, offered as one.
3. **Read a screenshot with a model** — reads the glyphs and infers behaviour.
4. **Describe it** — "like Hacker's Keyboard but with a lit Caps Lock and swipe-up
   digits" — and the model writes the JSON.

All four end at the same editable JSON, so none of them is a dead end.

## Editing without JSON

Layouts → open a layout → **Keys** lists every key with what it currently does, and
for absolutely-placed keys where it sits — which after a mask import is the only thing
telling them apart. Opening one gives label, hint, icon, width, style, repeat,
long-press alternates, and a picker for the tap and long-press actions covering every
action type above.

It writes through this same parser, so the editor and the JSON cannot disagree about
what a key means, and it preserves bindings it does not show: editing a tap action will
not discard a swipe you set up by hand.

Swipes, double tap, chords, macros and indicator lamps are JSON-only for now.

## Elements: a layout made of pieces

A layout is not always one panel. It can describe its own pieces, each placed
differently:

```json
{
  "id": "workbench",
  "layers": { "base": {...}, "numblock": {...}, "escape": {...} },
  "elements": [
    { "id": "panel",  "layer": "base",     "placement": "docked" },
    { "id": "keypad", "layer": "numblock", "placement": "floating",
      "bounds": [0.58, 0.26, 0.99, 0.56], "reservesSpace": false },
    { "id": "esc",    "layer": "escape",   "placement": "free",
      "bounds": [0.02, 0.05, 0.17, 0.115],
      "panelOpacity": 0, "reservesSpace": false, "pinned": true }
  ]
}
```

`bounds` are fractions of the input view, `[left, top, right, bottom]`.

Two fields decide how an element behaves towards the app underneath, and they are
per element because that is the only level at which the question has one answer:

- **`reservesSpace`** — whether the app is asked to keep this element's space clear.
  A docked panel usually should; a small floating block usually should not, and then
  every touch that misses it reaches the app.
- **`pinned`** — whether it may slide out of the way when it would cover the text
  cursor. A floating block should; a key deliberately placed in a corner should not,
  because a pinned key that wanders is a key you cannot find again.

A layout with no `elements` is one docked element. That is every layout written
before elements existed, and they all still work unchanged.

The app-wide *Presentation* setting is the fallback for exactly those layouts. A
layout that places itself ignores it.

## Symbol boards: tabs on a key

`popup` is a strip you slide across without letting go — right for a handful of
accents. `popupGroups` is a board that stays open after the finger lifts and is
tapped — right for the sixty symbols a scientific key carries, which is not a
gesture but a screen.

```json
{
  "id": "k_d", "label": "d",
  "popupGroups": [
    { "id": "accents", "label": "ď", "items": ["ď", "đ", "ð"] },
    { "id": "d_math",  "label": "∂", "title": "Derivatives and deltas",
      "items": ["∂", "∇", "Δ", "δ", "∆", "ⅆ"] }
  ]
}
```

A shorthand is accepted too, where the label doubles as the id:

```json
{ "popupGroups": { "∂": ["∂", "∇", "Δ"], "ď": ["ď", "đ"] } }
```

The selected tab is remembered per key. Tapping an already-selected tab pins it, and
a pinned tab shows alongside whatever is selected next — which is how you write an
equation inside Polish prose without toggling between maths and diacritics.

