package com.example.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ClipboardEntity::class, WordEntity::class, BigramEntity::class, ShortcutEntity::class],
    version = 3,
    exportSchema = false
)
abstract class KeyboardDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun shortcutDao(): ShortcutDao

    companion object {
        private const val NAME = "clipboard_db"

        @Volatile
        private var instance: KeyboardDatabase? = null

        fun get(context: Context): KeyboardDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KeyboardDatabase::class.java,
                    NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        /**
         * Version 3 lets a clipboard entry be something other than text.
         *
         * All columns, no table changes, and every one nullable or defaulted — so
         * everything already copied survives, and an old row simply reads as the text
         * entry it always was.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE clipboard_items ADD COLUMN mime TEXT NOT NULL DEFAULT 'text/plain'"
                )
                db.execSQL("ALTER TABLE clipboard_items ADD COLUMN filePath TEXT")
                db.execSQL(
                    "ALTER TABLE clipboard_items ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE clipboard_items ADD COLUMN sourcePackage TEXT")
                db.execSQL("ALTER TABLE clipboard_items ADD COLUMN groupId TEXT")
            }
        }

        /**
         * Version 1 shipped only the clipboard table. Adding columns and tables rather
         * than recreating keeps whatever the user had already copied.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clipboard_items ADD COLUMN label TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS dictionary_words (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "word TEXT NOT NULL, " +
                        "count INTEGER NOT NULL, " +
                        "locale TEXT NOT NULL, " +
                        "locked INTEGER NOT NULL, " +
                        "blocked INTEGER NOT NULL, " +
                        "lastUsed INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_dictionary_words_word ON dictionary_words (word)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS word_bigrams (" +
                        "previous TEXT NOT NULL, " +
                        "next TEXT NOT NULL, " +
                        "count INTEGER NOT NULL, " +
                        "lastUsed INTEGER NOT NULL, " +
                        "PRIMARY KEY(previous, next))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS text_shortcuts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "shortcut TEXT NOT NULL, " +
                        "expansion TEXT NOT NULL, " +
                        "automatic INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_text_shortcuts_shortcut ON text_shortcuts (shortcut)"
                )
            }
        }
    }
}
