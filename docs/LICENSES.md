# Third-party material bundled in this app

Everything the app ships that somebody else wrote, what it is used for, and the terms
it comes under. Data files are listed here rather than only in a header comment,
because a licence that lives only inside the file it covers is one nobody finds.

## Word lists — `app/src/main/assets/dictionaries/`

`pl.txt` and `en.txt` are the frequency-ordered word lists the keyboard starts from,
before it has learned anything of the user's own. They are derived from
**[FrequencyWords](https://github.com/hermitdave/FrequencyWords)** (2018 edition),
which is built from the **OpenSubtitles** corpus distributed by
[OPUS](https://opus.nlpl.eu/).

What was changed: entries outside the language's alphabet were dropped, duplicates
removed, the frequency counts stripped (the line number carries the rank), and a small
number of words marked with a leading `!`, which means *known but never offered
unprompted* — they remain in the dictionary, are spelled correctly, are never
auto-corrected away, and can always be typed.

FrequencyWords is distributed under the MIT licence:

```
MIT License

Copyright (c) 2016 Hermit Dave

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Replacing them

None of this is load-bearing. A list downloaded into the app's own storage takes
precedence over the bundled one for that language, and `assets/wordlists.json` is
merely the set of addresses offered — the user can add their own, in Suggestions →
word list sources. Nothing is ever fetched without being asked for.

The format is deliberately the one published lists already use: one word per line,
most common first, an optional second column that is ignored, `#` for a comment. A
file of somebody's own trade vocabulary works without conversion.
