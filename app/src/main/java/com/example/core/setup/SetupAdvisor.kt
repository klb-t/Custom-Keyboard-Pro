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

    fun recommend(
        providers: List<ProviderSpec>,
        wants: SetupWants,
        limit: Int = 4
    ): List<Recommendation> = providers
        .asSequence()
        .filter { it.id != "openai_compatible" }
        .filter { serves(it, wants.capabilities) }
        .filter { !wants.mustStayOnDevice || it.privacy == Privacy.ON_DEVICE }
        .filter { !wants.noCard || !it.needsCard }
        .filter { !wants.freeOnly || it.freeTier }
        .filter { !wants.avoidTraining || it.privacy != Privacy.TRAINS_BY_DEFAULT }
        .map { score(it, wants) }
        .filter { it.score > Int.MIN_VALUE }
        .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.provider.label })
        .take(limit)
        .toList()

    /**
     * The honest fallback: what to offer when the filters left nothing.
     *
     * An empty list is the worst possible answer to "help me choose", so this drops
     * the preferences one at a time, hardest-to-give-up last, and says which one had
     * to go. A user told "nothing matches, but here is what you get if you accept a
     * free tier that trains on your text" can decide. A user told nothing cannot.
     */
    fun fallback(providers: List<ProviderSpec>, wants: SetupWants): Pair<List<Recommendation>, String>? {
        val relaxations = listOf(
            wants.copy(avoidTraining = false) to "if you accept a provider that may train on your text",
            wants.copy(preferOneAccount = false, freeOnly = false) to "if you are willing to pay for use",
            wants.copy(noCard = false, freeOnly = false) to "if you will give payment details",
            wants.copy(capabilities = setOf(AiCapability.CHAT)) to "for writing only, without the rest",
            SetupWants(capabilities = wants.capabilities) to "with none of your preferences applied"
        )
        relaxations.forEach { (relaxed, explanation) ->
            val found = recommend(providers, relaxed)
            if (found.isNotEmpty()) return found to explanation
        }
        return null
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
                reasons += "Runs here; nothing is sent anywhere"
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
            reasons += "One key reaches many models, so this is the last account you need"
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

        if (provider.local && provider.id != "android_speech") {
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
        return provider.strengths.filter { tag ->
            val word = tag.replace('-', ' ')
            said.contains(word) || word.split(' ').any { it.length > 3 && said.contains(it) }
        }
    }
}
