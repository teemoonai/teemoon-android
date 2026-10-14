// ports: teemoon/Chat/Search/ChatSearchIndex.swift @ v1.0.1 (the sqlite plumbing, as a driver seam)
package ai.teemoon.chat.search

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File

/** One positioned row of a query result. */
interface SearchSqlRow {
    fun string(i: Int): String?
    fun long(i: Int): Long
    fun double(i: Int): Double
}

/**
 * The handful of sqlite operations the index needs, so the same SQL runs on the
 * platform SQLite in the app and on a JDBC SQLite under JVM tests. Binds are
 * TYPED: a `ts >= ?` bound as text would compare wrongly against a number.
 */
interface SearchSqlDatabase : AutoCloseable {
    /** Executes one or more `;`-separated statements with no binds. */
    fun exec(sql: String)
    /** Executes one statement with binds. */
    fun run(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), row: (SearchSqlRow) -> T): List<T>
    fun lastInsertRowId(): Long
}

/**
 * The app's driver: AndroidX's bundled SQLite, which compiles in FTS5 on every
 * device. The PLATFORM SQLite does not promise it — the API 34 ATD image
 * answered `no such module: fts5`, and an index that exists on one phone and
 * not another is a search that lies by omission. Same engine everywhere is the
 * uniformity iOS gets from its OS.
 */
class BundledSearchSqlDatabase(path: File) : SearchSqlDatabase {
    private val conn: androidx.sqlite.SQLiteConnection = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path.path)

    override fun exec(sql: String) {
        for (statement in sql.split(';')) if (statement.isNotBlank()) run(statement)
    }

    override fun run(sql: String, args: List<Any?>) {
        val st = conn.prepare(sql)
        try { args.forEachIndexed { i, a -> bind(st, i + 1, a) }; st.step() } finally { st.close() }
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (SearchSqlRow) -> T): List<T> {
        val st = conn.prepare(sql)
        try {
            args.forEachIndexed { i, a -> bind(st, i + 1, a) }
            val r = object : SearchSqlRow {
                override fun string(i: Int): String? = if (st.isNull(i)) null else st.getText(i)
                override fun long(i: Int): Long = st.getLong(i)
                override fun double(i: Int): Double = st.getDouble(i)
            }
            val out = ArrayList<T>()
            while (st.step()) out.add(row(r))
            return out
        } finally { st.close() }
    }

    override fun lastInsertRowId(): Long = query("SELECT last_insert_rowid()") { it.long(0) }.first()

    override fun close() = conn.close()

    private fun bind(st: androidx.sqlite.SQLiteStatement, i: Int, a: Any?) = when (a) {
        null -> st.bindNull(i)
        is Long -> st.bindLong(i, a)
        is Int -> st.bindLong(i, a.toLong())
        is Double -> st.bindDouble(i, a)
        else -> st.bindText(i, a.toString())
    }
}

/** The platform SQLite driver — kept for a device whose bundled engine cannot load; FTS5 is NOT guaranteed here. */
class FrameworkSearchSqlDatabase(path: File) : SearchSqlDatabase {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(path, null)

    override fun exec(sql: String) {
        for (statement in sql.split(';')) {
            if (statement.isBlank()) continue
            // A PRAGMA that answers (journal_mode, user_version) is a QUERY to the
            // platform SQLite: execSQL throws "Queries can be performed using
            // SQLiteDatabase query or rawQuery methods only" — observed on the
            // API 34 ATD image, where it silently disabled search. Drive every
            // PRAGMA through rawQuery and step it once so it actually runs.
            if (statement.trimStart().startsWith("PRAGMA", ignoreCase = true)) db.rawQuery(statement, null).use { it.moveToFirst() }
            else db.execSQL(statement)
        }
    }

    override fun run(sql: String, args: List<Any?>) {
        val stmt = db.compileStatement(sql)
        try {
            args.forEachIndexed { i, a -> bind(stmt, i + 1, a) }
            stmt.execute()
        } finally { stmt.close() }
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (SearchSqlRow) -> T): List<T> {
        val cursor = db.rawQueryWithFactory({ _, driver, editTable, q ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> q.bindNull(i + 1)
                    is Long -> q.bindLong(i + 1, a)
                    is Int -> q.bindLong(i + 1, a.toLong())
                    is Double -> q.bindDouble(i + 1, a)
                    else -> q.bindString(i + 1, a.toString())
                }
            }
            SQLiteCursor(driver, editTable, q)
        }, sql, emptyArray(), "")
        val out = ArrayList<T>()
        cursor.use { c ->
            val r = object : SearchSqlRow {
                override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
                override fun long(i: Int): Long = c.getLong(i)
                override fun double(i: Int): Double = c.getDouble(i)
            }
            while (c.moveToNext()) out.add(row(r))
        }
        return out
    }

    override fun lastInsertRowId(): Long = query("SELECT last_insert_rowid()") { it.long(0) }.first()

    override fun close() = db.close()

    private fun bind(stmt: SQLiteStatement, i: Int, a: Any?) = when (a) {
        null -> stmt.bindNull(i)
        is Long -> stmt.bindLong(i, a)
        is Int -> stmt.bindLong(i, a.toLong())
        is Double -> stmt.bindDouble(i, a)
        else -> stmt.bindString(i, a.toString())
    }
}
