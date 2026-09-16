package com.example.core.discovery

/**
 * Finding out what is available, as a shape rather than as a feature.
 *
 * Three things in this app are lists nobody can write down in advance: which models a
 * provider serves, which providers exist this year, which layouts someone has
 * published. They differ in every detail except the shape of the problem — ask
 * somewhere, get a list, merge it with what you already had, keep it when the network
 * is gone.
 *
 * So that shape is what lives here, and the specifics live in the sources. Nothing in
 * this file mentions models, providers or HTTP; adding a fourth kind of discovery
 * means writing a source, not touching this.
 */

/** Something with a stable identity, so two lists of it can be merged sensibly. */
interface Discoverable {
    val id: String
}

/** One place to ask. */
interface DiscoverySource<T : Discoverable> {
    val id: String

    /** A human-readable name for the source, for when it fails and must be named. */
    val label: String

    suspend fun discover(): Result<List<T>>
}

/** What a merged discovery produced, including what went wrong on the way. */
data class DiscoveryOutcome<T : Discoverable>(
    val items: List<T>,
    val failures: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** True when nothing was found and at least one source is to blame. */
    val failedOutright: Boolean get() = items.isEmpty() && failures.isNotEmpty()
}

object Discovery {

    /**
     * Asks every source and merges what comes back.
     *
     * Earlier sources win on an id collision, which is what makes "bundled catalogue
     * first, live answer second" and "user's own entries first, bundled second"
     * expressible by ordering alone.
     *
     * A failing source never fails the whole discovery: it contributes nothing and
     * explains itself in [DiscoveryOutcome.failures]. A partial list beats an error
     * screen when the partial list is the one the user already had.
     */
    suspend fun <T : Discoverable> merge(sources: List<DiscoverySource<T>>): DiscoveryOutcome<T> {
        val byId = LinkedHashMap<String, T>()
        val failures = mutableMapOf<String, String>()
        sources.forEach { source ->
            source.discover().fold(
                onSuccess = { items -> items.forEach { byId.putIfAbsent(it.id, it) } },
                onFailure = { failures[source.label] = it.message ?: it::class.java.simpleName }
            )
        }
        return DiscoveryOutcome(byId.values.toList(), failures)
    }

    /** A source that just returns what it was given. Wraps a cache or a bundled file. */
    fun <T : Discoverable> fixed(id: String, label: String, items: List<T>): DiscoverySource<T> =
        object : DiscoverySource<T> {
            override val id = id
            override val label = label
            override suspend fun discover(): Result<List<T>> = Result.success(items)
        }
}
