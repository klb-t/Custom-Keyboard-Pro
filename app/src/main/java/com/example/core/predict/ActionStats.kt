package com.example.core.predict

import com.example.core.io.Verbs
import com.example.core.layout.ClipboardOp
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyCodes
import com.example.core.layout.LayoutJson
import com.example.core.layout.MetaCodes
import org.json.JSONObject

/** Where the user is, as far as choosing a shortcut goes. */
data class ActionContext(
    val pkg: String?,
    /** A coarse kind of field: text, email, uri, number, password, search, none. */
    val field: String,
    val selection: Boolean,
    val emptyField: Boolean
)

/** One offered shortcut: what it shows and what it does. */
data class ActionChip(
    val label: String,
    val action: KeyAction,
    val key: String,
    /** Called up by name ("/copy") rather than offered unasked. */
    val called: Boolean = false,
    /**
     * Strong enough to take the strip's place: asked for by name, or what a selection
     * is almost always for. The rest wait politely beside the toolbar.
     */
    val prominent: Boolean = called
)

/**
 * Which shortcuts this person reaches for, where — so the strip can offer them.
 *
 * Words are predicted from what was typed before; shortcuts from where the typing is
 * happening. The same Ctrl+C that is noise in a message is the most-used key in a
 * terminal, and "paste" is what an empty field most often wants. So every shortcut
 * used is counted three times over — in exactly this situation, in this app, and
 * anywhere — and offered by a score that trusts the narrowest count most. A few
 * sensible defaults stand in until there is anything to count, and are outweighed by
 * the first handful of real uses.
 */
class ActionStats(private val maxPerBucket: Int = 40) {

    private class Entry(var count: Double, var lastAt: Long)

    private val buckets = mutableMapOf<String, MutableMap<String, Entry>>()

    private fun exact(c: ActionContext) = "c|${c.pkg}|${c.field}|${c.selection}|${c.emptyField}"
    private fun app(c: ActionContext) = "p|${c.pkg}"
    private val anywhere = "g"

    fun record(context: ActionContext, action: KeyAction, atMs: Long) {
        val key = keyOf(action) ?: return
        listOf(exact(context), app(context), anywhere).forEach { b ->
            val m = buckets.getOrPut(b) { mutableMapOf() }
            val e = m.getOrPut(key) { Entry(0.0, atMs) }
            e.count += 1.0
            e.lastAt = atMs
            if (m.size > maxPerBucket) {
                // Forget the least used, so a bucket keeps the shortcuts that matter.
                m.entries.minByOrNull { it.value.count }?.let { m.remove(it.key) }
            }
        }
    }

    /**
     * The best [count] shortcuts for [context]. [priors] are the defaults for this
     * situation, each worth [priorWeight] uses — enough to show before anything is
     * learned, little enough to be overtaken quickly.
     */
    fun suggest(
        context: ActionContext,
        count: Int,
        priors: List<KeyAction> = emptyList(),
        priorWeight: Double = 1.5
    ): List<KeyAction> {
        val scores = mutableMapOf<String, Double>()
        fun add(bucket: String, weight: Double) {
            buckets[bucket]?.forEach { (k, e) -> scores[k] = (scores[k] ?: 0.0) + e.count * weight }
        }
        add(exact(context), 4.0)
        add(app(context), 2.0)
        add(anywhere, 0.5)
        val byKey = mutableMapOf<String, KeyAction>()
        priors.forEachIndexed { i, a ->
            val k = keyOf(a) ?: return@forEachIndexed
            byKey[k] = a
            // Earlier priors a little ahead of later ones, so the order means something.
            scores[k] = (scores[k] ?: 0.0) + priorWeight * (1.0 - i * 0.05)
        }
        return scores.entries.sortedByDescending { it.value }
            .mapNotNull { (k, _) -> byKey[k] ?: actionOf(k) }
            .take(count)
    }

    fun toJson(): String = JSONObject().apply {
        buckets.forEach { (b, m) ->
            put(b, JSONObject().apply { m.forEach { (k, e) -> put(k, org.json.JSONArray(listOf(e.count, e.lastAt))) } })
        }
    }.toString()

