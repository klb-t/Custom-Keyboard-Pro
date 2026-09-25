package com.example.core.matrix

import org.json.JSONArray
import org.json.JSONObject

/** Where a piece of a result came from, in the terms that matter to trusting it. */
enum class Origin {
    /** Straight from the source, as it arrived. */
    OBSERVED,

    /** Calculated from the input: the same input always gives it. */
    DERIVED,

    /** Estimated from the input: it could be wrong. */
    INFERRED,

    /** Made up to fit: the input does not determine it. */
    GENERATED,

    /** Brought in from somewhere other than the input. */
    EXTERNAL;

    companion object {
        fun of(mapping: Mapping): Origin = when (mapping) {
            Mapping.DETERMINISTIC -> DERIVED
            Mapping.INFERENTIAL -> INFERRED
            Mapping.STOCHASTIC, Mapping.GENERATIVE -> GENERATED
        }
    }
}

/** Where the input came from and what it was taken to be. */
data class Source(
    val transport: String,
    val type: String,
    val representation: String,
    /** How it was taken ("as written", "read as note names"), when that was a judgement. */
    val interpretedAs: String = "",
    val inferred: Boolean = false
)

/** One step taken on the way to a result. */
data class Record(
    val transform: String,
    val implementation: String?,
    val site: Site?,
    val mapping: Mapping,
    val changes: List<Change>,
    /** Every parameter the step ran with, as it ran — given, carried in the input, or defaulted. */
    val parameters: Map<String, String> = emptyMap(),
    /** Parameters the step needed that nobody gave and the input did not carry, so defaults stood in for them. */
    val assumed: List<String> = emptyList(),
    /** What had to be true for the output to mean what it says. */
    val assumptions: List<String> = emptyList(),
    /** How sure the step was, when it said; null is "it did not say", never "certain". */
    val confidence: Double? = null,
    val note: String? = null
) {
    val origin: Origin
        get() = if (changes.any { it.kind == ChangeKind.EXTERNALLY_ADDED }) Origin.EXTERNAL else Origin.of(mapping)

    fun toJson(): JSONObject = JSONObject().apply {
        put("transform", transform)
        implementation?.let { put("implementation", it) }
        site?.let { put("site", it.name.lowercase()) }
        put("mapping", mapping.name.lowercase())
        put("changes", JSONArray(changes.map { JSONArray(listOf(it.kind.name.lowercase(), it.what)) }))
        if (parameters.isNotEmpty()) put("parameters", JSONObject(parameters))
        if (assumed.isNotEmpty()) put("assumed", JSONArray(assumed))
        if (assumptions.isNotEmpty()) put("assumptions", JSONArray(assumptions))
        confidence?.let { put("confidence", it) }
        note?.let { put("note", it) }
    }

    companion object {
        private fun strings(a: JSONArray?): List<String> = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()

        fun fromJson(o: JSONObject): Record? {
            val transform = o.optString("transform").takeIf { it.isNotBlank() } ?: return null
            val mapping = Mapping.entries.firstOrNull { it.name.equals(o.optString("mapping"), true) } ?: return null
            val changes = o.optJSONArray("changes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONArray(i) ?: return@mapNotNull null
                    val kind = ChangeKind.entries.firstOrNull { it.name.equals(c.optString(0), true) } ?: return@mapNotNull null
                    Change(kind, c.optString(1))
                }
            }.orEmpty()
            val params = o.optJSONObject("parameters")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }.orEmpty()
            return Record(
                transform = transform,
                implementation = o.optString("implementation").takeIf { it.isNotBlank() },
                site = Site.entries.firstOrNull { it.name.equals(o.optString("site"), true) },
                mapping = mapping,
                changes = changes,
                parameters = params,
                assumed = strings(o.optJSONArray("assumed")),
                assumptions = strings(o.optJSONArray("assumptions")),
                confidence = if (o.has("confidence")) o.optDouble("confidence") else null,
                note = o.optString("note").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * The history that comes with a result: where the input came from and every step
 * taken since — carried with the data, written into the files that can hold it, and
 * read back from them, so a picture made here and converted again later still knows
 * it was drawn from a recording, in which layout, by what.
 *
 * Not a debug log: a result without it is a claim without a source.
 */
data class Provenance(
    val source: Source,
    val records: List<Record> = emptyList(),
    /** The history the input brought with it, when it was made by IO Matrix before. */
    val earlier: Provenance? = null
) {
    fun then(record: Record): Provenance = copy(records = records + record)

    /** Every kind of origin in the result's history — so "inferred" is never hidden behind a later "derived". */
    val origins: Set<Origin>
        get() = buildSet {
            add(Origin.OBSERVED)
            if (source.inferred) add(Origin.INFERRED)
            records.forEach { add(it.origin) }
            earlier?.let { addAll(it.origins) }
        }

    /** Whether anything on the way was sent off the phone. */
    val leftDevice: Boolean
        get() = records.any { it.site == Site.PROVIDER } || earlier?.leftDevice == true

    fun changes(kind: ChangeKind): List<String> =
        (earlier?.changes(kind).orEmpty() + records.flatMap { r -> r.changes.filter { it.kind == kind }.map { it.what } }).distinct()

    /** Everything that stood in for a missing parameter, by step. */
    val assumed: List<String>
        get() = (earlier?.assumed.orEmpty() + records.flatMap { r -> r.assumed.map { "${r.transform}: $it" } })

    /** One line for a notice: where from, and how. */
    fun summary(): String = buildString {
        append(source.transport).append(" (").append(source.representation).append(')')
        if (source.inferred) append(" read as ").append(source.interpretedAs)
        records.forEach { r ->
            append(" → ").append(r.transform)
            val marks = buildList {
                if (r.origin != Origin.DERIVED) add(r.origin.name.lowercase())
                if (r.site == Site.PROVIDER) add("sent away")
                if (r.assumed.isNotEmpty()) add("assumed " + r.assumed.joinToString("/"))
            }
            if (marks.isNotEmpty()) append(" [").append(marks.joinToString("; ")).append(']')
        }
    }

    /** The full account, for the expert view. */
    fun details(): String = buildString {
        earlier?.let { append("Before: ").append(it.summary()).append('\n') }
        append("From: ").append(source.transport).append(", ").append(source.type).append(" as ").append(source.representation)
        if (source.inferred) append(" (read as ").append(source.interpretedAs).append(')')
        append('\n')
        records.forEachIndexed { i, r ->
            append(i + 1).append(". ").append(r.transform)
            r.implementation?.let { append(" by ").append(it) }
            append(" — ").append(r.origin.name.lowercase())
            r.confidence?.let { append(", confidence ").append("%.2f".format(java.util.Locale.ROOT, it)) }
            append('\n')
            if (r.parameters.isNotEmpty()) append("   with ").append(r.parameters.entries.joinToString { "${it.key}=${it.value}" }).append('\n')
            if (r.assumed.isNotEmpty()) append("   assumed (not given): ").append(r.assumed.joinToString()).append('\n')
            if (r.assumptions.isNotEmpty()) append("   assuming: ").append(r.assumptions.joinToString("; ")).append('\n')
            r.changes.groupBy { it.kind }.forEach { (k, cs) ->
                if (k != ChangeKind.PRESERVED) append("   ").append(k.name.lowercase()).append(": ").append(cs.joinToString { it.what }).append('\n')
            }
            r.note?.let { append("   ").append(it).append('\n') }
        }
        if (leftDevice) append("Sent off the phone on the way.\n")
    }.trimEnd()

    fun toJson(): JSONObject = JSONObject().apply {
        put("io", 1)
        put("source", JSONObject().apply {
            put("transport", source.transport)
            put("type", source.type)
            put("representation", source.representation)
            if (source.inferred) {
                put("interpretedAs", source.interpretedAs)
                put("inferred", true)
            }
        })
        put("records", JSONArray(records.map { it.toJson() }))
        earlier?.let { put("earlier", it.toJson()) }
    }

    companion object {
        /** Null for anything that is not a history written by [toJson] — never a half-read one. */
        fun fromJson(raw: String?): Provenance? {
            if (raw.isNullOrBlank()) return null
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            return fromJson(o)
        }

        fun fromJson(o: JSONObject): Provenance? {
            if (o.optInt("io", 0) != 1) return null
            val s = o.optJSONObject("source") ?: return null
            val records = o.optJSONArray("records")?.let { arr ->
                (0 until arr.length()).map { i -> arr.optJSONObject(i)?.let { Record.fromJson(it) } ?: return null }
            }.orEmpty()
            return Provenance(
                source = Source(
                    transport = s.optString("transport"),
                    type = s.optString("type"),
                    representation = s.optString("representation"),
                    interpretedAs = s.optString("interpretedAs"),
                    inferred = s.optBoolean("inferred", false)
                ),
                records = records,
                earlier = o.optJSONObject("earlier")?.let { fromJson(it) }
            )
        }
    }
}
