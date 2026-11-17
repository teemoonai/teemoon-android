// conformance/reply-machine.json (kit 0.6.0) wired into the chat: ReplyMachine is the only writer of a reply's status.
// A plain turn's row is written "interrupted" before the request goes out and rewritten once when it ends; a stop keeps
// the text so far; a failure keeps an empty row; the approved markers (reply-markers, the maintainer 2026-11-06) show in the
// session and, from the saved status, after a relaunch — a row still "interrupted" reads "didn't finish", never re-asked.
package ai.teemoon.app

import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.Provider
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReplyLifecycleWiringTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { kotlinx.coroutines.Dispatchers.setMain(dispatcher) }
    @After fun down() { kotlinx.coroutines.Dispatchers.resetMain() }

    /** Streams [partial], then waits for [release] (or forever), then answers [answer]; or throws [fail]. */
    private class Scripted(val partial: String = "", val answer: String = "the whole answer", val fail: Throwable? = null) : GenerationTransport {
        val release = CompletableDeferred<Unit>()
        val sent = ArrayList<List<Map<String, Any>>>()
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            sent += messages
            if (partial.isNotEmpty()) onPartial(partial)
            fail?.let { throw it }
            release.await()
            if (answer.isNotEmpty()) onPartial(answer)
            return TurnResult(content = answer)
        }
    }

    private fun setup(transport: Scripted): Pair<FakeChatDao, ChatViewModel> {
        val dao = FakeChatDao(); dao.threads += ThreadEntity(id = "t", title = null, timestamp = 0)
        val vm = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks }, makeTransport = { _, _, _, _, _, _ -> transport })
        return dao to vm
    }
    private fun FakeChatDao.reply() = messages.single { it.role == "assistant" }

    @Test fun aPlainTurnIsInterruptedUntilItEnds() = runTest(dispatcher) {
        val t = Scripted(); val (dao, vm) = setup(t)
        vm.send("a question"); advanceUntilIdle()
        assertEquals("written before the answer", "interrupted", dao.reply().status)
        assertEquals("", dao.reply().content)
        t.release.complete(Unit); advanceUntilIdle()
        assertEquals("complete", dao.reply().status)
        assertEquals("the whole answer", dao.reply().content)
        assertNull(vm.state.value.messages.last().marker)
    }

    @Test fun aStopKeepsTheTextSoFarAndSaysSo() = runTest(dispatcher) {
        val t = Scripted(partial = "half an"); val (dao, vm) = setup(t)
        vm.send("a question"); advanceUntilIdle()
        vm.stop(); advanceUntilIdle()
        assertEquals("stopped", dao.reply().status)
        assertEquals("half an", dao.reply().content)
        assertEquals("marker.stopped", vm.state.value.messages.last().marker)
        assertEquals("you stopped this reply.", ReplyMarkerCopy.text("marker.stopped"))
    }

    @Test fun aFailureKeepsAnEmptyRowThatReadsDidntFinish() = runTest(dispatcher) {
        val t = Scripted(fail = IOException("connection reset")); val (dao, vm) = setup(t)
        vm.send("a question"); advanceUntilIdle()
        assertEquals("failed", dao.reply().status)
        assertEquals("", dao.reply().content)
        // reopened: the saved status says what happened
        val reopened = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks })
        reopened.restore(dao.messages.sortedBy { it.timestamp })
        assertEquals("marker.didntFinish", reopened.state.value.messages.last().marker)
        assertEquals("this reply didn't finish.", ReplyMarkerCopy.text("marker.didntFinish"))
    }

    /** Launch recovery (the kit, ios asked for it exactly): an "interrupted" row reads didn't finish; nothing is sent or written. */
    @Test fun aRowStillInterruptedAtLaunchReadsDidntFinishAndIsNotReAsked() = runTest(dispatcher) {
        val t = Scripted(); val (dao, vm) = setup(t)
        val rows = listOf(MessageEntity("m-1", "t", "user", "a question", 1), MessageEntity("a-2", "t", "assistant", "", 2, status = "interrupted"))
        dao.messages += rows
        vm.restore(rows); advanceUntilIdle()
        assertEquals("marker.didntFinish", vm.state.value.messages.last().marker)
        assertTrue("re-asked on its own", t.sent.isEmpty())
        assertEquals("nothing written", rows, dao.messages.sortedBy { it.timestamp })
    }

    @Test fun anEmptyReplyIsAFailedRowAndNeverGoesOnTheWire() = runTest(dispatcher) {
        val first = Scripted(answer = ""); val (dao, vm) = setup(first)
        first.release.complete(Unit)
        vm.send("a question"); advanceUntilIdle()
        assertEquals("failed", dao.reply().status)
        val second = Scripted(); second.release.complete(Unit)
        val vm2 = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks }, makeTransport = { _, _, _, _, _, _ -> second })
        vm2.restore(dao.messages.sortedBy { it.timestamp })
        vm2.send("again"); advanceUntilIdle()
        val wire = second.sent.first()
        assertTrue("an empty reply went out on the wire", wire.none { it["role"] == "assistant" && (it["content"] as? String).isNullOrBlank() })
    }

    /** G4 (the kit's cut short): length, content_filter, the on-device cap, or EOF with neither [DONE] nor a finish_reason. */
    private class Ends(val result: TurnResult) : GenerationTransport {
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            onPartial(result.content); return result
        }
    }
    private fun statusAfter(result: TurnResult): Pair<String?, String?> = kotlinx.coroutines.test.runTest(dispatcher) {
        val dao = FakeChatDao(); dao.threads += ThreadEntity(id = "t", title = null, timestamp = 0)
        val vm = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks }, makeTransport = { _, _, _, _, _, _ -> Ends(result) })
        vm.send("a question"); advanceUntilIdle()
        statusSeen = dao.reply().status to vm.state.value.messages.last().marker
    }.let { statusSeen }
    private var statusSeen: Pair<String?, String?> = null to null

    @Test fun aReplyThatEndsEarlyIsCutShort() {
        assertEquals("cutOff" to "marker.cutShort", statusAfter(TurnResult(content = "the first three steps", finishReason = "length", wasTruncated = true)))
        assertEquals("cutOff" to "marker.cutShort", statusAfter(TurnResult(content = "the first three steps", finishReason = "content_filter")))
        assertEquals("on-device cap", "cutOff" to "marker.cutShort", statusAfter(TurnResult(content = "the first three steps", wasTruncated = true)))
        assertEquals("EOF without [DONE] or a finish", "cutOff" to "marker.cutShort", statusAfter(TurnResult(content = "the first three steps", endedWithoutDone = true)))
        assertEquals("finish(stop) without [DONE] is complete", "complete" to null, statusAfter(TurnResult(content = "a whole answer", finishReason = "stop", endedWithoutDone = true)))
        assertEquals("complete" to null, statusAfter(TurnResult(content = "a whole answer", finishReason = "stop")))
    }

    /** sent-before-confirmed-mark (approved words): an open chat's reply follows its record's settle — pass clears, a hard fail marks. */
    @Test fun aReplysSentMarkFollowsItsRecord() = runTest(dispatcher) {
        val (_, vm) = setup(Scripted())
        val key = "1730851200000|n0"
        vm.restore(listOf(MessageEntity("m-1", "t", "user", "q", 1), MessageEntity("a-2", "t", "assistant", "a", 2, status = "complete", sentBeforeConfirmed = "pending|$key"),
            MessageEntity("m-3", "t", "user", "q2", 3), MessageEntity("a-4", "t", "assistant", "b", 4, status = "complete", sentBeforeConfirmed = "pending|other")))
        vm.resolveSentMarks(key, ai.teemoon.chat.SentBeforeConfirmed.Outcome.HARD_FAIL)
        assertEquals(listOf(null, "failed|$key", null, "pending|other"), vm.state.value.messages.map { it.sentBeforeConfirmed })
        assertEquals("near.ai's hardware failed its check after this was sent. treat this reply as not private.", ReplyMarkerCopy.text("mark.checkFailedAfterSend"))
        assertEquals("sent while near.ai's hardware was still being checked", ReplyMarkerCopy.text("mark.sentWhileChecking"))
        vm.resolveSentMarks("other", ai.teemoon.chat.SentBeforeConfirmed.Outcome.PASS)
        assertNull("a pass clears the mark", vm.state.value.messages.last().sentBeforeConfirmed)
    }

    @Test fun theDecryptLineIsTheApprovedOne() {
        assertEquals("this reply was cut short.", ReplyMarkerCopy.text("marker.cutShort"))
        assertEquals("this reply couldn't be decrypted, so it isn't shown.", ReplyMarkerCopy.text("marker.decryptFailed"))
    }
}
