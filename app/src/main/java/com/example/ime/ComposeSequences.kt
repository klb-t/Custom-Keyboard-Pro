package com.example.ime

/**
 * X11-style compose sequences.
 *
 * Press the compose key, then two or three ordinary keys, and get a character that is
 * on no layout: `Compose o c` gives ©, `Compose - >` gives →. It is how people who
 * came from a desktop expect to type arrows, dashes and maths, and no phone keyboard
 * offers it.
 *
 * The table is deliberately small and predictable rather than a full import of the
 * X11 file: these are the sequences worth knowing by heart. Sequences compose with
 * dead keys too, since both end up in the same commit path.
 */
object ComposeSequences {

    private val TABLE: Map<String, String> = buildMap {
        // Punctuation and dashes
        put("--", "–"); put("---", "—"); put("..", "…"); put("<<", "«"); put(">>", "»")
        put("''", "’"); put("\"\"", "”"); put(",,", "„")

        // Arrows
        put("->", "→"); put("<-", "←"); put("^|", "↑"); put("v|", "↓")
        put("=>", "⇒"); put("<=", "⇐"); put("<>", "↔")

        // Maths and logic
        put("+-", "±"); put("!=", "≠"); put("=<", "≤"); put(">=", "≥")
        put("~~", "≈"); put("oo", "∞"); put("xx", "×"); put(":-", "÷")
        put("00", "°"); put("//", "√"); put("pi", "π"); put("mu", "µ")
        put("AA", "∀"); put("EE", "∃"); put("in", "∈"); put("su", "∑")

        // Symbols
        put("oc", "©"); put("or", "®"); put("tm", "™"); put("so", "§"); put("pp", "¶")
        put("**", "•"); put("+++", "‡"); put("++", "†")

        // Currency
        put("e=", "€"); put("l-", "£"); put("y=", "¥"); put("c/", "¢")
        put("zl", "zł"); put("Rs", "₹"); put("Ru", "₽")

        // Fractions
        put("12", "½"); put("13", "⅓"); put("14", "¼"); put("34", "¾"); put("23", "⅔")

        // Letters that are not reachable by a dead key
        put("ae", "æ"); put("AE", "Æ"); put("oe", "œ"); put("OE", "Œ")
        put("ss", "ß"); put("/o", "ø"); put("/O", "Ø"); put("/l", "ł"); put("/L", "Ł")
        put("dh", "ð"); put("th", "þ"); put("TH", "Þ")

        // Boxes and marks
        put("ok", "✓"); put("no", "✗"); put("<3", "♥"); put(":)", "☺"); put(":(", "☹")
        put("*n", "★"); put("*N", "☆"); put("mm", "♪")
    }

    /** The character a completed sequence produces, or null if it is not one. */
    fun resolve(sequence: String): String? = TABLE[sequence]

    /** True when [sequence] could still grow into a real sequence. */
    fun isPrefix(sequence: String): Boolean =
        sequence.length < 4 && TABLE.keys.any { it.startsWith(sequence) && it != sequence }

    /** The whole table, for the help screen. */
    fun all(): List<Pair<String, String>> = TABLE.entries.map { it.key to it.value }.sortedBy { it.first }
}
