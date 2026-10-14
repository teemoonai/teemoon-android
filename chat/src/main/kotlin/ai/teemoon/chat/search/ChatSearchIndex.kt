// ports: teemoon/Chat/Search/ChatSearchIndex.swift @ v1.0.1
//
// Full-text search over stored chat messages, backed by an FTS5 sidecar
// database beside the Room store.
//
// Five traps this type exists to hold shut. Each one has a test.
//
//  - THE SIDECAR IS NEVER INSIDE THE ROOM DATABASE. Room owns that schema and
//    every table in it is a migration surface. The index is a separate file,
//    derived from the same database path, and is derived data: a schema bump
//    DROPS it and the launch reconcile refills it.
//  - NO SIDECAR FOR AN IN-MEMORY STORE. A test or preview run must not leave a
//    persistent index of whatever store it opened behind on disk.
//  - USER TEXT IS NOT FTS5 SYNTAX. Raw `c++` throws `fts5: syntax error near
//    "+"`. Everything the user types goes through [matchExpression].
//  - KEYS ARE `MessageEntity.id` / `ThreadEntity.id` STRINGS, never rowids.
//  - THE SNIPPET DELIMITERS CANNOT COLLIDE, because indexing strips them from
//    the content first. After [sanitizeForStorage], they cannot appear.
//
// The caller owns the privacy consequence: this file holds a full second copy
// of every message in plaintext, so whoever creates it must also harden the
// sidecar file the way the store is hardened (backup exclusion).
package ai.teemoon.chat.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the sqlite handle for the FTS5 sidecar. Serialised by a mutex because
 * two callers race for it by construction: the launch reconcile walks the
 * whole store while the search field queries on every keystroke.
 */
