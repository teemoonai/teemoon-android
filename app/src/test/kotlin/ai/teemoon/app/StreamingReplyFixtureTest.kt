// Android-only (no Swift source): the streaming fixture :macrobenchmark's StreamingReplyBenchmark measures.
package ai.teemoon.app

import ai.teemoon.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingReplyFixtureTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain() }

    @Test fun theFixtureIsLongAndCarriesEveryBlockKind() {
        val md = StreamingReplyFixture.MARKDOWN
        assertTrue("${md.length} chars", md.length in 5_000..7_000)
        assertTrue(md.contains("\n## ")); assertTrue(md.contains("\n```kotlin\n")); assertTrue(md.contains("\n|---|")); assertTrue(md.contains("\n- ")); assertTrue(md.contains("\n1. "))
    }

    @Test fun partialsAreTokenSizedAndEndOnTheWholeText() {
        val ends = ScriptedReplyTransport.partialEnds(1_000)
        assertEquals(1_000, ends.last())
        ends.zipWithNext().forEach { (a, b) -> assertTrue(b - a in 1..6) }
        assertTrue(ScriptedReplyTransport.partialEnds(0).isEmpty())
    }

    @Test fun theSeedExtraIsReadOnlyBehindTheFlag() {
        assertTrue(UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_FLAG to true, UiTestSeeding.EXTRA_STREAM_REPLY to true))!!.streamReply)
        assertFalse(UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_FLAG to true))!!.streamReply)
    }

    /** Through the real view model and engine: the transcript ends holding the whole fixture, settled. */
    @Test fun theScriptedReplyStreamsThroughTheViewModel() = runTest(dispatcher) {
        val texts = mutableListOf<String>()
        val vm = ChatViewModel(provider = { Provider(id = "p", name = "local", endpoint = "http://127.0.0.1:9/v1/chat/completions", model = "m", requiresAPIKey = false) },
            makeTransport = { _, _, _, _, _, _ -> ScriptedReplyTransport(StreamingReplyFixture.MARKDOWN, intervalMs = 1) })
        val collect = backgroundScope.launch(dispatcher) { vm.state.collect { s -> s.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.let { texts += it.text } } }
        vm.send(StreamingReplyFixture.PROMPT); advanceUntilIdle()
        collect.cancel()
        val last = vm.state.value.messages.last()
        assertEquals(StreamingReplyFixture.MARKDOWN, last.text)
        assertFalse(last.streaming)
        assertTrue("only ${texts.size} partials seen", texts.size > 100)
    }
}
