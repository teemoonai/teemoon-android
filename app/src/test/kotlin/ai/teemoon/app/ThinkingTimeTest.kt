// One clock for "thinking… Ns" and "thought for Ns" (the maintainer 2026-10-25, via pm: "Both"). The disclosure read
// Message.generatingTime, which is null until the turn ends — "thinking... 0s" while the activity chip beside it
// counted 1s, 2s — then "thought for 0s" when the answer began, and "thought for 5s" (the WHOLE turn, answer included)
// at the hand-off. Now the thinking time is stamped when the answer begins: turn start → `</think>`, the same start
// the chip counts from, and that is what the row keeps and the store saves.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThinkingTimeTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain() }

    private class InMemoryChatDao : ChatDao {
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

    private var clock = 1_770_000_000_000L
    private var vm: ChatViewModel? = null
    private val seen = ArrayList<Long?>()

    /** 2 s of reasoning, then 3 s of answer. */
    private inner class Reasoner : GenerationTransport {
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            onPartial("<think>weigh it")
            seen += vm!!.state.value.messages.last().generatingTimeMs
            clock += 2_000
            onPartial("<think>weigh it</think> They")
            seen += vm!!.state.value.messages.last().generatingTimeMs
            clock += 3_000
            onPartial("<think>weigh it</think> They weigh the same.")
            seen += vm!!.state.value.messages.last().generatingTimeMs
            return TurnResult(content = "<think>weigh it</think> They weigh the same.")
        }
    }

    @Test fun theThinkingTimeIsStampedWhenTheAnswerBeginsAndKept() = runTest(dispatcher) {
        val dao = InMemoryChatDao()
        vm = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks }, now = { clock }, makeTransport = { _, _, _, _, _, _ -> Reasoner() })
        vm!!.send("which is heavier?"); advanceUntilIdle()
        assertNull("still thinking: no figure yet (the row ticks from the turn clock)", seen[0])
        assertEquals("the answer began: 2 s of thinking", 2_000L, seen[1])
        assertEquals("and it holds while the answer streams", 2_000L, seen[2])
        assertEquals("the settled row keeps it, not the whole turn", 2_000L, vm!!.state.value.messages.last().generatingTimeMs)
        assertEquals("and the store saves it", 2_000L, dao.messages("t").last { it.role == "assistant" }.generatingTimeMs)
    }

    @Test fun aReplyWithoutThinkingKeepsTheWholeTurn() = runTest(dispatcher) {
        val plain = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                onPartial("Hi"); clock += 4_000; onPartial("Hi there."); return TurnResult(content = "Hi there.")
            }
        }
        vm = ChatViewModel(provider = { Provider.fireworks }, now = { clock }, makeTransport = { _, _, _, _, _, _ -> plain })
        vm!!.send("hello"); advanceUntilIdle()
        assertEquals(4_000L, vm!!.state.value.messages.last().generatingTimeMs)
    }

    @Test fun theHeaderSaysWhatTheChipSaysWhileThinking() {
        for (ms in listOf(null, 0L, 400L, 1_000L, 2_900L, 75_000L))
            assertEquals(activityLabel(ActivityState.Thinking, ms?.let { it / 1000.0 }), thinkingHeader(isThinking = true, elapsedMs = ms, thoughtMs = null))
        assertEquals("thought for 2s", thinkingHeader(isThinking = false, elapsedMs = 9_000, thoughtMs = 2_000))
    }
}
