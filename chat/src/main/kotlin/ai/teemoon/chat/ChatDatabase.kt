package ai.teemoon.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The user's conversations.
 *
 * **`fallbackToDestructiveMigration()` is forbidden in this codebase** and must
 * never be added here, not even temporarily. It is the one line that turns a
 * failing schema change into a passing build by deleting every thread and
 * message the user has. A migration that cannot be written is a conversation to
 * have, not a table to drop.
 *
 * Adding a nullable column or one with a default is safe. Anything else —
 * removing or renaming a column, changing a type, changing a primary key —
 * needs an explicit Migration plus a MigrationTestHelper test.
 */
@Database(entities = [ThreadEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    /** What opening the store produced. The file is never deleted on either path. */
    sealed interface Open {
        data class Ready(val db: ChatDatabase) : Open
        /**
         * The file could not be opened for a reason OTHER than a schema migration: it stays
         * on disk untouched and the app runs on an in-memory store without history, telling
         * the user why (iOS TeemoonApp.storeOpenFailure). A migration failure is not caught
         * here — CONTRIBUTING.md: it must crash, never wipe, never silently run without history.
         */
        data class Failed(val db: ChatDatabase, val reason: String) : Open
    }

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        /** Room raises a migration problem as an IllegalStateException naming the migration; nothing else is one. */
        fun isMigrationFailure(t: Throwable): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is IllegalStateException && (cur.message ?: "").contains("migration", ignoreCase = true)) return true
                cur = cur.cause
            }
            return false
        }

        /** Opens the on-disk store and PROBES it now, so a corrupt file is discovered at launch and not mid-chat. */
        fun open(context: Context): Open {
            val db = get(context)
            return try {
                db.openHelper.writableDatabase
                Open.Ready(db)
            } catch (t: Throwable) {
                if (isMigrationFailure(t)) throw t
                val memory = Room.inMemoryDatabaseBuilder(context.applicationContext, ChatDatabase::class.java).build()
                Open.Failed(memory, t.message ?: t.toString())
            }
        }

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ChatDatabase::class.java, "teemoon-chat.db")
                // No fallbackToDestructiveMigration. See the class comment.
                .build()
                .also { instance = it }
        }
    }
}
