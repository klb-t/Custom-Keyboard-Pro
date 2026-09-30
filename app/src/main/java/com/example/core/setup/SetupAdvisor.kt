package com.example.core.setup

import com.example.core.discovery.AiCapability
import com.example.core.discovery.Privacy
import com.example.core.discovery.ProviderSpec

/**
 * What the user said they want, in the only terms they can be expected to have.
 *
 * Not "which provider" — that is the question being answered. These are the things
 * somebody who has never heard of any of them can still say truthfully about
 * themselves.
 */
data class SetupWants(
    /** What they want the keyboard to do. Empty means "whatever is useful". */
    val capabilities: Set<String> = setOf(AiCapability.CHAT),
    /** Nothing may leave the device. Overrides everything else, because it is a rule. */
    val mustStayOnDevice: Boolean = false,
    /** Will not hand over payment details. */
    val noCard: Boolean = false,
    /** Will not pay, but would give a card to something with a free tier. */
    val freeOnly: Boolean = false,
    /** Minds being trained on. */
    val avoidTraining: Boolean = false,
    /** Would rather one account than five. */
    val preferOneAccount: Boolean = false,
    /** Free text they typed, matched against provider strengths. */
    val notes: String = ""
)

/** One recommendation, with the reasons it was made, in the user's words. */
data class Recommendation(
    val provider: ProviderSpec,
    val score: Int,
    /** Why it is here. Shown as-is. */
    val reasons: List<String>,
    /** What they should know before choosing it. Shown as-is. */
    val caveats: List<String>
)

/**
 * Choosing where to send things, for somebody who has no account anywhere.
 *
 * This is the part of setup that cannot be delegated to a model, and the reason is
 * worth stating plainly: it is advising a user who has not yet configured a model.
 * Asking one to rank the providers would require the thing the ranking exists to
 * obtain. So the reasoning is written out.
 *
 * What is *not* written out is the knowledge. Every fact this weighs — free tier,
 * payment details, what happens to the text, what the provider is good at — lives in
 * the catalogue, which is data. Adding a provider teaches the advisor something new
 * without anyone touching this file, and a provider the app has never heard of gets
 * ranked correctly the moment its entry arrives.
 *
 * The ranking is deliberately legible rather than clever. Every point it awards has a
 * sentence attached, and the sentences are what the user is shown — so a
 * recommendation the user disagrees with can be argued with, instead of being a
 * number they have to trust.
 */
object SetupAdvisor {

    /** The same hard filters govern recommendations, direct routes and an open setup target. */
    fun allowsPolicy(provider: ProviderSpec, wants: SetupWants): Boolean =
        (!wants.mustStayOnDevice || staysOnDevice(provider)) &&
            (!wants.noCard || !provider.needsCard) &&
            (!wants.freeOnly || provider.freeTier) &&
            (!wants.avoidTraining || provider.privacy in setOf(Privacy.NO_TRAINING, Privacy.ON_DEVICE))