    companion object {
        /**
         * Which actions count as shortcuts worth offering. Typing, cursor steps and
         * modifiers are the keyboard itself; offering them back would be noise.
         */
        fun isShortcut(action: KeyAction): Boolean = when (action) {
            is KeyAction.SendKey, is KeyAction.Clipboard, is KeyAction.Undo, is KeyAction.Redo,
            is KeyAction.Select, is KeyAction.Do, is KeyAction.Ai, is KeyAction.SwitchLayout,
            is KeyAction.Presentation -> true
            else -> false
        }

        fun keyOf(action: KeyAction): String? =
            if (!isShortcut(action)) null else LayoutJson.writeAction(action).toString()

        fun actionOf(key: String): KeyAction? =
            runCatching { LayoutJson.parseAction(JSONObject(key)) }.getOrNull()

        fun fromJson(raw: String?): ActionStats {
            val stats = ActionStats()
            if (raw.isNullOrBlank()) return stats
            runCatching {
                val o = JSONObject(raw)
                o.keys().forEach { b ->
                    val m = o.optJSONObject(b) ?: return@forEach
                    val bucket = stats.buckets.getOrPut(b) { mutableMapOf() }
                    m.keys().forEach { k ->
                        val arr = m.optJSONArray(k) ?: return@forEach
                        bucket[k] = Entry(arr.optDouble(0, 0.0), arr.optLong(1, 0L))
                    }
                }
            }
            return stats
        }

        /** What a shortcut is called on a chip — short, and what a person would say. */
        fun label(action: KeyAction): String = when (action) {
            is KeyAction.SendKey -> {
                val meta = MetaCodes.describe(action.metaState)
                    .split('+').filter { it.isNotBlank() }
                    .joinToString("+") { it.replaceFirstChar { c -> c.uppercase() } }
                val key = (KeyCodes.name(action.keyCode) ?: "key ${action.keyCode}")
                    .removePrefix("KEYCODE_").lowercase()
                    .replaceFirstChar { it.uppercase() }
                    .replace("Dpad_", "").replace("Move_", "")
                if (meta.isEmpty()) key else "$meta+$key"
            }
            is KeyAction.Clipboard -> when (action.op) {
                ClipboardOp.COPY -> "Copy"
                ClipboardOp.CUT -> "Cut"
                ClipboardOp.PASTE -> "Paste"
                else -> action.op.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            is KeyAction.Undo -> "Undo"
            is KeyAction.Redo -> "Redo"
            is KeyAction.Select -> "Select " + action.unit.name.lowercase()
            is KeyAction.Do -> Verbs.byId(action.command.verb)?.label ?: action.command.verb
            is KeyAction.Ai -> "AI: " + action.taskId
            is KeyAction.SwitchLayout -> action.layoutId ?: "Next layout"
            is KeyAction.Presentation -> action.mode.name.lowercase()
            else -> ""
        }

        /**
         * The defaults for a situation, before anything is learned: with a selection,
         * what people do with one; in an empty field, paste; in a terminal, the keys a
         * terminal needs and a phone keyboard hides.
         */
        fun priors(context: ActionContext, clipboardHasText: Boolean): List<KeyAction> = buildList {
            if (context.selection) {
                add(KeyAction.Clipboard(ClipboardOp.COPY))
                add(KeyAction.Clipboard(ClipboardOp.CUT))
                add(KeyAction.Do(com.example.core.io.Command("search")))
                add(KeyAction.Do(com.example.core.io.Command("share")))
            } else if (context.emptyField && clipboardHasText) {
                add(KeyAction.Clipboard(ClipboardOp.PASTE))
            }
            val pkg = context.pkg.orEmpty()
            if (TERMINALS.any { it in pkg }) {
                add(KeyAction.SendKey(android.view.KeyEvent.KEYCODE_C, android.view.KeyEvent.META_CTRL_ON))
                add(KeyAction.SendKey(android.view.KeyEvent.KEYCODE_TAB))
                add(KeyAction.SendKey(android.view.KeyEvent.KEYCODE_ESCAPE))
                add(KeyAction.SendKey(android.view.KeyEvent.KEYCODE_D, android.view.KeyEvent.META_CTRL_ON))
            }
        }

        private val TERMINALS = listOf("termux", "terminal", "ssh", "juicessh", "connectbot", "shell")
    }
}

/**
 * Shortcuts called up by name while typing: "/copy", "/torch", "/ctrl+z".
 *
 * The other half of shortcuts in the suggestion pool: the strip offers what fits the
 * situation unasked, and this finds what was asked for. Only after the command prefix
 * at the start of a word, so ordinary writing never turns into commands.
 */
object ActionCommands {

    /** The command being typed, without its prefix, or null when the word is not one. */
    fun typed(word: String, prefix: String): String? {
        if (prefix.isEmpty() || !word.startsWith(prefix)) return null
        return word.removePrefix(prefix).lowercase().takeIf { it.isNotEmpty() }
    }

    /** Actions whose name starts with what was typed, best first. */
    fun matching(query: String, candidates: List<KeyAction>, limit: Int): List<KeyAction> {
        val q = query.lowercase()
        return candidates.map { it to ActionStats.label(it).lowercase().replace(" ", "") }
            .filter { (_, name) -> name.startsWith(q) || name.contains(q) }
            .sortedBy { (_, name) -> if (name.startsWith(q)) 0 else 1 }
            .map { it.first }
            .distinctBy { ActionStats.keyOf(it) }
            .take(limit)
    }

    /** Everything that can be called by name: every verb with no arguments, and the editing keys. */
    val CATALOGUE: List<KeyAction> by lazy {
        Verbs.ALL.filter { v -> v.params.isEmpty() || v.id in setOf("scroll", "media", "search", "share", "torch", "pocket_lock", "record", "play") }
            .map { KeyAction.Do(com.example.core.io.Command(it.id)) } +
            listOf(
                KeyAction.Clipboard(ClipboardOp.COPY), KeyAction.Clipboard(ClipboardOp.CUT),
                KeyAction.Clipboard(ClipboardOp.PASTE), KeyAction.Undo, KeyAction.Redo,
                KeyAction.Select(com.example.core.layout.TextUnit.ALL),
                KeyAction.SendKey(android.view.KeyEvent.KEYCODE_ESCAPE),
                KeyAction.SendKey(android.view.KeyEvent.KEYCODE_TAB),
                KeyAction.SendKey(android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.META_CTRL_ON),
                KeyAction.SendKey(android.view.KeyEvent.KEYCODE_C, android.view.KeyEvent.META_CTRL_ON),
                KeyAction.SendKey(android.view.KeyEvent.KEYCODE_V, android.view.KeyEvent.META_CTRL_ON),
                KeyAction.SendKey(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON)
            )
    }
}