class ChatSearchIndex(
    /** The sidecar path, for hardening. */
    val databaseFile: File?,
    private val open: () -> SearchSqlDatabase,
    private val warn: (String) -> Unit = {},
) : ChatSearchIndexing {

    constructor(databaseFile: File, warn: (String) -> Unit = {}) : this(databaseFile, { BundledSearchSqlDatabase(databaseFile) }, warn)

    private val lock = Mutex()
    private var db: SearchSqlDatabase? = null

    // MARK: Open / schema

    private fun handle(): SearchSqlDatabase {
        db?.let { return it }
        val opened = try { open() } catch (e: Exception) {
            throw ChatSearchIndexException("open", "could not open chat index at ${databaseFile?.path}: ${e.message}")
        }
        db = opened
        createSchema(opened)
        return opened
    }

    private fun createSchema(db: SearchSqlDatabase) {
        // A sidecar built by an older schema is DROPPED, not migrated: the tables
        // carry a tokenizer, `IF NOT EXISTS` would silently keep the old one, and
        // this file is derived data the reconcile refills. The version read is
        // its own statement, finished before WAL: a stepped statement holds an
        // implicit read transaction, and `journal_mode = WAL` refuses to run
        // inside one — which took the whole schema with it on iOS, measured as
        // 39 failing tests briefly misattributed to the tokenizer.
        val current = db.query("PRAGMA user_version") { it.long(0) }.firstOrNull() ?: 0L
        if (current != 0L && current != SCHEMA_VERSION) {
            exec(db, "DROP TABLE IF EXISTS messages; DROP TABLE IF EXISTS docs;", "schema rebuild")
        }
        // `porter unicode61 remove_diacritics 2`: porter measured its way in —
        // GLM live queried "grocery items" against a history that says
        // "groceries" and got nothing, twice, because "grocery" is not a PREFIX
        // of "groceries" (y→ies); stemming folds both to "groceri". Not trigram:
        // trigram costs 50 MB against 5 MB on the reference store to serve the
        // 189 messages that contain CJK. Revisit only with a measurement.
        //
        // Regular FTS5, not contentless: contentless cannot do snippet(), and
        // the snippet IS the feature. The price is ~12 MB of duplicated text.
        exec(db, """
            PRAGMA journal_mode = WAL;
            CREATE VIRTUAL TABLE IF NOT EXISTS messages USING fts5(
                content,
                message_id UNINDEXED,
                thread_id UNINDEXED,
                role UNINDEXED,
                ts UNINDEXED,
                tokenize = 'porter unicode61 remove_diacritics 2'
            );
            CREATE TABLE IF NOT EXISTS docs(
                message_id TEXT PRIMARY KEY,
                rowid_ref INTEGER NOT NULL,
                thread_id TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS docs_thread ON docs(thread_id)
        """.trimIndent(), "create schema")
        runCatching { db.exec("PRAGMA user_version = $SCHEMA_VERSION") }
    }

    private suspend fun <T> locked(block: (SearchSqlDatabase) -> T): T = withContext(Dispatchers.IO) { lock.withLock { block(handle()) } }

    // MARK: Writes

    override suspend fun index(records: List<ChatMessageRecord>) {
        if (records.isEmpty()) return
        locked { db ->
            exec(db, "BEGIN IMMEDIATE", "begin")
            try {
                for (record in records) upsert(record, db)
                exec(db, "COMMIT", "commit")
            } catch (e: Exception) {
                runCatching { db.exec("ROLLBACK") }
                throw e
            }
        }
    }

    private fun upsert(record: ChatMessageRecord, db: SearchSqlDatabase) {
        deleteRows(listOf(record.messageID), db)
        db.run("INSERT INTO messages(content, message_id, thread_id, role, ts) VALUES(?, ?, ?, ?, ?)",
            listOf(sanitizeForStorage(record.content), record.messageID, record.threadID, record.role, record.timestampMs))
        val rowid = db.lastInsertRowId()
        db.run("INSERT INTO docs(message_id, rowid_ref, thread_id) VALUES(?, ?, ?)", listOf(record.messageID, rowid, record.threadID))
    }

    override suspend fun remove(messageIDs: List<String>) {
        if (messageIDs.isEmpty()) return
        locked { db ->
            exec(db, "BEGIN IMMEDIATE", "begin")
            try {
                deleteRows(messageIDs, db)
                exec(db, "COMMIT", "commit")
            } catch (e: Exception) {
                runCatching { db.exec("ROLLBACK") }
                throw e
            }
        }
    }

    private fun deleteRows(messageIDs: List<String>, db: SearchSqlDatabase) {
        for (id in messageIDs) {
            val rowid = db.query("SELECT rowid_ref FROM docs WHERE message_id = ?", listOf(id)) { it.long(0) }.firstOrNull() ?: continue
            db.run("DELETE FROM messages WHERE rowid = ?", listOf(rowid))
            db.run("DELETE FROM docs WHERE message_id = ?", listOf(id))
        }
    }

    /** Drops every row for a thread. Indexed by `docs_thread`, so this stays cheap on the delete path where the user is watching a row animate away. */
    override suspend fun removeThread(threadID: String) {
        val ids = locked { db -> db.query("SELECT message_id FROM docs WHERE thread_id = ?", listOf(threadID)) { it.string(0) }.filterNotNull() }
        remove(ids)
    }

    /** Empties the index. "delete all chats" must reach this, or it leaves a searchable plaintext copy of every one. */
    override suspend fun removeAll() { locked { db -> exec(db, "DELETE FROM messages; DELETE FROM docs", "remove all") } }

    // MARK: Reads

    override suspend fun indexedMessageIDs(): Set<String> =
        locked { db -> db.query("SELECT message_id FROM docs") { it.string(0) }.filterNotNull().toSet() }

    override suspend fun search(
        query: String, granularity: ChatSearchGranularity, limit: Int, sinceMs: Long?, excludingThread: String?, mode: ChatSearchMatchMode,
    ): List<ChatSearchResult> {
        val match = matchExpression(query, mode) ?: return emptyList()
        if (limit <= 0) return emptyList()
        val ranked = locked { db ->
            rankedRows(db, match, if (granularity == ChatSearchGranularity.THREADS) threadOverFetch(limit) else limit, sinceMs, excludingThread)
        }
        return when (granularity) {
            ChatSearchGranularity.MESSAGES -> ranked.take(limit)
            ChatSearchGranularity.THREADS -> {
                // Rows arrive in rank order, so a thread's first appearance IS its
                // best message — the same row `min(bm25)` would have chosen.
                val seen = HashSet<String>(); val best = ArrayList<ChatSearchResult>()
                for (row in ranked) {
                    if (!seen.add(row.threadID)) continue
                    best.add(row); if (best.size == limit) break
                }
                best
            }
        }
    }

    /**
     * The one ranked query both granularities share. [sinceMs] and
     * [excludingThread] live in the WHERE — `ts`/`thread_id` are ordinary
     * stored columns of the FTS table — so LIMIT counts surviving rows.
     */
    private fun rankedRows(db: SearchSqlDatabase, match: String, limit: Int, sinceMs: Long?, excludingThread: String?): List<ChatSearchResult> {
        var conditions = "messages MATCH ?"
        val args = arrayListOf<Any?>(HIGHLIGHT_OPEN, HIGHLIGHT_CLOSE, match)
        if (sinceMs != null) { conditions += " AND ts >= ?"; args.add(sinceMs) }
        if (excludingThread != null) { conditions += " AND thread_id <> ?"; args.add(excludingThread) }
        args.add(maxOf(0, limit))
        // bm25() is lower-is-better, so ORDER BY is ascending. NEVER let a failed
        // step read as "no matches": a malformed query used to return zero rows,
        // indistinguishable from a working search of a history that does not
        // contain the term — a false claim about the user's data. The driver
        // throws; we rethrow as the index's own error.
        return try {
            db.query("""
                SELECT message_id, thread_id, role, ts,
                       snippet(messages, 0, ?, ?, '…', 18) AS snip,
                       bm25(messages) AS rank
                FROM messages
                WHERE $conditions
                ORDER BY rank
                LIMIT ?
            """.trimIndent(), args) { r ->
                ChatSearchResult(r.string(0) ?: "", r.string(1) ?: "", r.string(2) ?: "user", r.long(3), r.string(4) ?: "")
            }.filter { it.messageID.isNotEmpty() && it.threadID.isNotEmpty() }
        } catch (e: ChatSearchIndexException) { throw e } catch (e: Exception) {
            throw ChatSearchIndexException("search", e.message ?: "unknown")
        }
    }

    override suspend fun following(threadID: String, afterMs: Long): ChatSearchResult? = locked { db ->
        // FULL content, not an FTS snippet — this row didn't match anything, so there is no window to centre. Callers own the cap.
        contentRows(db, "SELECT message_id, thread_id, role, ts, content FROM messages WHERE thread_id = ? AND ts > ? ORDER BY ts ASC LIMIT 1",
            listOf(threadID, afterMs), "following").firstOrNull()
    }

    override suspend fun window(threadID: String, aroundMs: Long, before: Int, after: Int): List<ChatSearchResult> = locked { db ->
        // Two bounded queries rather than one clever one: at-or-before descending
        // (the target row rides in here), then strictly-after ascending. Merged in time order.
        val rows = contentRows(db, "SELECT message_id, thread_id, role, ts, content FROM messages WHERE thread_id = ? AND ts <= ? ORDER BY ts DESC LIMIT ?",
            listOf(threadID, aroundMs, maxOf(0, before) + 1), "window backward").reversed().toMutableList()
        if (after > 0) rows.addAll(contentRows(db, "SELECT message_id, thread_id, role, ts, content FROM messages WHERE thread_id = ? AND ts > ? ORDER BY ts ASC LIMIT ?",
            listOf(threadID, aroundMs, after), "window forward"))
        rows
    }

    private fun contentRows(db: SearchSqlDatabase, sql: String, args: List<Any?>, operation: String): List<ChatSearchResult> = try {
        db.query(sql, args) { r -> ChatSearchResult(r.string(0) ?: "", r.string(1) ?: "", r.string(2) ?: "user", r.long(3), r.string(4) ?: "") }
            .filter { it.messageID.isNotEmpty() && it.threadID.isNotEmpty() }
    } catch (e: Exception) { throw ChatSearchIndexException(operation, e.message ?: "unknown") }

    private fun exec(db: SearchSqlDatabase, sql: String, operation: String) {
        try { db.exec(sql) } catch (e: Exception) {
            warn("chat index $operation failed: ${e.javaClass.simpleName}")   // type only: SQLite messages can quote indexed text
            throw ChatSearchIndexException(operation, e.message ?: "unknown")
        }
    }

    companion object {
        /**
         * Wrapped around matched terms by `snippet()`. U+0001/U+0002 rather than
         * anything printable: the same string is parsed into styled runs for the
         * list card AND embedded in the envelope handed to a model, and any
         * delimiter that survives in message text breaks one or the other.
         * [sanitizeForStorage] removes them on the way in, so a collision is
         * impossible rather than unlikely.
         */
        const val HIGHLIGHT_OPEN = "\u0001"
        const val HIGHLIGHT_CLOSE = "\u0002"

        /** Bumping this DROPS and recreates the tables on next open — a tokenizer change is a rebuild, never a migration. v2: porter stemming. */
        const val SCHEMA_VERSION = 2L

        /**
         * The sidecar that belongs to the store at [storeFile], or null when
         * there must not be one. Null for an in-memory store: a caller that
         * trusts a file path blindly writes a real index for a store that does
         * not exist.
         */
        fun sidecarFile(storeFile: File, isStoredInMemoryOnly: Boolean): File? =
            if (isStoredInMemoryOnly) null else File(storeFile.parentFile, "chat-search-index.sqlite")

        /**
         * How many message rows to pull before collapsing to threads. The
         * collapse cannot happen in SQL: FTS5 refuses its auxiliary functions
         * outside a direct query on the table. The cap is a RECALL cliff, not a
         * tuning knob (the "iran bug", 2026-08-30): bm25's length normalization
         * ranks a term buried in one long reply below dozens of short passing
         * mentions, so any match ranked past this cap never reaches the thread
         * collapse at all. 4096 keeps the one-word-query bound while making the
         * cliff unreachable for a store this app's size.
         */
        fun threadOverFetch(limit: Int): Int = minOf(limit * 8, 4096)

        /**
         * Turns what the user typed into an FTS5 MATCH expression, or null when
         * there is nothing to search for. FTS5's query language is not a search
         * box: `c++` raises a syntax error, AND/OR/NOT/NEAR are operators, and an
         * unbalanced `"` is a parse error. Every token is quoted, which makes
         * each one a literal phrase, and the last gets `*` so results narrow as
         * you type — that trailing `*` is also what produces the partial-word
         * highlight. null (rather than "") for "nothing to search": zero rows is
         * a claim about the user's history that a blank search box has not earned.
         */
        fun matchExpression(query: String, mode: ChatSearchMatchMode = ChatSearchMatchMode.ALL_TERMS): String? {
            val tokens = query.split(Regex("\\s+")).map { it.replace("\"", "") }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            return when (mode) {
                ChatSearchMatchMode.ALL_TERMS -> tokens.mapIndexed { i, t -> if (i == tokens.lastIndex) "\"$t\"*" else "\"$t\"" }.joinToString(" ")
                // Recall over precision: every token prefixed and OR'd, with bm25 still ranking multi-term matches first.
                ChatSearchMatchMode.ANY_TERM -> tokens.joinToString(" OR ") { "\"$it\"*" }
            }
        }

        /**
         * Removes the highlight delimiters from text on its way into the index.
         * Replaced with a SPACE, not with nothing: deleting them welds the
         * neighbouring words into one token — `a<open>b<close>c` became the
         * single term `abc`, so searching for `b` found nothing.
         */
        fun sanitizeForStorage(content: String): String =
            if (!content.contains(HIGHLIGHT_OPEN) && !content.contains(HIGHLIGHT_CLOSE)) content
            else content.replace(HIGHLIGHT_OPEN, " ").replace(HIGHLIGHT_CLOSE, " ")
    }
}