    fun recommend(
        providers: List<ProviderSpec>,
        wants: SetupWants,
        limit: Int = 4
    ): List<Recommendation> = providers
        .asSequence()
        .filter { it.id != "openai_compatible" }
        .filter { serves(it, wants.capabilities) }
        .filter { allowsPolicy(it, wants) }
        .map { score(it, wants) }
        .filter { it.score > Int.MIN_VALUE }
        .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.provider.label })
        .take(limit)
        .toList()

    /**
     * The honest fallback: what to offer when the filters left nothing.
     *
     * Offers explicit commercial/privacy compromises without changing the requested
     * operation or the user's device boundary. Null is correct when no such route exists.
     */
    fun fallback(providers: List<ProviderSpec>, wants: SetupWants): Pair<List<Recommendation>, String>? {
        // Never relax the device boundary or replace the requested operation with chat.
        val relaxations = listOf(
            wants.copy(avoidTraining = false) to "if you explicitly allow unknown or training policies",
            wants.copy(preferOneAccount = false, freeOnly = false) to "if you are willing to pay for use",
            wants.copy(noCard = false, freeOnly = false) to "if you will give payment details"
        )
        relaxations.forEach { (relaxed, explanation) ->
            val found = recommend(providers, relaxed)
            if (found.isNotEmpty()) return found to explanation
        }
        return null
    }

    internal fun staysOnDevice(provider: ProviderSpec): Boolean {
        if (provider.privacy != Privacy.ON_DEVICE) return false
        if (provider.baseUrl.isBlank()) return provider.wire == com.example.core.discovery.AiWire.ON_DEVICE
        val host = runCatching { java.net.URI(provider.baseUrl).host?.lowercase() }.getOrNull()
        return host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }

    private fun serves(provider: ProviderSpec, capabilities: Set<String>): Boolean =
        capabilities.isEmpty() || capabilities.all { provider.can(it) }

    private fun score(provider: ProviderSpec, wants: SetupWants): Recommendation {
        var score = 0
        val reasons = mutableListOf<String>()
        val caveats = mutableListOf<String>()

        // Costing nothing is the single thing that most decides whether setup gets
        // finished at all, so it outweighs every refinement below it.
        if (provider.freeTier) {
            score += 40
            reasons += "Free to start"
        }
        if (!provider.needsKey) {
            score += 25
            reasons += "No account and no key"
        } else if (provider.needsCard) {
            score -= 20
            caveats += "Wants payment details before it will answer"
        }

        when (provider.privacy) {
            Privacy.ON_DEVICE -> {
                score += if (wants.avoidTraining || wants.mustStayOnDevice) 50 else 20
                reasons += if (staysOnDevice(provider)) "Runs here; nothing is sent anywhere"
                else "Self-hosted endpoint; data still leaves the phone when it is remote"
            }
            Privacy.NO_TRAINING -> {
                score += if (wants.avoidTraining) 25 else 8
                reasons += "Does not train on what you send"
            }
            Privacy.TRAINS_BY_DEFAULT -> {
                score -= if (wants.avoidTraining) 40 else 5
                caveats += "Trains on what you send unless you opt out"
            }
            else -> caveats += "What happens to your text here is not established"
        }

        if (provider.router) {
            score += if (wants.preferOneAccount) 35 else 12
            reasons += "One key reaches several models; availability depends on the gateway"
        }

        // A provider that covers everything asked for in one place beats two that
        // each cover half, because the second account is where people stop.
        val covered = wants.capabilities.count { provider.can(it) }
        score += covered * 10
        if (covered > 1) {
            reasons += "Covers " + wants.capabilities
                .filter { provider.can(it) }
                .joinToString(", ") { AiCapability.label(it).lowercase() }
        }

        matchedStrengths(provider, wants.notes).forEach { strength ->
            score += 15
            reasons += "Good at what you described: $strength"
        }

        // Something local that still points at an address needs that address to be
        // serving. The phone's own recogniser has no base URL because there is
        // nothing to point at — which is exactly what makes it easier, and is read
        // off the entry rather than hardcoded against its id.
        if (provider.local && provider.baseUrl.isNotBlank()) {
            score -= 15
            caveats += "Needs a server you run yourself"
        }
        if (provider.baseUrl.contains("ACCOUNT_ID") || provider.baseUrl.contains("GATEWAY")) {
            score -= 30
            caveats += "The address has to be filled in with your own details first"
        }

        return Recommendation(provider, score, reasons.distinct(), caveats.distinct())
    }

    /**
     * Words from what the user typed that name something a provider is good at.
     *
     * Matching is on the tag, both ways round, so "dictation" finds a provider tagged
     * `dictation` and a user who wrote "I dictate a lot" finds it too. Crude on
     * purpose: the alternative is asking a model, which is the thing that is not
     * available here.
     */
    internal fun matchedStrengths(provider: ProviderSpec, notes: String): List<String> {
        if (notes.isBlank() || provider.strengths.isEmpty()) return emptyList()
        val said = notes.lowercase()
        val saidWords = said.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return provider.strengths.filter { tag ->
            val phrase = tag.replace('-', ' ')
            said.contains(phrase) || phrase.split(' ').any { part -> matches(part, saidWords) }
        }
    }

    /**
     * Whether a tag and something the user wrote are the same idea.
     *
     * Compared on a short common prefix rather than for equality, because nobody
     * writes "dictation" — they write "I dictate a lot", and a matcher that cannot
     * connect those two is useless for the case it exists to serve. Five characters
     * is enough to keep "dictate" and "dictation" together while keeping "private"
     * and "print" apart.
     */
    private fun matches(tag: String, saidWords: List<String>): Boolean {
        if (tag.length <= 3) return false
        val stem = tag.take(STEM)
        return saidWords.any { word ->
            word.length > 3 && (word.startsWith(stem) || tag.startsWith(word.take(STEM)))
        }
    }

    private const val STEM = 5
}
