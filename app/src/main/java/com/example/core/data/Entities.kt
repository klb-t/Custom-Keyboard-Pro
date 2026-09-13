package com.example.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One remembered clipboard entry.
 *
 * `type` distinguishes text from a content URI; a URI is stored by reference because
 * copying the bytes of every screenshot the user takes is not a trade anyone asked for.
 */
@Entity(tableName = "clipboard_items")
data class ClipboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Pinned entries survive retention sweeps and "clear all". */
    val pinned: Boolean = false,
    val label: String? = null,
    /**
     * What this actually is. Text entries say so; everything else carries the type
     * the source app declared, which is what a paste needs in order to be accepted.
     */
    val mime: String = "text/plain",
    /**
     * Where the bytes live, in our own storage.
     *
     * Not a `content://` URI, deliberately. A URI on the clipboard carries a
     * temporary read grant tied to that clip, so keeping the URI string produces an
     * entry that is unreadable by the time anyone wants it. The bytes are copied at
     * capture, which is the only moment they can be.
     */
    val filePath: String? = null,
    val sizeBytes: Long = 0L,
    /** Which app it was copied from, when the system says. */
    val sourcePackage: String? = null,
    /**
     * Parts of one copy that arrived together.
     *
     * A single clip can hold several items — a file plus its name, a selection plus
     * its HTML — and splitting them into rows keeps each one usable on its own while
     * this records that they came as one thing.
     */
    val groupId: String? = null
) {
    val isText: Boolean get() = type == TYPE_TEXT

    companion object {
        const val TYPE_TEXT = "TEXT"
        const val TYPE_URI = "URI"

        /** Bytes of our own, on disk. The type every non-text entry has from now on. */
        const val TYPE_FILE = "FILE"
    }
}

/**
 * A word the user has typed, with how often. Feeds suggestions and correction.
 *
 * `locked` marks words the user added by hand, so automatic pruning leaves them alone.
 * `blocked` marks words the user rejected, which are never suggested again.
 */
@Entity(tableName = "dictionary_words", indices = [Index(value = ["word"], unique = true)])
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val count: Int = 1,
    val locale: String = "",
    val locked: Boolean = false,
    val blocked: Boolean = false,
    val lastUsed: Long = System.currentTimeMillis()
)

/**
 * "After word A, the user typed word B" — the whole of the next-word model.
 *
 * A bigram table is a small, private, instantly-available predictor. It is what makes
 * suggestions useful with no network and no account, which is the point.
 */
@Entity(tableName = "word_bigrams", primaryKeys = ["previous", "next"])
data class BigramEntity(
    val previous: String,
    val next: String,
    val count: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)

/** A user-defined text expansion: type the shortcut, get the expansion. */
@Entity(tableName = "text_shortcuts", indices = [Index(value = ["shortcut"], unique = true)])
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shortcut: String,
    val expansion: String,
    /** Expand as soon as the shortcut is completed, rather than offering it. */
    val automatic: Boolean = false
)
