// A4 (pm sweep 2026-11-06): deleting a chat while it is answering. Its view model's job was never cancelled, so the
// reply's save hit the thread's CASCADE foreign key in a bare launch — an uncaught throw, an app crash on the phone.
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
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThreadDeletedMidTurnTest {
    private val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    @org.junit.Before fun up() { kotlinx.coroutines.Dispatchers.setMain(dispatcher) }
    @org.junit.After fun down() { kotlinx.coroutines.Dispatchers.resetMain() }

    /** A turn whose read outlives a stop, then answers: the reply's save comes after the delete. */
    private class Late : ai.teemoon.inference.GenerationTransport {
        val release = CompletableDeferred<Unit>()
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<ai.teemoon.inference.Tool>, onPartial: (String) -> Unit): ai.teemoon.inference.TurnResult {
            onPartial("half"); withContext(NonCancellable) { release.await() }; onPartial("the whole answer")
            return ai.teemoon.inference.TurnResult(content = "the whole answer")
        }
    }

    @org.junit.Test fun deletingAChatMidTurnStopsItAndWritesNothingToIt() = kotlinx.coroutines.test.runTest(dispatcher) {
        val dao = FakeChatDao(); dao.threads += ThreadEntity(id = "t", title = null, timestamp = 0)
        val transport = Late()
        val vm = ChatViewModel(repo = ChatRepository(dao), threadId = "t", provider = { Provider.fireworks }, makeTransport = { _, _, _, _, _, _ -> transport })
        vm.send("a question"); advanceUntilIdle()
        assertTrue(vm.state.value.isStreaming)
        // the chat list's delete: the open view model is told first, then the store deletes the thread
        vm.threadDeleted(); ChatRepository(dao).delete("t")
        transport.release.complete(Unit); advanceUntilIdle()
        assertFalse(vm.state.value.isStreaming)
        assertTrue("nothing written to a deleted chat", dao.messages.none { it.threadId == "t" })
    }
}
