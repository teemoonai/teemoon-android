// ports: teemoonTests/ChatSearchReconcilerTests.swift @ v1.0.1 (the SwiftData container is a fake ChatSearchSource
//        with the same orphaning semantics; the Room source is exercised on a device)
package ai.teemoon.chat.search

import ai.teemoon.chat.search.ChatSearchGranularity.MESSAGES
import ai.teemoon.chat.search.SearchTestSupport.record
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** A store with threads and messages; deleting a thread nullifies its messages (iOS) — they stay, unsearchable. */
class FakeStore : ChatSearchSource {
    val threads = HashSet<String>()
    val messages = LinkedHashMap<String, ChatMessageRecord>()   // threadID "" = orphan
    fun seedThread(vararg texts: String): String {
        val t = UUID.randomUUID().toString(); threads.add(t)
        for (x in texts) record(x, thread = t).let { messages[it.messageID] = it }
        return t
    }
    fun deleteThread(t: String) { threads.remove(t); for ((k, v) in messages) if (v.threadID == t) messages[k] = v.copy(threadID = "") }
    override suspend fun indexableMessageIDs(): Set<String> = messages.values.filter { it.threadID in threads }.map { it.messageID }.toSet()
    override suspend fun records(ids: Set<String>): List<ChatMessageRecord> = ids.mapNotNull { messages[it] }.filter { it.threadID in threads }
}

/** The reconciler is what makes hooks optional: it must converge from empty, from stale, and from over-full, and never index an orphan. */
class ChatSearchReconcilerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun makeIndex() = SearchTestSupport.index(File(tmp.root, "reconcile-${UUID.randomUUID()}.sqlite"))

    @Test fun reconcile_fromEmptyIndexesEverything() = runTest {
        val store = FakeStore(); store.seedThread("alpha kettle", "beta kettle", "gamma"); val index = makeIndex()
        val stats = ChatSearchReconciler(index).reconcile(store)
        assertEquals(3, stats.inserted); assertEquals(0, stats.removed); assertEquals(2, index.search("kettle", MESSAGES, 10).size)
    }

    @Test fun reconcile_isIdempotent() = runTest {
        val store = FakeStore(); store.seedThread("alpha", "beta"); val r = ChatSearchReconciler(makeIndex())
        r.reconcile(store); val second = r.reconcile(store)
        assertTrue(second.isConverged); assertEquals(2, second.unchanged)   // a launch that changed nothing must do nothing
    }

    /** An index left over-full — the shape a missed delete hook produces. */
    @Test fun reconcile_dropsRowsThatAreNoLongerInTheStore() = runTest {
        val index = makeIndex(); val r = ChatSearchReconciler(index)
        index.index(listOf(record("ghost message"), record("another ghost")))
        val store = FakeStore(); store.seedThread("real message")
        val stats = r.reconcile(store)
        assertEquals(2, stats.removed); assertEquals(1, stats.inserted)
        assertTrue(index.search("ghost", MESSAGES, 10).isEmpty()); assertEquals(1, index.search("real", MESSAGES, 10).size)
    }

    @Test fun reconcile_neverIndexesOrphanedMessages() = runTest {
        val store = FakeStore(); val doomed = store.seedThread("orphan kettle"); store.seedThread("kept kettle"); store.deleteThread(doomed)
        val index = makeIndex()
        val stats = ChatSearchReconciler(index).reconcile(store)
        assertEquals(1, stats.inserted)
        val hits = index.search("kettle", MESSAGES, 10)
        assertEquals(1, hits.size); assertFalse(hits[0].snippet.contains("orphan"))
    }

    /** The delete hook never ran — a crash, an external path, an older build. */
    @Test fun reconcile_removesRowsForAThreadDeletedSinceLastLaunch() = runTest {
        val store = FakeStore(); val doomed = store.seedThread("first", "second"); val index = makeIndex(); val r = ChatSearchReconciler(index)
        r.reconcile(store); assertEquals(2, index.indexedMessageIDs().size)
        store.deleteThread(doomed)
        val stats = r.reconcile(store)
        assertEquals(2, stats.removed); assertTrue(index.indexedMessageIDs().isEmpty())
    }

    /** Identity-only diffing is the deliberate trade: it cannot see an edit to an existing message. The hook path (upsert) is what covers streaming. */
    @Test fun reconcile_leavesEditedContentAloneByDesign() = runTest {
        val store = FakeStore(); store.seedThread("original wording"); val index = makeIndex(); val r = ChatSearchReconciler(index)
        r.reconcile(store)
        val id = store.messages.keys.first(); store.messages[id] = store.messages.getValue(id).copy(content = "revised wording")
        val stats = r.reconcile(store)
        assertTrue(stats.isConverged); assertTrue(index.search("revised", MESSAGES, 10).isEmpty())
    }
}
