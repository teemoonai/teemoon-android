// ports: teemoonTests/ThinkHarderKeepReplyTests.swift @ 14b2974, over the view model and ChatRepository rather than
//        SwiftData. + android-only: a stop after the new reply showed text (the partial goes, the old reply comes back),
//        and the restored reply keeps its own generating time.
//
// the maintainer, 2026-10-21 ("think-harder-keep-reply"): the old reply stays, dimmed, until the new one shows text; a failed,
// stopped or interrupted Think Harder leaves it as it was. Build 50 deleted and saved it before asking, so a failure
// lost the answer.
package ai.teemoon.app

import ai.teemoon.chat.ChatDao
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.ThreadPreview
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ThinkHarderKeepReplyTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain() }

    private class Transport(val mode: Mode) : GenerationTransport {
        enum class Mode { ANSWER, FAIL, STALL, PARTIAL_THEN_STALL, INTERRUPT, DEAF_TO_STOP }
        val sent = ArrayList<List<Map<String, Any>>>()
        val partialShown = CompletableDeferred<Unit>()
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            sent += messages
            return when (mode) {
                Mode.ANSWER -> { onPartial("NEW ANSWER"); TurnResult(content = "NEW ANSWER") }
                Mode.FAIL -> throw IOException("timed out")
                Mode.STALL -> awaitCancellation()
                Mode.PARTIAL_THEN_STALL -> { onPartial("NEW AN"); partialShown.complete(Unit); awaitCancellation() }
                Mode.INTERRUPT -> throw CancellationException("killed")
                // A read that outlives the stop (the 3917.6 disk gate): the reply still arrives, after it.
                Mode.DEAF_TO_STOP -> withContext(NonCancellable) { delay(5_000); onPartial("LATE ANSWER"); TurnResult(content = "LATE ANSWER") }
            }
        }
    }

    private class Dao : ChatDao {
        override suspend fun hasMessages(threadId: String): Boolean = messages(threadId).isNotEmpty()
        val threads = mutableListOf<ThreadEntity>(); val messages = mutableListOf<MessageEntity>()
        override fun observeThreads() = flowOf(threads.toList())
        override fun observeThreadsWithMessages() = flowOf(threads.filter { t -> messages.any { it.threadId == t.id } })
        override fun observeMessages(threadId: String) = flowOf(messages.filter { it.threadId == threadId })
        override suspend fun messages(threadId: String) = messages.filter { it.threadId == threadId }.sortedBy { it.timestamp }
        override suspend fun upsertThread(thread: ThreadEntity) { threads.removeAll { it.id == thread.id }; threads += thread }
        override suspend fun upsertMessage(message: MessageEntity) { messages.removeAll { it.id == message.id }; messages += message }
        override suspend fun updateContent(id: String, content: String) {}
        override suspend fun setTitle(id: String, title: String) {}
        override suspend fun deleteThread(id: String) { threads.removeAll { it.id == id } }
        override suspend fun deleteAllThreads() { threads.clear() }
        override suspend fun deleteAllMessages() { messages.clear() }
        override suspend fun deleteMessagesSince(threadId: String, sinceMs: Long) { messages.removeAll { it.threadId == threadId && it.timestamp >= sinceMs } }
        override suspend fun deleteMessages(ids: List<String>) { messages.removeAll { it.id in ids } }
        override suspend fun rewriteSentBeforeConfirmed(from: String, to: String?): Int { var n = 0; messages.replaceAll { if (it.sentBeforeConfirmed == from) { n++; it.copy(sentBeforeConfirmed = to) } else it }; return n }
        override suspend fun setTimestamp(id: String, timestamp: Long) { messages.replaceAll { if (it.id == id) it.copy(timestamp = timestamp) else it } }
        override fun observePreviews() = flowOf(emptyList<ThreadPreview>())
        override suspend fun threadIdsWithContent(needle: String) = emptyList<String>()
        override suspend fun indexableMessageIds() = emptyList<String>()
        override suspend fun messagesByIds(ids: List<String>) = messages.filter { it.id in ids }
        override suspend fun startThread(thread: ThreadEntity, first: MessageEntity) {}
        override suspend fun setTitleIfNone(id: String, title: String) {}
        override suspend fun insertAttachments(attachments: List<ai.teemoon.chat.AttachmentEntity>) {}
        override suspend fun attachmentsFor(messageIds: List<String>) = emptyList<ai.teemoon.chat.AttachmentEntity>()
        override fun observeAttachments(threadId: String) = kotlinx.coroutines.flow.flowOf(emptyList<ai.teemoon.chat.AttachmentEntity>())
        override suspend fun attachmentPaths() = emptyList<String>()
        override suspend fun threadExists(id: String) = threads.any { it.id == id }
        override suspend fun insertThread(thread: ThreadEntity) { threads += thread }
        override suspend fun insertMessages(messages: List<MessageEntity>) { this.messages += messages }
    }

    private val askedAt = 1_770_000_000_000L
    private var clock = askedAt + 3_600_000

    private inner class Rig(val transport: Transport) {
        val dao = Dao()
        val vm = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks },
            systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT }, now = { clock }, makeTransport = { _, _, _, _, _, _ -> transport })
        init {
            dao.threads += ThreadEntity(id = "t", title = "why is the sky blue", timestamp = askedAt)
            dao.messages += MessageEntity(id = "q", threadId = "t", role = "user", content = "why is the sky blue", timestamp = askedAt)
            dao.messages += MessageEntity(id = "old", threadId = "t", role = "assistant", content = "OLD ANSWER", timestamp = askedAt + 5_000, generatingTimeMs = 5_000)
            vm.restore(dao.messages.toList())
        }
        fun thinkHarder() = vm.thinkHarder("old")
        val contents get() = vm.state.value.messages.map { it.text }
        suspend fun stored() = dao.messages("t").map { it.content }
        suspend fun storedQuestionTime() = dao.messages("t").first { it.id == "q" }.timestamp
    }

    @Test fun aNewReplyReplacesTheOldOneInOneSave() = runTest(dispatcher) {
        val rig = Rig(Transport(Transport.Mode.ANSWER))
        rig.thinkHarder()
        assertTrue("dimmed until the new reply has text", rig.vm.state.value.superseding.isDimmed("old"))
        advanceUntilIdle()
        assertEquals(listOf("why is the sky blue", "NEW ANSWER"), rig.contents)
        assertEquals(listOf("why is the sky blue", "NEW ANSWER"), rig.stored())
        assertEquals("restamped when the new reply lands", clock, rig.storedQuestionTime())
        assertEquals(clock, rig.vm.state.value.messages.first().createdAtMs)
        assertFalse(rig.vm.state.value.superseding.isActive)
        assertFalse(rig.vm.generation.superseding.isActive)
        val wire = rig.transport.sent.first().mapNotNull { it["content"] as? String }
        assertTrue("the model is never sent the reply it replaces", wire.none { it.contains("OLD ANSWER") })
        assertEquals("nor the question twice", 1, wire.count { it.startsWith("why is the sky blue") })
    }

    @Test fun aFailureKeepsTheOldReply() = runTest(dispatcher) {
        val rig = Rig(Transport(Transport.Mode.FAIL))
        rig.thinkHarder(); advanceUntilIdle()
        assertNotNull(rig.vm.state.value.lastError)
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.contents)
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.stored())
        assertEquals(askedAt, rig.storedQuestionTime())
        assertEquals(askedAt, rig.vm.state.value.messages.first().createdAtMs)
        assertFalse("not left dimmed", rig.vm.state.value.superseding.isActive)
        assertEquals("the old reply keeps its own time", 5_000L, rig.vm.state.value.messages.last().generatingTimeMs)
    }

    @Test fun aStopKeepsTheOldReply() = runTest(dispatcher) {
        val rig = Rig(Transport(Transport.Mode.STALL))
        rig.thinkHarder(); runCurrent()
        rig.vm.stop(); advanceUntilIdle()
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.contents)
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.stored())
        assertFalse(rig.vm.state.value.superseding.isActive)
        assertFalse(rig.vm.state.value.isStreaming)
    }

    /** android-only: stopped after the swap — a stopped partial is not saved, and the old reply comes back. */
    @Test fun aStopAfterTheFirstTextPutsTheOldReplyBack() = runTest(dispatcher) {
        val transport = Transport(Transport.Mode.PARTIAL_THEN_STALL)
        val rig = Rig(transport)
        rig.thinkHarder(); runCurrent(); transport.partialShown.await(); runCurrent()
        assertTrue("swapped at the first text", rig.vm.state.value.superseding.swapped)
        assertEquals(listOf("why is the sky blue", "NEW AN"), rig.vm.state.value.superseding.visible(rig.vm.state.value.messages).map { it.text })
        rig.vm.stop(); advanceUntilIdle()
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.contents)
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.stored())
    }

    /** A reply that arrives after the stop writes nothing: the restored old reply is not overwritten, and nothing is saved. */
    @Test fun aReplyArrivingAfterTheStopIsDropped() = runTest(dispatcher) {
        val rig = Rig(Transport(Transport.Mode.DEAF_TO_STOP))
        rig.thinkHarder(); runCurrent()
        rig.vm.stop(); advanceUntilIdle()
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.contents)
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.stored())
    }

    /**
     * The same on a plain turn stopped before any text: the late reply used to land in the question's own bubble. Since
     * the reply machine (reply-machine.json: a stop keeps the text so far) the stopped turn keeps its own empty row, which
     * reads "you stopped this reply." — and the late reply lands in neither.
     */
    @Test fun aPlainTurnsLateReplyDoesNotOverwriteTheQuestion() = runTest(dispatcher) {
        val rig = Rig(Transport(Transport.Mode.DEAF_TO_STOP))
        clock += 1_000
        rig.vm.send("a second question"); runCurrent()
        rig.vm.stop(); advanceUntilIdle()
        assertEquals(listOf("why is the sky blue", "OLD ANSWER", "a second question", ""), rig.contents)
        assertEquals(listOf("why is the sky blue", "OLD ANSWER", "a second question", ""), rig.stored())
        assertEquals("marker.stopped", rig.vm.state.value.messages.last().marker)
        assertEquals("stopped", rig.dao.messages("t").last().status)
    }

    /** pm: a kill mid-Think-Harder must keep the old reply and must not re-ask at default effort. */
    @Test fun anInterruptionKeepsTheOldReplyAndIsNotReasked() = runTest(dispatcher) {
        val transport = Transport(Transport.Mode.INTERRUPT)
        val rig = Rig(transport)
        rig.thinkHarder(); advanceUntilIdle()
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), rig.contents)
        // The next launch reads the store: the question has its answer, so nothing is left to resume.
        assertEquals(listOf("user", "assistant"), rig.dao.messages("t").map { it.role })
        val relaunched = ChatViewModel(repo = ChatRepository(rig.dao), threadId = "t", provider = { Provider.fireworks }, now = { clock },
            makeTransport = { _, _, _, _, _, _ -> transport })
        relaunched.restore(rig.dao.messages("t")); advanceUntilIdle()
        assertEquals(listOf("why is the sky blue", "OLD ANSWER"), relaunched.state.value.messages.map { it.text })
        assertEquals("asked once, and never again", 1, transport.sent.size)
    }

    @Test fun theTranscriptDropsTheOldReplyAtTheFirstText() {
        val a = ChatMessage("user", "q", id = "a"); val b = ChatMessage("assistant", "old", id = "b")
        var s = ReplySupersession(setOf("b"))
        assertEquals(listOf("q", "old"), s.visible(listOf(a, b)).map { it.text })
        assertEquals(listOf("q"), s.history(listOf(a, b)).map { it.text })
        s = s.noteText("")
        assertTrue(s.isDimmed("b"))
        s = s.noteText("N")
        assertFalse(s.isDimmed("b"))
        assertEquals(listOf("q"), s.visible(listOf(a, b)).map { it.text })
        assertEquals(ReplySupersession.Outcome.Replace(setOf("b")), s.outcome(failed = false, cancelled = false, output = "N"))
        assertEquals(ReplySupersession.Outcome.Restore, s.outcome(failed = true, cancelled = false, output = "N"))
        assertEquals("a stopped partial is not saved", ReplySupersession.Outcome.Restore, s.outcome(failed = false, cancelled = true, output = "N"))
        assertEquals(null, ReplySupersession().outcome(failed = false, cancelled = false, output = "x"))
    }
}
