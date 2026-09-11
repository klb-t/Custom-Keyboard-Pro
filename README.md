# Custom Keyboard Pro

An Android keyboard where the layout is data, not code — so anything the built-in
layouts can do, a layout you make can do too.

It works completely on its defaults. Nothing needs to be configured, no account, no
key, no network. Everything is adjustable if you want it to be.

## What it does

**Layouts are documents.** A layout describes its keys, what each one looks like, and
what it does under every way of touching it: tap, long press, double tap, hold-repeat,
eight swipe directions, and chords with another key. All of it is JSON you can read,
edit, export and import.

**Four ways to make one.**

| | |
|---|---|
| Paint a **colour mask** | One flat colour per key. Any shape, anywhere on the image, which becomes the keyboard's background. Exact — nothing is guessed. |
| **Trace a screenshot** | Finds the rows and keys by looking for the gaps. On device, offline, instant, and a guess. |
| **Read a screenshot with a model** | Reads the glyphs and infers what each key should do, including long-press alternates. |
| **Describe it in words** | "Like Hacker's Keyboard but with a lit Caps Lock and swipe-up digits" — and it writes the JSON. |

**Real modifiers, with lamps.** Shift, Ctrl, Alt, Meta, Fn and AltGr, each momentary,
one-shot, toggled or locked — and a per-key indicator showing which. Caps, Num and
Scroll Lock have their own, and there is an optional status strip for all of them.
Ctrl+C, Tab, Esc and the function row are sent as real key events, so they reach
terminals and remote desktops.

**Things other keyboards do not have.** X11 compose sequences (`Compose`, `-`, `>`
gives →), dead keys for every Latin accent, hex Unicode entry, macros, per-key touch
weights, and a long swipe across the keyboard bound to any action you like.

**Dictation that lets you choose.** The recogniser returns a ranked list and the
second entry is often the right one, so the alternatives are shown and you pick.
System recogniser by default — free, no key, usually on-device — or any
Whisper-compatible endpoint.

**AI, if you want it and on your terms.** Any OpenAI-compatible endpoint, Anthropic,
Gemini, or a model on your own machine through Ollama, LM Studio or vLLM. Inline
completion in the suggestion strip, and a panel of text tasks you can extend. All of
it off until you turn it on.

**Suggestions that work on day one.** Common English and Polish word lists are built
in; your own dictionary and next-word model build up alongside them, on the device.
You can inspect, edit and delete every word it has learned, and import a real word
list.

**Shapes.** Full width, one-handed left or right, split, or a floating panel you drag
where you want — with touches outside it going through to the app underneath.

## Privacy

Two rules, enforced in the code that could break them rather than at each call site:

1. In a password field, or an editor that asked not to be personalised, nothing is
   suggested, learned, recorded to the clipboard, or sent to a model — whatever else
   is switched on.
2. Anything that leaves the device is off by default and needs a provider you supply.

## Building

No Gradle wrapper binary is committed. CI provisions Gradle through
`gradle/actions/setup-gradle`; locally any Gradle 9.x works:

```
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how it is put together and where
  the decisions live.
- [`docs/LAYOUT_FORMAT.md`](docs/LAYOUT_FORMAT.md) — the layout JSON, in full.

## Not included

**Glide typing.** Tracing a word across the keys needs a large frequency-weighted
word list to decode against; the bundled lists are a few hundred words, enough for
suggestions and correction but far too small for a glide decoder. Shipping the gesture
without the data behind it would produce a feature that feels broken. Importing a real
word list is the groundwork; the decoder is not written.
