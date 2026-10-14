// ports: teemoon/App/UITestStoreSeeding.swift @ v1.0.4 (seedFixtureThreadsIfRequested; seedLongThread is UiTestSeedingTest's
//        neighbour in UiTestSeeding.kt, seedShapedThreads is not ported — see ports.json)
package ai.teemoon.app

import ai.teemoon.chat.ChatDao
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.ThreadPreview
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design fixtures exist so a capture of the chat screen is comparable across days. That makes DETERMINISM the
 * property under test — not the prose, which is free to change with the design it is there to show off.
 */
class UiTestFixtureThreadsTest {
    private class Dao : ChatDao {
        val threads = mutableListOf<ThreadEntity>(); val messages = mutableListOf<MessageEntity>()
        override fun observeThreads() = flowOf(threads.toList())
        override fun observeThreadsWithMessages() = flowOf(threads.filter { t -> messages.any { it.threadId == t.id } })
        override fun observeMessages(threadId: String) = flowOf(messages.filter { it.threadId == threadId })
        override suspend fun messages(threadId: String) = messages.filter { it.threadId == threadId }
        override suspend fun upsertThread(thread: ThreadEntity) { threads.removeAll { it.id == thread.id }; threads += thread }
        override suspend fun upsertMessage(message: MessageEntity) { messages += message }
        override suspend fun updateContent(id: String, content: String) {}
        override suspend fun setTitle(id: String, title: String) { threads.replaceAll { if (it.id == id) it.copy(title = title) else it } }
        override suspend fun deleteThread(id: String) { threads.removeAll { it.id == id } }
        override suspend fun deleteAllThreads() { threads.clear() }
        override suspend fun deleteAllMessages() { messages.clear() }
        override suspend fun deleteMessagesSince(threadId: String, sinceMs: Long) { messages.removeAll { it.threadId == threadId && it.timestamp >= sinceMs } }
        override fun observePreviews() = flowOf(emptyList<ThreadPreview>())
        override suspend fun threadIdsWithContent(needle: String) = emptyList<String>()
        override suspend fun indexableMessageIds() = emptyList<String>()
        override suspend fun messagesByIds(ids: List<String>) = emptyList<MessageEntity>()
        override suspend fun startThread(thread: ThreadEntity, first: MessageEntity) {}
    }

    private suspend fun seeded(): Dao = Dao().also { UiTestSeeding.seedFixtureThreads(ChatRepository(it)) }

    @Test fun fourThreadsWithBothSidesOfAnExchange() = runTest {
        val dao = seeded()
        assertEquals(4, dao.threads.size)
        assertEquals(8, dao.messages.size)
        for (t in dao.threads) {
            val roles = dao.messages.filter { it.threadId == t.id }.sortedBy { it.timestamp }.map { it.role }
            assertEquals("${t.id} is not a user/assistant exchange", listOf("user", "assistant"), roles)
        }
    }

    /**
     * THE point of the fixtures: two runs are byte-identical. Fixed text and a fixed base date, so a capture diff
     * shows design drift rather than today's date. Ids included — a random id would land the threads in a different
     * order and a list screenshot would differ for no design reason.
     */
    @Test fun twoRunsProduceIdenticalData() = runTest {
        fun Dao.fingerprint() = threads.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.title}:${it.timestamp}" } +
            "//" + messages.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.role}:${it.timestamp}:${it.content.hashCode()}" }
        assertEquals(seeded().fingerprint(), seeded().fingerprint())
    }

    /** No `Date()` anywhere near this: every timestamp is derived from the fixed base, so none of them is "now". */
    @Test fun noTimestampIsWallClock() = runTest {
        val dao = seeded()
        val base = 1_770_000_000_000L
        for (m in dao.messages) assertTrue("${m.id} is not anchored to the base date", m.timestamp in (base - 10 * 86_400_000L)..(base + 86_400_000L))
    }

    /** The threads span days, so the chat list renders more than one date bucket — the thing a list capture is for. */
    @Test fun theThreadsSpanSeveralDays() = runTest {
        val days = seeded().threads.map { it.timestamp / 86_400_000L }.toSet()
        assertTrue("all four fixtures landed on one day: $days", days.size >= 3)
    }

    /**
     * One opening line is deliberately long, because the thread title is derived from it and an untruncated title
     * is only visibly wrong past a certain length. If every fixture were short the title layout would never be shown.
     */
    @Test fun oneOpeningLineIsLongEnoughToTestTheTitle() = runTest {
        val openings = seeded().messages.filter { it.role == "user" }.map { it.content }
        assertTrue("no fixture exercises a long title: ${openings.map { it.length }}", openings.any { it.length > 120 })
    }

    /** At least one reply wraps to several paragraphs — a transcript of one-liners does not look like a transcript. */
    @Test fun oneReplyHasSeveralParagraphs() = runTest {
        assertTrue(seeded().messages.any { it.role == "assistant" && it.content.split("\n\n").size >= 3 })
    }

    /** Idempotent: relaunching a seeded device must not stack a second set of fixtures on the first. */
    @Test fun seedingTwiceLeavesOneSet() = runTest {
        val dao = Dao(); val repo = ChatRepository(dao)
        UiTestSeeding.seedFixtureThreads(repo)
        val again = UiTestSeeding.seedFixtureThreads(repo)
        assertEquals(4, dao.threads.size)
        assertTrue(again.contains("present"))
    }
}
