// A3 (pm sweep 2026-11-06): an empty reply was saved. GenerationEngine always reports its final text, and nothing
// checked it was blank, so the store kept a blank assistant row: settle() hid it on screen, it came back on reopen,
// and it went out on the wire with the next turn.
package ai.teemoon.app

import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmptyReplyNotSavedTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain() }

    /**
     * A3 said "no row for no reply"; reply-markers (the maintainer approved, 2026-11-06) supersedes it: an empty reply is a
     * FAILED row — empty, marked "didn't finish" with retry on reopen — and it never goes on the wire
     * (ReplyLifecycleWiringTest.anEmptyReplyIsAFailedRowAndNeverGoesOnTheWire).
     */
    @Test fun anEmptyReplyIsAnEmptyFailedRow() = runTest(dispatcher) {
        for (empty in listOf("", "  \n ")) {
            val dao = FakeChatDao(); dao.threads += ThreadEntity(id = "t", title = null, timestamp = 0)
            val transport = object : GenerationTransport {
                override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit) = TurnResult(content = empty)
            }
            val vm = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks }, makeTransport = { _, _, _, _, _, _ -> transport })
            vm.send("a question"); advanceUntilIdle()
            val rows = dao.messages.filter { it.threadId == "t" }
            assertEquals("${empty.length}-char reply", listOf("user", "assistant"), rows.map { it.role })
            assertEquals("failed", rows.last().status); assertEquals("", rows.last().content)
        }
    }
}
