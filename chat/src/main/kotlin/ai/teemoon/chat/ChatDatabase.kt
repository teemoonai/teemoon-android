package ai.teemoon.chat

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
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
@Database(entities = [ThreadEntity::class, MessageEntity::class, AttachmentEntity::class], version = 3, exportSchema = true)
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
            instance ?: builder(context, "teemoon-chat.db").build().also { instance = it }
        }

        /**
         * DELETED TEXT MUST NOT STAY READABLE (the maintainer, 2026-10-19: "yes residue fix"; measured: a deleted chat's text was
         * still in teemoon-chat.db and its -wal, DeletedTextResidueTest). With [secureDelete] the delete zeroes the pages
         * it frees; the WAL still holds them as they were, so it is checkpointed and truncated after every chat delete,
         * and after delete all the file is VACUUMed down to what is left.
         */
        fun maintenance(db: ChatDatabase, scope: kotlinx.coroutines.CoroutineScope): StoreMaintenance {
            // In the background, one at a time: "delete all" runs from the settings screen (MainActivity) and a VACUUM of
            // a big store must not hold the frame.
            val lock = kotlinx.coroutines.sync.Mutex()
            return StoreMaintenance { all ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    lock.withLock {
                        runCatching {
                            val w = db.openHelper.writableDatabase
                            w.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                            if (all) { w.execSQL("VACUUM"); w.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
                        }
                    }
                }
            }
        }

        /**
         * PRAGMA secure_delete on the connection that writes (platform SQLite routes a statement that sets a pragma to it).
         * Then a checkpoint: a kill between a delete and its [maintenance] scrub (a queued job, iOS e871b7a's window) left
         * the deleted text in the file; the next open finishes that scrub. Cheap when the WAL is empty.
         */
        private val secureDelete = object : RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
                runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
            }
        }

        /**
         * 1 → 2 (1.1.0, IMAGES.md §1, the maintainer approved 2026-10-25): images. It ONLY creates the attachments table and its
         * index; threads and messages are not touched, so every chat opens as it was. The SQL is Room's own for
         * [AttachmentEntity] (schemas/…/2.json); ChatMigrationTest opens a version-1 store under it and checks every
         * row. A 1.0.6 build opening a version-2 store fails its migration and crashes — never a wipe.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `idx` INTEGER NOT NULL, `mime` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `path` TEXT NOT NULL, `thumbnail` BLOB NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_message_id` ON `attachments` (`message_id`)")
            }
        }

        /**
         * 2 → 3 (1.1.0, the maintainer approved 2026-11-06): two nullable columns, `messages.status` (G3) and
         * `messages.sentBeforeConfirmed` (sent-before-confirmed-mark). Nothing is rewritten:
         * every existing row reads null, which means complete. ChatMigrationTest opens version-2 and version-1 stores
         * under it and checks every row. A 1.1.0 build before this one opening a version-3 store fails and crashes — never a wipe.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `status` TEXT")
                // the sent-before-confirmed mark (the maintainer's typed yes, 2026-11-06), folded in while v3 is unshipped
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `sentBeforeConfirmed` TEXT")
            }
        }

        /** The store's one builder: the app's database and the device tests' scratch stores are opened alike. */
        fun builder(context: Context, name: String): RoomDatabase.Builder<ChatDatabase> =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(secureDelete)
                // No fallbackToDestructiveMigration. See the class comment.
    }
}
