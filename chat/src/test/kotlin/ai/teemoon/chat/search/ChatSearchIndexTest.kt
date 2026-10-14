// ports: teemoonTests/ChatSearchIndexTests.swift @ v1.0.1
package ai.teemoon.chat.search

import ai.teemoon.chat.search.ChatSearchGranularity.MESSAGES
import ai.teemoon.chat.search.ChatSearchGranularity.THREADS
import ai.teemoon.chat.search.SearchTestSupport.OPEN
import ai.teemoon.chat.search.SearchTestSupport.record
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** Pins the FTS5 sidecar: the query sanitiser, the two granularities, delete paths, and the rules that keep the index off disk when it must not be there. */
class ChatSearchIndexTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file() = File(tmp.root, "chat-search-${UUID.randomUUID()}.sqlite")
    private fun makeIndex() = file().let { SearchTestSupport.index(it) to it }
    private val now = 1_700_000_000_000L
    private val day = 24 * 3600 * 1000L

    // MARK: - SQL-side filters

    /** Filtering rank-ordered rows AFTER a bounded fetch lets a common term with a deep history filter to zero. In SQL, LIMIT counts surviving rows. */
    @Test fun search_sinceIsAppliedBeforeTheLimit() = runTest {
        val (index, _) = makeIndex()
        val records = (0 until 40).map { record("old kettle note number $it", atMs = now - 400 * day) } + record("the recent kettle decision", atMs = now - 3_600_000)
        index.index(records)
        val hits = index.search("kettle", MESSAGES, 5, sinceMs = now - 7 * day, excludingThread = null)
        assertEquals(1, hits.size); assertTrue(hits[0].snippet.contains("recent"))
    }

    @Test fun search_excludingThreadIsAppliedBeforeTheLimit() = runTest {
        val (index, _) = makeIndex(); val noisy = "noisy"; val other = "other"
        index.index((0 until 40).map { record("kettle chatter $it", thread = noisy) } + record("the kettle answer, elsewhere", thread = other))
        val hits = index.search("kettle", MESSAGES, 5, sinceMs = null, excludingThread = noisy)
        assertEquals(1, hits.size); assertEquals(other, hits[0].threadID)
    }

    @Test fun search_bothFiltersComposeWithTheMatch() = runTest {
        val (index, _) = makeIndex()
        index.index(listOf(
            record("kettle in the excluded thread", thread = "here", atMs = now),
            record("kettle but ancient", thread = "there", atMs = now - 400 * day),
            record("kettle, recent and elsewhere", thread = "there", atMs = now - 60_000)))
        val hits = index.search("kettle", MESSAGES, 10, sinceMs = now - day, excludingThread = "here")
        assertEquals(1, hits.size); assertTrue(hits[0].snippet.contains("elsewhere"))
    }

    // MARK: - Following (the answer half of a matched question)

    @Test fun following_returnsTheNextMessageWithItsContentNotASnippet() = runTest {
        val (index, _) = makeIndex(); val thread = "t"
        val longAnswer = "For the trip: " + (0 until 40).joinToString(", ") { "item$it" }
        index.index(listOf(record("what should we pack?", thread, atMs = now), record(longAnswer, thread, role = "assistant", atMs = now + 60_000)))
        val next = index.following(thread, afterMs = now)!!
        assertEquals("assistant", next.role)
        assertTrue(next.snippet.contains("item20"))   // content that MATCHED NOTHING must not be squeezed through the 18-token window
        assertNull(index.following(thread, afterMs = now + 60_000))
    }

    @Test fun window_returnsContentLedRowsInOrderAroundAMoment() = runTest {
        val (index, _) = makeIndex(); val thread = "t"
        val deepTable = "roast table: " + (0 until 60).joinToString(", ") { "row$it" }
        index.index(listOf(record("one", thread, atMs = now), record("two", thread, atMs = now + 60_000), record(deepTable, thread, role = "assistant", atMs = now + 120_000),
            record("four", thread, atMs = now + 180_000), record("five", thread, atMs = now + 240_000)))
        val alone = index.window(thread, now + 120_000, before = 0, after = 0)
        assertEquals(1, alone.size); assertTrue(alone.first().snippet.contains("row55"))
        val around = index.window(thread, now + 120_000, before = 1, after = 2)
        assertEquals("two", around.first().snippet); assertEquals(4, around.size); assertEquals("five", around.last().snippet)
    }

    // MARK: - Stemming (schema v2)

    /** "grocery" is not a PREFIX of "groceries", so no prefix trick reaches it. Both stem to "groceri". */
    @Test fun search_stemmingFoldsMorphologyBothWays() = runTest {
        val (index, _) = makeIndex()
        index.index(listOf(record("what food and groceries should we get for the maui trip?")))
        assertEquals(1, index.search("grocery", MESSAGES, 5).size); assertEquals(1, index.search("groceries", MESSAGES, 5).size)
    }

    /** IF NOT EXISTS would silently keep a v1 table — and its tokenizer — forever. A version mismatch must drop and let reconcile refill. */
    @Test fun schema_versionBumpRebuildsRatherThanKeepingTheOldTokenizer() = runTest {
        val (index, path) = makeIndex()
        index.index(listOf(record("seed row")))
        assertEquals(1, SearchTestSupport.index(path).search("seed", MESSAGES, 5).size)   // same-version reopen must keep its rows
        JdbcSearchSqlDatabase.on(path) { it.setUserVersion(1) }                            // simulate an older sidecar
        val reopened = SearchTestSupport.index(path)
        assertTrue(reopened.search("seed", MESSAGES, 5).isEmpty()); assertTrue(reopened.indexedMessageIDs().isEmpty())
    }

    // MARK: - Any-term mode (the tool's recall rung)

    @Test fun match_anyTermPrefixesAndOrsEveryToken() {
        assertEquals("\"grocery\"* OR \"list\"* OR \"recommendations\"* OR \"store\"*", ChatSearchIndex.matchExpression("grocery list recommendations store", ChatSearchMatchMode.ANY_TERM))
        assertNull(ChatSearchIndex.matchExpression("   ", ChatSearchMatchMode.ANY_TERM))
    }

    /** Five of six words match nothing, and under AND that verdict is final. Any-term lets the one concrete token carry the search. */
    @Test fun search_anyTermFindsWhatConceptWordQueriesMiss() = runTest {
        val (index, _) = makeIndex()
        index.index(listOf(record("For Maui: poke bowls from Foodland, and a Costco Kahului run for water and snacks.")))
        val q = "grocery items Whole Foods Trader Joes Costco"
        assertTrue(index.search(q, MESSAGES, 5).isEmpty())
        assertEquals(1, index.search(q, MESSAGES, 5, null, null, ChatSearchMatchMode.ANY_TERM).size)
    }

    // MARK: - Query sanitiser: every case is something a user types by accident and FTS5 treats as syntax.

    @Test fun match_quotesEachTokenAndPrefixesTheLast() { assertEquals("\"hello\" \"world\"*", ChatSearchIndex.matchExpression("hello world")) }
    @Test fun match_survivesOperatorCharacters() { assertEquals("\"c++\"*", ChatSearchIndex.matchExpression("c++")) }
    @Test fun match_neutralisesBareOperators() {
        assertEquals("\"NOT\"*", ChatSearchIndex.matchExpression("NOT")); assertEquals("\"cats\" \"AND\" \"dogs\"*", ChatSearchIndex.matchExpression("cats AND dogs"))
    }
    @Test fun match_stripsQuotesRatherThanUnbalancingThem() { assertEquals("\"say\" \"hi\"*", ChatSearchIndex.matchExpression("say \"hi")) }
    @Test fun match_emptyAndWhitespaceAreNull() {
        assertNull(ChatSearchIndex.matchExpression("")); assertNull(ChatSearchIndex.matchExpression("   \n ")); assertNull(ChatSearchIndex.matchExpression("\"\"\""))
    }
    @Test fun match_apostrophesSurvive() { assertEquals("\"don't\"*", ChatSearchIndex.matchExpression("don't")) }

    // MARK: - Sidecar placement

    @Test fun sidecar_isNeverCreatedForAnInMemoryStore() { assertNull(ChatSearchIndex.sidecarFile(File("/tmp/whatever/teemoon-chat.db"), isStoredInMemoryOnly = true)) }
    @Test fun sidecar_sitsBesideTheStoreNotInsideIt() {
        val store = File("/tmp/whatever/teemoon-chat.db"); val sidecar = ChatSearchIndex.sidecarFile(store, false)!!
        assertEquals(store.parentFile, sidecar.parentFile); assertNotEquals(store.name, sidecar.name)
    }

    // MARK: - Round trip

    @Test fun search_findsAnIndexedMessageAndHighlightsTheMatch() = runTest {
        val (index, _) = makeIndex(); index.index(listOf(record("the appliance repair was expensive")))
        val hits = index.search("appliance", MESSAGES, 10)
        assertEquals(1, hits.size); assertTrue(hits[0].snippet.contains(OPEN)); assertTrue(hits[0].snippet.contains("appliance"))
    }

    @Test fun search_prefixMatchesAsYouType() = runTest {
        val (index, _) = makeIndex(); index.index(listOf(record("the appliances are delivered")))
        assertEquals(1, index.search("applianc", MESSAGES, 10).size)
    }

    @Test fun search_emptyQueryReturnsNothingWithoutError() = runTest {
        val (index, _) = makeIndex(); index.index(listOf(record("anything at all")))
        assertTrue(index.search("", MESSAGES, 10).isEmpty())
    }

    @Test fun search_operatorTextDoesNotThrow() = runTest {
        val (index, _) = makeIndex(); index.index(listOf(record("I prefer c++ to rust")))
        index.search("c++", MESSAGES, 10)
    }

    @Test fun search_preservesProvenance() = runTest {
        val (index, _) = makeIndex(); val one = record("provenance check", thread = "t", role = "assistant", atMs = now)
        index.index(listOf(one))
        val hit = index.search("provenance", MESSAGES, 10).first()
        assertEquals(one.messageID, hit.messageID); assertEquals("t", hit.threadID); assertEquals("assistant", hit.role); assertEquals(now, hit.timestampMs)
    }

    // MARK: - Granularity

    @Test fun granularity_threadsCollapsesToOneRowPerConversation() = runTest {
        val (index, _) = makeIndex()
        index.index(listOf(record("kettle one", "t"), record("kettle two", "t"), record("kettle three", "t")))
        assertEquals(1, index.search("kettle", THREADS, 10).size); assertEquals(3, index.search("kettle", MESSAGES, 10).size)
    }

    @Test fun granularity_threadsRanksAcrossConversations() = runTest {
        val (index, _) = makeIndex()
        index.index(listOf(record("kettle", "a"), record("a long message that mentions kettle exactly once among many other words here", "b")))
        val threads = index.search("kettle", THREADS, 10)
        assertEquals(2, threads.size); assertEquals(setOf("a", "b"), threads.map { it.threadID }.toSet())
    }

    // MARK: - Deletes

    @Test fun remove_byThreadDropsEveryRowForThatThread() = runTest {
        val (index, _) = makeIndex()
        index.index(listOf(record("shared term", "doomed"), record("shared term", "doomed"), record("shared term", "kept")))
        index.removeThread("doomed")
        val hits = index.search("shared", MESSAGES, 10)
        assertEquals(1, hits.size); assertEquals("kept", hits[0].threadID); assertEquals(1, index.indexedMessageIDs().size)
    }

    @Test fun remove_byMessageLeavesTheRestOfTheThread() = runTest {
        val (index, _) = makeIndex(); val doomed = record("truncated turn", "t")
        index.index(listOf(doomed, record("surviving turn", "t")))
        index.remove(listOf(doomed.messageID))
        assertTrue(index.search("truncated", MESSAGES, 10).isEmpty()); assertEquals(1, index.search("surviving", MESSAGES, 10).size)
    }

    @Test fun removeAll_emptiesBothTables() = runTest {
        val (index, _) = makeIndex(); index.index(listOf(record("one"), record("two"), record("three")))
        index.removeAll()
        assertTrue(index.indexedMessageIDs().isEmpty()); assertTrue(index.search("one", MESSAGES, 10).isEmpty())
    }

    /** A streaming message re-indexed as it grows must not accumulate duplicates. */
    @Test fun index_isIdempotentForTheSameMessageID() = runTest {
        val (index, _) = makeIndex(); val original = record("first wording")
        index.index(listOf(original)); index.index(listOf(original.copy(content = "second wording")))
        assertEquals(1, index.indexedMessageIDs().size)
        assertTrue(index.search("first", MESSAGES, 10).isEmpty()); assertEquals(1, index.search("second", MESSAGES, 10).size)
    }

    // MARK: - Delimiters

    /** Still findable: the delimiters became separators, not glue. Deleting them outright welded "before/middle/after" into one unsearchable term. */
    @Test fun delimiters_cannotArriveFromMessageText() = runTest {
        val (index, _) = makeIndex()
        index.index(listOf(record("before${OPEN}middle${SearchTestSupport.CLOSE}after kettle")))
        val hits = index.search("middle", MESSAGES, 10)
        assertEquals(1, hits.size)
        assertEquals(1, hits.first().snippet.count { it.toString() == OPEN })   // exactly the one pair snippet() added
    }

    @Test fun sanitizeForStorage_keepsWordsApart() { assertEquals("a b c", ChatSearchIndex.sanitizeForStorage("a${OPEN}b${SearchTestSupport.CLOSE}c")) }
    @Test fun sanitizeForStorage_leavesOrdinaryTextAlone() { val t = "perfectly normal — with an em dash and 中文"; assertEquals(t, ChatSearchIndex.sanitizeForStorage(t)) }

    // MARK: - Persistence

    @Test fun index_survivesReopening() = runTest {
        val (index, path) = makeIndex(); index.index(listOf(record("persisted across handles")))
        assertEquals(1, SearchTestSupport.index(path).search("persisted", MESSAGES, 10).size)
    }

    /** NEVER let a failed step read as "no matches". */
    @Test fun aBrokenIndexThrowsRatherThanAnsweringEmpty() = runTest {
        val path = file(); val index = SearchTestSupport.index(path)
        index.index(listOf(record("seed")))
        JdbcSearchSqlDatabase.on(path) { it.exec("DROP TABLE messages") }
        val fresh = ChatSearchIndex(databaseFile = path, open = { JdbcSearchSqlDatabase(path).also { it.exec("DROP TABLE IF EXISTS messages; CREATE TABLE messages(x)") } })
        assertFalse(runCatching { fresh.search("seed", MESSAGES, 10) }.isSuccess)
    }
}
