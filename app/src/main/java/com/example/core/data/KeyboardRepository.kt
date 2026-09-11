package com.example.core.data

import android.content.Context
import com.example.core.text.TextOps
import kotlinx.coroutines.flow.Flow

/**
 * Everything persisted about the user's typing, behind one object.
 *
 * All writes are suspending and all reads that the UI needs are flows, so nothing here
 * can block the input thread — a keyboard that stutters while it writes to SQLite is
 * worse than a keyboard with no history at all.
 */
class KeyboardRepository(context: Context) {

    private val db = KeyboardDatabase.get(context)
    private val clipboard = db.clipboardDao()
    private val dictionary = db.dictionaryDao()
    private val shortcuts = db.shortcutDao()

    // --- clipboard ---------------------------------------------------------

    fun observeClipboard(): Flow<List<ClipboardEntity>> = clipboard.observeAll()

    fun searchClipboard(query: String): Flow<List<ClipboardEntity>> = clipboard.search(query)

    suspend fun rememberClip(type: String, content: String, maxItems: Int) {
        if (content.isBlank()) return
        if (clipboard.newest()?.content == content) return
        clipboard.deleteByContent(content)
        clipboard.insert(ClipboardEntity(type = type, content = content))
        if (maxItems > 0) clipboard.trimTo(maxItems)
    }

    suspend fun newestClip(): ClipboardEntity? = clipboard.newest()

    suspend fun deleteClip(id: Long) = clipboard.deleteById(id)
    suspend fun setClipPinned(id: Long, pinned: Boolean) = clipboard.setPinned(id, pinned)
    suspend fun updateClip(id: Long, content: String) = clipboard.updateContent(id, content)
    suspend fun clearClipboard() = clipboard.deleteAllUnpinned()
    suspend fun sweepClipboard(retentionDays: Int) {
        if (retentionDays <= 0) return
        clipboard.deleteOlderThan(System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L)
    }

    // --- dictionary --------------------------------------------------------

    fun observeDictionary(limit: Int = 500, offset: Int = 0): Flow<List<WordEntity>> =
        dictionary.observeAll(limit, offset)

    suspend fun dictionarySize(): Int = dictionary.count()

    /** Records one typed word and the transition into it. */
    suspend fun learn(previous: String?, word: String, locale: String) {
        if (word.length < 2 || word.any { !TextOps.isWordChar(it) }) return
        val existing = dictionary.find(word)
        if (existing != null) {
            if (existing.blocked) return
            dictionary.bump(word, System.currentTimeMillis())
        } else {
            dictionary.upsert(WordEntity(word = word, count = 1, locale = locale))
        }
        if (!previous.isNullOrEmpty()) {
            val bigram = dictionary.findBigram(previous, word)
            dictionary.upsertBigram(
                bigram?.copy(count = bigram.count + 1, lastUsed = System.currentTimeMillis())
                    ?: BigramEntity(previous = previous, next = word)
            )
        }
    }

    suspend fun addWord(word: String, locale: String = "") {
        if (word.isBlank()) return
        dictionary.upsert(
            WordEntity(
                id = dictionary.find(word)?.id ?: 0,
                word = word,
                count = (dictionary.find(word)?.count ?: 0) + 5,
                locale = locale,
                locked = true
            )
        )
    }

    /**
     * Adds many words at once, for importing a real word list.
     *
     * Accepts one word per line, optionally followed by whitespace and a count, which
     * is the format most published frequency lists already use. Imported words are
     * marked as the user's own so automatic pruning leaves them alone.
     */
    suspend fun importWords(text: CharSequence, locale: String = ""): Int {
        val separator = Regex("[\\s,;]+")
        var imported = 0
        // Written in batches: one query per word turns a 100,000-line list into
        // several minutes of work for no reason.
        text.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(separator)
                val word = parts.firstOrNull()?.lowercase().orEmpty()
                if (word.length < 2 || word.any { !TextOps.isWordChar(it) }) return@mapNotNull null
                WordEntity(
                    word = word,
                    count = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 1_000_000),
                    locale = locale,
                    locked = true
                )
            }
            .distinctBy { it.word }
            .chunked(500)
            .forEach { batch ->
                dictionary.upsertAll(batch)
                imported += batch.size
            }
        return imported
    }

    suspend fun blockWord(word: String) = dictionary.block(word)
    suspend fun deleteWord(word: String) = dictionary.delete(word)
    suspend fun clearLearnedWords() {
        dictionary.clearLearned()
        dictionary.clearBigrams()
    }

    /** True when the user has typed this word before and has not blocked it. */
    suspend fun knows(word: String): Boolean {
        val entity = dictionary.find(word) ?: return false
        return !entity.blocked
    }

    suspend fun wordsStartingWith(prefix: String, limit: Int): List<WordEntity> =
        if (prefix.isEmpty()) emptyList() else dictionary.startingWith(prefix, limit)

    suspend fun nextWords(previous: String, prefix: String, limit: Int): List<BigramEntity> =
        if (previous.isEmpty()) emptyList() else dictionary.following(previous, prefix, limit)

    // --- text shortcuts ----------------------------------------------------

    fun observeShortcuts(): Flow<List<ShortcutEntity>> = shortcuts.observeAll()
    suspend fun allShortcuts(): List<ShortcutEntity> = shortcuts.all()
    suspend fun findShortcut(key: String): ShortcutEntity? = shortcuts.find(key)
    suspend fun saveShortcut(item: ShortcutEntity) = shortcuts.upsert(item)
    suspend fun deleteShortcut(id: Long) = shortcuts.delete(id)

    companion object {
        @Volatile
        private var instance: KeyboardRepository? = null

        fun get(context: Context): KeyboardRepository =
            instance ?: synchronized(this) {
                instance ?: KeyboardRepository(context.applicationContext).also { instance = it }
            }
    }
}
