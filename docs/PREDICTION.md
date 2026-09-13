# Corrections and predictions

Two lanes, and the difference between them is the whole design.

| | Changes text that already exists | Adds text you must accept |
|---|---|---|
| **Correction** | yes, often unnoticed | — |
| **Prediction** | — | yes, on a deliberate tap |

Everything cautious below applies to the first lane only. A suggestion you have to tap
can be as bold as it likes, because nothing enters the text without a deliberate act.
Getting that boundary wrong in either direction is how a keyboard becomes either
useless or dangerous.

## Modifiers

A candidate's score is a log-probability. Every contribution to it is a `Modifier`, and
the set of them is data while the way they combine is not.

```
log P(word) = Σ  weightᵢ · scoreᵢ(word | context)
```

Additive in log space, which is multiplicative in probability space — a prior times a
likelihood, which is what these are. The arithmetic is fixed because it follows from
what probability is, not from anybody's preference.

### Power: what a modifier is allowed to do

| Power | May introduce a candidate | May raise one already supported |
|---|---|---|
| `GENERATIVE` | yes | yes |
| `PROTECTIVE` | **no** | yes |
| `SUPPRESSIVE` | no | lowers only |

This exists because of one specific failure. A profile that has learned somebody writes
abusively is useful for *not fighting them* when they clearly typed an insult. It must
never be able to *produce* one over weak touch evidence — the difference between a
keyboard that stops correcting you and a keyboard that makes you insult the people who
just helped you. Anything learned from a profile and socially costly is protective
only, and the registry enforces it; without enforcement it is a generative modifier
with a reassuring name.

### Kind: how it acts, and why order is fixed

```
rewrites  →  offsets (any order)  →  floors  →  vetoes
```

An offset computed on the pre-rewrite spelling awards points to a word that is no
longer there. A veto anything can outbid is not a veto. Offsets commute, which is
exactly what makes the set of them data: they can be added, removed and reweighted
freely without the result depending on the order.

### Provenance: how much a piece of text is worth believing

`CHOSEN` › `TYPED_CONFIDENT` › `COMPLETED` › `DICTATED` › `TYPED_UNCERTAIN` ›
`EXTERNAL` › `AUTOCORRECTED`

A field rather than a note, because the difference is load-bearing: a correction rule
learned from dictation must not fire on a word somebody typed on purpose. Text the
keyboard itself inserted and the user did not delete ranks lowest — not deleting is not
consent, and treating it as a label is how a model ends up training on its own output.

A learned correction rule is empirical log-odds:

```
offset = ln( (corrections + 1) / (keeps + 1) )
```

Ten corrections and no keeps is a strong rule. Five and five is none. It calibrates
itself and unwinds itself if the user changes their mind.

## Deciding is not ranking

Ranking produces an order; deciding produces an action, and the two differ whenever the
costs are uneven — which, for a keyboard, is most of the time that matters.

Costs are `ORDINARY`, `MEANING`, `SOCIAL`, `IRREVERSIBLE`, and the chosen candidate
minimises **expected cost**, not maximum probability:

```
w* = argmin_w  Σ_v  P(v) · L(w, v)
```

The case that settles it: somebody asks for help on a forum, several people help, and
they type a reply. Two of the touches are poor — one lands between two modifier keys,
another at the far edge of the board. A profile learned from their history makes an
insult the likelier reading by the numbers. Emitting the thanks when the insult was
meant is a retype; emitting the insult when thanks were meant is public and possibly
unnoticed until sent. Those are not the same error and are not priced the same.

From which a rule falls out rather than being bolted on:

> **Nothing is silently substituted across a difference in cost. At any confidence.
> Not even at 99%.** When the alternatives would cost different amounts, the keyboard
> flags the word and offers them instead of choosing.

## Confidence is a band

`HIGH` / `MEDIUM` / `LOW`, not a percentage. "73%" attached to something right 40% of
the time is worse than no number at all, because it looks like a measurement. A band
claims only an ordering, which is the thing these scores genuinely have until there is
enough data to calibrate. The number belongs behind expert mode, with a note saying
what it is calibrated on.

## One generation, several offers

A continuation is generated once and cut at several depths — word, clause, sentence,
paragraph, whole. These are **prefixes of the same continuation** rather than five
requests: far cheaper, and they cannot contradict each other the way five separate
generations routinely would. Descending the suggestion tree is the same operation seen
from the other side: taking a longer slice, or regenerating from the one just accepted.

### Stopping is data, and a probability floor is not enough

| Condition | For |
|---|---|
| `tokenFloor` | cut when one token drops below a log-probability |
| `surpriseBudget` | cut when the generation *as a whole* has drifted |
| `stopStrings` | `\n\n`, a fence |
| `untilBalanced` | generate until the block closes — better for code than any token count |
| `maxChars` | the bezel; every other condition can fail to fire |
| `maxMillis` | on a phone, "you have been waiting" is a real reason to stop |

The budget matters more than the floor. A name or a number mid-sentence is one
genuinely uncertain token in an otherwise confident run, and a floor cuts a good
continuation in half there; a budget carries through it and stops when the thing has
actually wandered.

`untilBalanced` arms on a brace, not on any bracket. Parentheses balance constantly
mid-expression — `fun x() ` is balanced the instant it is written — so counting them
stops every code generation on its first token.

## Not yet wired

The core above is compiled and tested; nothing calls it. That was deliberate: it is the
piece where being subtly wrong is worse than being obviously broken, so it stands on
its own first. Still to come: the two rows in the UI, the tree, and streaming in the
call engine — which everything above needs, since cutting on a log-probability requires
tokens as they arrive rather than a finished reply.
