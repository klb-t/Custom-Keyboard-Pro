package com.example.core.discovery

/**
 * Recognising an API key, so the user does not have to shepherd it by hand.
 *
 * Getting a key means leaving the app, signing in somewhere, pressing "create", and
 * copying a long opaque string. The copy is the last thing that happens — so by the
 * time somebody is back in the settings screen, the key is already on the clipboard
 * and asking them to paste it is asking them to do the one step the app could have
 * done for them.
 *
 * Two levels, and the second matters more than the first. A provider may declare a
 * pattern, which is data like everything else in the catalogue. When it does not — and
 * most will not, because prefixes change and nobody updates a keyboard when they do —
 * [looksLikeKey] still answers, from the shape of the thing rather than from a list of
 * prefixes somebody has to maintain.
 */
object ApiKeys {

    /**
     * Whether a piece of text has the shape of an API key.
     *
     * Deliberately about shape and not about prefixes. A key is one long opaque token:
     * no spaces, not a sentence, not a URL, and mixed enough that it is not a word
     * somebody copied. This is the half that keeps working when a provider changes its
     * prefix, which they do.
     */
    fun looksLikeKey(text: String?): Boolean {
        val candidate = text?.trim() ?: return false
        if (candidate.length !in MIN_LENGTH..MAX_LENGTH) return false
        if (candidate.any { it.isWhitespace() }) return false
        if (candidate.contains("://") || candidate.startsWith("www.")) return false
        // A key is not a sentence and not a file path.
        if (candidate.any { it in "<>\"'`\\{}[]()" }) return false
        if (candidate.count { it == '/' } > 1) return false

        val letters = candidate.count { it.isLetter() }
        val digits = candidate.count { it.isDigit() }
        // An all-letters string of this length is a phrase or a word; an all-digits one
        // is an identifier or a number. A key is neither.
        if (letters == 0 || digits == 0) return false
        // Mostly-uppercase with no digits reads as shouting, not as a credential.
        return letters + digits + candidate.count { it in "-_.:+=" } == candidate.length
    }

    /**
     * Whether [text] could be a key for [provider] specifically.
     *
     * A provider that declares a pattern is held to it, which is what lets the app tell
     * an OpenRouter key from an Anthropic one when both are on the clipboard history.
     * A provider that declares nothing falls back to shape alone rather than to
     * refusing, because an unrecognised key is still a key.
     */
    fun matches(provider: ProviderSpec?, text: String?): Boolean {
        val candidate = text?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        val pattern = provider?.keyPattern.orEmpty()
        if (pattern.isBlank()) return looksLikeKey(candidate)
        val regex = compiled(pattern) ?: return looksLikeKey(candidate)
        return regex.matches(candidate)
    }

    /**
     * Which known provider a key most likely belongs to, or null.
     *
     * Used to say "that looks like your Groq key" when the screen is showing OpenRouter
     * — a wrong key pasted into the right box is the failure that started all of this,
     * and it is cheap to notice.
     */
    fun whoseKey(text: String?, providers: List<ProviderSpec>): ProviderSpec? {
        val candidate = text?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        return providers
            .filter { it.keyPattern.isNotBlank() }
            // The longest pattern wins: "sk-or-v1-" is a better answer than "sk-".
            .sortedByDescending { it.keyPattern.length }
            .firstOrNull { spec -> compiled(spec.keyPattern)?.matches(candidate) == true }
    }

    /** A key with its middle removed, for showing one without showing it. */
    fun masked(key: String): String {
        val trimmed = key.trim()
        if (trimmed.length <= 12) return "•".repeat(trimmed.length)
        return trimmed.take(6) + "…" + trimmed.takeLast(4)
    }

    private val cache = HashMap<String, Regex?>()

    @Synchronized
    private fun compiled(pattern: String): Regex? = cache.getOrPut(pattern) {
        runCatching { Regex(pattern) }.getOrNull()
    }

    /** Short enough to be an account id, long enough to be nothing else. */
    private const val MIN_LENGTH = 16
    private const val MAX_LENGTH = 400
}
