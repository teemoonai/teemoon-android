// ports: teemoonTests/ChatSearchServiceTests.swift @ v1.0.1 (Message/ModelContext become ChatMessageRecord + the
//        isPersisted flag; backup exclusion becomes the injected harden callback)
package ai.teemoon.chat.search

import ai.teemoon.chat.search.ChatSearchGranularity.MESSAGES
import ai.teemoon.chat.search.SearchTestSupport.record
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** Covers the mutation-site hooks and the fail-open contract. "Optimisation" does not mean "allowed to be wrong". */
class ChatSearchServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun path() = File(tmp.root, "service-${UUID.randomUUID()}.sqlite")

    @Test fun didWrite_makesAMessageFindable() = runTest {
        val index = SearchTestSupport.index(path()); val service = ChatSearchService(this, index)
        service.didWrite(record("the kettle broke", thread = "t")); service.drainPendingWork()
        assertEquals(1, index.search("kettle", MESSAGES, 10).size)
    }

    /** No thread means no way to open the result, so it must never be indexed. */
    @Test fun didWrite_ignoresAnOrphanedMessage() = runTest {
        val index = SearchTestSupport.index(path()); val service = ChatSearchService(this, index)
        service.didWrite(record("orphan kettle", thread = "")); service.drainPendingWork()
        assertTrue(index.indexedMessageIDs().isEmpty())
    }

    /** The detached shape: a turn built on a thread never inserted. "Spoken and discarded" must mean discarded. */
    @Test fun didWrite_ignoresADetachedMessage() = runTest {
        val index = SearchTestSupport.index(path()); val service = ChatSearchService(this, index)
        service.didWrite(record("detached kettle", thread = "never-inserted"), isPersisted = false); service.drainPendingWork()
        assertTrue(index.indexedMessageIDs().isEmpty())
    }

    /** The sqlite is created lazily by the first write, so a reharden BEFORE any write finds no file and no-ops. */
    @Test fun rehardenAfterFirstWrite_reachesTheSidecar() = runTest {
        val p = path(); val index = SearchTestSupport.index(p); val service = ChatSearchService(this, index)
        val hardened = ArrayList<File>()
        service.rehardenIfNeeded { hardened.add(it) }
        assertTrue(hardened.isEmpty())
        service.didWrite(record("hardened kettle", thread = "t")); service.drainPendingWork()
        service.rehardenIfNeeded { hardened.add(it) }
        assertEquals(listOf(p), hardened)
    }

    /** THE RACE THIS QUEUE EXISTS FOR: retry truncation writes a message and deletes it moments later. */
    @Test fun hooksApplyInTheOrderTheyWereCalled() = runTest {
        val index = SearchTestSupport.index(path()); val service = ChatSearchService(this, index)
        val m = record("retracted kettle answer", thread = "t", role = "assistant")
        service.didWrite(m); service.didDelete(listOf(m.messageID)); service.drainPendingWork()
        assertTrue(index.search("kettle", MESSAGES, 10).isEmpty()); assertTrue(index.indexedMessageIDs().isEmpty())
    }

    /**
     * A retried reply and a Think Harder's replaced one reach the index through didDelete: deleted text like a deleted
     * chat's, so its words leave the index's files, not wait in the WAL for a checkpoint (1.0.6 audit, developer note).
     */
    @Test fun didDelete_leavesTheTextInNoFile() = runTest {
        val p = path(); val index = SearchTestSupport.index(p); val service = ChatSearchService(this, index)
        service.didWrite(record("the kept question", thread = "t"))
        val dropped = record("zanzibarquokka was the replaced reply", thread = "t", role = "assistant")
        service.didWrite(dropped); service.drainPendingWork()
        service.didDelete(listOf(dropped.messageID)); service.drainPendingWork()
        assertTrue(index.search("zanzibarquokka", MESSAGES, 10).isEmpty())
        for (f in listOf(p, File(p.path + "-wal"))) if (f.exists())
            assertFalse("${f.name} still holds the deleted reply", String(f.readBytes(), Charsets.ISO_8859_1).contains("zanzibarquokka"))
        assertEquals(1, index.search("kept", MESSAGES, 10).size)
    }

    @Test fun didDeleteThread_dropsThatThreadOnly() = runTest {
        val index = SearchTestSupport.index(path()); val service = ChatSearchService(this, index)
        service.didWrite(record("kettle one", thread = "doomed")); service.didWrite(record("kettle two", thread = "kept"))
        service.didDeleteThread("doomed"); service.drainPendingWork()
        val hits = index.search("kettle", MESSAGES, 10)
        assertEquals(1, hits.size); assertEquals("kept", hits.first().threadID)
    }

    /** An index left behind here is a searchable plaintext copy of everything the user just asked to be deleted. */
    @Test fun didDeleteAllChats_leavesNothingSearchable() = runTest {
        val index = SearchTestSupport.index(path()); val service = ChatSearchService(this, index)
        for (t in listOf("one kettle", "two kettle", "three kettle")) service.didWrite(record(t, thread = "t"))
        service.didDeleteAllChats(); service.drainPendingWork()
        assertTrue(index.indexedMessageIDs().isEmpty()); assertTrue(index.search("kettle", MESSAGES, 10).isEmpty())
    }

    // MARK: - Fail open

    /** null means "fall back to the in-memory filter", NOT "no matches". */
    @Test fun search_returnsNullWithoutAnIndex() = runTest { assertNull(ChatSearchService(this).search("kettle")) }

    /** A half-built index must not answer: it would report a confidently empty history while the launch reconcile is still running. */
    @Test fun search_returnsNullBeforeTheFirstReconcile() = runTest {
        val service = ChatSearchService(this)
        service.configure(File(tmp.root, "teemoon-chat.db"), isStoredInMemoryOnly = false) { }
        assertNotNull(service.index); assertNull(service.search("kettle"))
    }

    @Test fun configure_inMemoryStoreGetsNoIndex() = runTest {
        val service = ChatSearchService(this); var hardened = false
        service.configure(File(tmp.root, "teemoon-chat.db"), isStoredInMemoryOnly = true) { hardened = true }
        assertNull(service.index); assertFalse(service.isReady); assertFalse(hardened)
    }

    /** The in-memory path: every hook must be inert, not crash. */
    @Test fun hooksAreNoOpsWithoutAnIndex() = runTest {
        val service = ChatSearchService(this)
        service.didWrite(record("x")); service.didDelete(listOf("id")); service.didDeleteThread("t"); service.didDeleteAllChats(); service.drainPendingWork()
        assertNull(service.index)
    }

    // MARK: - Reconcile

    @Test fun reconcile_marksTheServiceReadyAndAnswersAfterwards() = runTest {
        val service = ChatSearchService(this, SearchTestSupport.index(path()))
        val store = FakeStore(); store.seedThread("reconciled kettle")
        service.reconcile(store)
        assertTrue(service.isReady); assertEquals(1, service.search("kettle", MESSAGES, 10)?.size)
    }

    /** A reconcile that fails is logged and swallowed — never thrown into the launch path — and a service that was not yet ready stays that way. */
    @Test fun reconcile_failureIsLoggedNotThrown() = runTest {
        val broken = object : ChatSearchSource {
            override suspend fun indexableMessageIDs(): Set<String> = throw IllegalStateException("store unavailable")
            override suspend fun records(ids: Set<String>) = emptyList<ChatMessageRecord>()
        }
        val logged = ArrayList<String>()
        val service = ChatSearchService(this, SearchTestSupport.index(path()), log = { logged.add(it) })
        service.reconcile(broken)
        assertTrue(logged.any { it.contains("reconcile failed") })
    }

    /**
     * A8 (pm sweep 2026-11-06): reconcile ran outside the hook queue. A delete that landed while it was reading the
     * store applied first, then reconcile wrote back the snapshot it had read — the deleted message stayed searchable
     * until the next launch. Reconcile now takes its turn in the queue, so the delete applies after it.
     */
    @Test fun aDeleteDuringReconcileStaysDeleted() = runTest {
        val index = SearchTestSupport.index(path()); val service = ChatSearchService(this, index)
        val m = record("deleted mid-reconcile kettle", thread = "t")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : ChatSearchSource {
            override suspend fun indexableMessageIDs() = setOf(m.messageID)
            override suspend fun records(ids: Set<String>): List<ChatMessageRecord> { gate.await(); return listOf(m) }
        }
        val reconciling = launch { service.reconcile(source) }
        testScheduler.runCurrent()
        service.didDelete(listOf(m.messageID))
        gate.complete(Unit); reconciling.join(); service.drainPendingWork()
        assertTrue("a deleted message must not come back from reconcile's snapshot", index.indexedMessageIDs().isEmpty())
    }

    /** A8: the queue's tail had no lock — two threads enqueuing at once chained off the same job and ran together. */
    @Test fun hooksFromManyThreadsRunOneAtATime() = kotlinx.coroutines.runBlocking {
        val real = SearchTestSupport.index(path())
        val running = java.util.concurrent.atomic.AtomicInteger(); val most = java.util.concurrent.atomic.AtomicInteger()
        val counted = object : ChatSearchIndexing by real {
            override suspend fun index(records: List<ChatMessageRecord>) {
                most.accumulateAndGet(running.incrementAndGet(), ::maxOf); Thread.sleep(1); real.index(records); running.decrementAndGet()
            }
        }
        val service = ChatSearchService(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), counted)
        val threads = (0 until 8).map { t -> Thread { repeat(25) { n -> service.didWrite(record("kettle $t $n", thread = "t")) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        repeat(50) { service.drainPendingWork() }
        assertEquals("hooks overlapped", 1, most.get())
    }
}
