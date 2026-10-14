package ai.teemoon.chat.search

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** The JVM stand-in for the platform SQLite: same SQL, xerial's bundled engine (FTS5 + porter enabled). */
class JdbcSearchSqlDatabase(path: File) : SearchSqlDatabase {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${path.path}")

    override fun exec(sql: String) {
        val st = conn.createStatement()
        try { for (s in sql.split(';')) if (s.isNotBlank()) st.execute(s) } finally { st.close() }
    }

    override fun run(sql: String, args: List<Any?>) {
        val ps = conn.prepareStatement(sql)
        try { args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.execute() } finally { ps.close() }
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (SearchSqlRow) -> T): List<T> {
        val ps = conn.prepareStatement(sql)
        try {
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            val rs = ps.executeQuery()
            val r = object : SearchSqlRow {
                override fun string(i: Int): String? = rs.getString(i + 1)
                override fun long(i: Int): Long = rs.getLong(i + 1)
                override fun double(i: Int): Double = rs.getDouble(i + 1)
            }
            val out = ArrayList<T>()
            while (rs.next()) out.add(row(r))
            return out
        } finally { ps.close() }
    }

    override fun lastInsertRowId(): Long = query("SELECT last_insert_rowid()") { it.long(0) }.first()

    override fun close() = conn.close()

    /** Rewrites `PRAGMA user_version` behind the index, to simulate an older sidecar. */
    fun setUserVersion(v: Long) = exec("PRAGMA user_version = $v")

    companion object {
        /** Opens, runs [block], closes. */
        fun <T> on(path: File, block: (JdbcSearchSqlDatabase) -> T): T {
            val db = JdbcSearchSqlDatabase(path)
            try { return block(db) } finally { db.close() }
        }
    }
}

/** Test helpers shared by the search suites. */
object SearchTestSupport {
    fun index(file: File): ChatSearchIndex = ChatSearchIndex(databaseFile = file, open = { JdbcSearchSqlDatabase(file) })
    fun record(content: String, thread: String = UUID.randomUUID().toString(), role: String = "user", atMs: Long = System.currentTimeMillis()) =
        ChatMessageRecord(messageID = UUID.randomUUID().toString(), threadID = thread, role = role, timestampMs = atMs, content = content)
    const val OPEN = ChatSearchIndex.HIGHLIGHT_OPEN
    const val CLOSE = ChatSearchIndex.HIGHLIGHT_CLOSE
}
