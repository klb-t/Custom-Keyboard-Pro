package com.example.core.layout

/**
 * What a language expects from a keyboard, applied to whatever layout is on screen.
 *
 * The scientific layout offers the accents a physicist wants, ordered for a physicist:
 * hold "e" and the first alternate is `é`. For somebody writing Polish on that same
 * board the first alternate has to be `ę`, because that is the letter they reach for
 * fifty times a message — and hold-and-release gives the first one.
 *
 * The wrong fix is a Polish copy of the scientific layout, and then a Czech one, and
 * then a Polish copy of every other layout. Which characters belong to a language is a
 * fact about the language, not about any particular board, so it is applied *to* a
 * board rather than baked into one. A layout the user draws themselves gets it too,
 * for free, in every language.
 */
object LanguageKeys {

    /**
     * The letters of a language, in the order it wants them offered.
     *
     * Anything in this list is pulled to the front of a key's alternates, keeping this
     * order among themselves. Everything else keeps its own order behind them — the
     * physicist's Greek is still there, one slide further along.
     */
    val PREFERRED: Map<String, List<String>> = mapOf(
        "pl" to listOf("ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"),
        "cs" to listOf("á", "č", "ď", "é", "ě", "í", "ň", "ó", "ř", "š", "ť", "ú", "ů", "ý", "ž"),
        "de" to listOf("ä", "ö", "ü", "ß"),
        "es" to listOf("á", "é", "í", "ñ", "ó", "ú", "ü"),
        "fr" to listOf("à", "â", "ç", "é", "è", "ê", "ë", "î", "ï", "ô", "ù", "û", "œ"),
        "sk" to listOf("á", "ä", "č", "ď", "é", "í", "ĺ", "ľ", "ň", "ó", "ô", "ŕ", "š", "ť", "ú", "ý", "ž"),
        "uk" to emptyList(),
        "tr" to listOf("ç", "ğ", "ı", "ö", "ş", "ü")
    )

    /**
     * Characters a language expects on a key that does not carry them.
     *
     * Polish `ź` is the case that forced this. It belongs on **x**, where the
     * programmer's layout puts it, because **z** is taken by `ż` — and no scientific or
     * international board has any reason to put a Polish letter on x. Added at the
     * front of that key's alternates.
     */
    val EXTRA: Map<String, Map<String, List<String>>> = mapOf(
        "pl" to mapOf(
            "x" to listOf("ź"),
            "z" to listOf("ż", "ź")
        )
    )

    /**
     * What AltGr types, held over a letter.
     *
     * Applied as a transform rather than as a layer a layout has to declare, so every
     * board gets it — the scientific one, a user's own, one that arrived as data. The
     * key is matched by the letter it *types* rather than by its id, because ids differ
     * between layouts and the letter does not.
     */
    val ALT_GR: Map<String, Map<String, String>> = mapOf(
        "pl" to mapOf(
            "a" to "ą", "c" to "ć", "e" to "ę", "l" to "ł",
            "n" to "ń", "o" to "ó", "s" to "ś", "x" to "ź", "z" to "ż"
        ),
        "de" to mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß"),
        "tr" to mapOf("c" to "ç", "g" to "ğ", "i" to "ı", "o" to "ö", "s" to "ş", "u" to "ü")
    )

    /** "pl-PL", "pl_PL" and "Polski" alike reduce to "pl". */
    fun tag(locale: String?): String =
        locale?.trim()?.lowercase()?.take(2)?.takeIf { it.length == 2 }.orEmpty()

    fun hasAltGr(locale: String?): Boolean = ALT_GR.containsKey(tag(locale))

    fun altGrFor(locale: String?): Map<String, String> = ALT_GR[tag(locale)].orEmpty()

    /**
     * Reorders every key's alternates so this language's own letters come first.
     *
     * Nothing is removed. A key that offered fourteen mathematical symbols still offers
     * all fourteen; the Polish letter simply stops being ninth.
     */
    fun preferredFor(locale: String?, key: KeyDef): List<String> {
        val language = tag(locale)
        val preferred = PREFERRED[language].orEmpty()
        val extra = EXTRA[language]?.get(typedBy(key)).orEmpty()
        if (preferred.isEmpty() && extra.isEmpty()) return key.popup

        // A key with a tabbed board keeps its accents in the first tab and has no quick
        // strip at all — which on the scientific layout means "ę" is behind a board, a
        // tab and a tap. Reading the board's own accents here is what lets the language
        // hand that key a strip it did not have, without the board losing anything.
        val existing = key.popup.ifEmpty { key.popupGroups.firstOrNull()?.items.orEmpty() }

        val wanted = LinkedHashSet<String>()
        // The extras first: a letter the board never had is the one most worth reaching.
        extra.forEach { if (it !in wanted) wanted += it }
        preferred.forEach { candidate ->
            if (candidate in existing && candidate !in wanted) wanted += candidate
        }
        // Nothing of this language on this key: leave it exactly as it was, so a board
        // that had no strip still has none and still opens its board.
        if (wanted.isEmpty()) return key.popup
        return wanted.toList() + existing.filterNot { it in wanted }
    }

    /** The single letter a key types, lowercased, or null when it types something else. */
    fun typedBy(key: KeyDef): String? {
        val text = (key.tapAction as? KeyAction.Text)?.text ?: return null
        if (text.length != 1 || !text[0].isLetter()) return null
        return text.lowercase()
    }
}
