# Providers, capabilities and discovery

Everything the keyboard sends somewhere goes through this. The short version: a
provider is described rather than coded, what it can do is a field, and how to reach
each thing it does is either one of a few hand-written request shapes or a description
of the request itself.

## A provider is what it can do

`providers.json` ships 41 entries. Each says what it is and what it serves:

```json
{
  "id": "mistral",
  "label": "Mistral",
  "wire": "openai",
  "baseUrl": "https://api.mistral.ai/v1",
  "freeTier": true,
  "privacy": "no_training",
  "strengths": ["european", "ocr", "dictation", "cheap"],
  "capabilities": {
    "transcribe": { "wire": "openai_audio", "defaultModel": "voxtral-mini-latest" },
    "ocr": { "call": { "...": "see below" }, "defaultModel": "mistral-ocr-latest" }
  }
}
```

Capabilities are `chat`, `transcribe`, `ocr`, `image`, `video`, `speech`, `embed`. A
provider serving four of them is one entry with four endpoints, not four entries in
four lists. Chat is described by the top-level fields rather than by an entry in the
map, because that is what every provider written before capabilities existed said —
but only when `wire` is actually a chat format. A provider that only makes pictures
has a base URL too, and reading that as "it can also chat" offers the user an endpoint
that answers 404.

## Coded wire, or described call

Two ways to say how to reach a capability, and the split is deliberate.

**`wire`** names a request shape implemented in Kotlin. There are four: the three chat
formats, which earn hand-written code because streaming, message arrays and tool calls
are not expressible as a template without inventing a programming language; and
dictation's multipart shape, which nearly every transcription endpoint copied from
OpenAI and which therefore covers more providers than any description of it would.

**`call`** describes the request outright, and everything else uses it:

```json
{
  "path": "/listen?model={{model}}&language={{language}}",
  "auth": "prefixed", "authName": "Authorization", "authPrefix": "Token",
  "bodyKind": "binary",
  "resultPath": "results.channels[0].alternatives[0].transcript"
}
```

`resultPath` is a dotted path with `[n]` for arrays. Placeholders are `{{name}}` and
are filled from the call's inputs. Asynchronous providers add `pollUrlPath`,
`pollStatusPath` and `pollDoneValues`, so "submit, then watch" is also data.

Two things about filling a body that are easy to get wrong:

- the body is walked as **parsed JSON**, not substituted into its text, so a prompt
  containing a quote or a newline cannot break out of the string it was put in;
- a string that is *exactly* one placeholder takes the type of what it resolves to,
  because several providers reject `"0.7"` where they expect `0.7`, and a field nothing
  supplies is dropped rather than sent empty, because `"language": ""` is read as a
  language by more providers than not.

## Finding out what exists

`Discovery` is the shape — ask several sources, merge by id, earlier wins, a failing
source contributes nothing and explains itself rather than failing the whole thing.
Four things plug into it.

**`ProviderCatalog`** merges the user's own entries, then anything downloaded, then the
bundled file, then the three ids the app shipped with. The order is the trust order: a
base URL somebody typed on purpose always wins.

**`CatalogUpdate`** fetches a newer catalogue. The address is a setting **with no
default**, deliberately — shipping one would point every install at an address of the
author's choosing and make a keyboard that works offline quietly phone home.

**`ProviderProbe`** asks whether a provider is actually there. For a hosted one that
means "is this key good"; for a local one it means "is anything listening", and that is
worth asking *unprompted*. Somebody running Ollama on their own phone should be offered
it rather than having to know it is an option — and it is the only path that produces a
working setup for a user with no accounts at all.

**`FactsFetcher`** reads prices, context lengths and modalities from somewhere that
does not ask who is calling. This matters for one specific reason given below.

## The advisor, and why it cannot ask a model

`SetupAdvisor` recommends a provider to somebody who has no account anywhere. It cannot
delegate that to a model, because it is advising a user who has not configured one —
that is the entire reason it exists.

So the reasoning is written out. What is **not** written out is the knowledge: free
tier, payment details, what becomes of the text, what each provider is good at are all
catalogue fields. A provider that appears after this build ships gets ranked correctly
the moment its entry arrives, with no change to the ranking code, and a test asserts
exactly that on an entry invented inside the test.

Three properties worth keeping:

- **Rules filter, preferences score.** "Nothing may leave this device" removes
  providers from the list rather than moving them down it. One hosted provider slipping
  into that list is a promise broken, whatever its score.
- **Every point carries a sentence**, and the sentences are what the user is shown. A
  recommendation they disagree with can be argued with instead of being a number they
  have to trust. Where a privacy policy is not established, it says so rather than
  leaving a reassuring blank.
- **Impossible wants get an explained compromise**, naming what had to be given up.
  "Nothing matches" is the worst possible answer to "help me choose".

For the same reason the advisor exists, the facts it weighs have to be obtainable
without a key. Anonymous *inference* is essentially unavailable — what exists is
somebody's demo, which breaks and would mean every install hitting a server chosen by
the author. Anonymous *data* is not: a public model list carries prices and context
lengths for hundreds of models. That is the useful half, because the advisor wanted
numbers, not opinions.

Fact sources ship as data for the same reason catalogue addresses do. Leaderboards
change format, move, and get paywalled; a hardcoded URL is a delayed fault. Two entries
ship, one enabled and one parked — the parked one reads the same list the way it was
shaped before a field was renamed, so when the live one stops returning anything the
repair is flipping which is enabled, not writing code.

## Things the tests hold, because each is a lie waiting to be told

- a price of `"0"` survives as zero, not as "no price known" — those mean opposite
  things to somebody choosing without a card;
- a missing price reads as unknown, never as free;
- a missing context length is absent rather than `0`;
- a row with no id is dropped instead of becoming a blank entry;
- a source that changed shape parses to nothing rather than to nonsense;
- a field a source does not publish reads as absent — an empty path means "the whole
  document" to `JsonPath`, correctly, so passing one through for an unmapped field once
  returned a model's entire JSON description in place of its provider name.
