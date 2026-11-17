package ai.teemoon.app

// ports: teemoonUITests/ProviderWaitUITests.swift @ v1.0.6
//
// A provider that sends no response headers for a while: the chip says the request is waiting on it, on the first
// request and on a search turn's follow-up — and not before 5 s.

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderWaitJourneyTest {
    @get:Rule(order = 0) val compose = emptyComposeRule()
    @get:Rule(order = 1) val pageSizeDialog = PageSizeDialogRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun launch(server: FakeSseServer, grounding: Boolean) {
        if (grounding) File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }.let { File(it, ".BRAVE_API_KEY").apply { delete() }.writeText("uitest-brave-key") }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl)
            putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            if (grounding) {
                putExtra(UiTestSeeding.EXTRA_GROUNDING, true)
                putExtra(UiTestSeeding.EXTRA_BRAVE_ENDPOINT, "${server.baseUrl}/llm/context")
            }
        }
        scenario?.close(); scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }
        Thread.sleep(2_500)   // the seed runs off the main thread
    }

    private fun send(prompt: String) {
        compose.onAllNodesWithTag("chat.composer")[0].performClick(); Thread.sleep(600)
        compose.onAllNodesWithTag("chat.composer")[0].performTextInput(prompt); Thread.sleep(300)
        compose.onAllNodesWithTag("chat.send")[0].performClick()
    }

    @Test fun theChipNamesTheProviderAfterFiveSecondsWithoutHeaders() { FakeSseServer(lines = 6, delayMs = 6, headerDelayMs = 9_000).use { server ->
        launch(server, grounding = false)
        send("Why is the sky blue?")
        Thread.sleep(2_500)
        assertFalse("shown before the threshold", exists("waiting for"))
        compose.waitUntil(5_000) { exists("waiting for") }
        // the headers arrive at 9 s: the chip falls back, and the turn ends
        compose.waitUntil(30_000) { exists("Paragraph 1") }
        compose.waitUntil(20_000) { !tagExists("chat.stop") }
        assertFalse(exists("waiting for"))
    } }

    @Test fun aSearchTurnsFollowUpWaitsToo() { FakeSseServer(toolRound = true, delayMs = 6, headerDelayMs = 7_000).use { server ->
        launch(server, grounding = true)
        send("what is the weather in paris")
        // the first request's wait shows, then clears at its headers; the follow-up starts its own clock
        compose.waitUntil(15_000) { exists("looking that up.") }
        compose.waitUntil(15_000) { exists("waiting for") }
        assertTrue("the follow-up must still be held for this to test anything", !exists("Paragraph 1"))
        compose.waitUntil(30_000) { exists("Paragraph 1") }
        compose.waitUntil(20_000) { !tagExists("chat.stop") }
    } }
}
