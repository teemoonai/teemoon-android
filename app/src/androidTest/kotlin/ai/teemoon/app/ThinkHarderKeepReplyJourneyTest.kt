package ai.teemoon.app

// iOS 14b2974 (the maintainer, 2026-10-21, "think-harder-keep-reply"), on a device: the old reply stays on screen under the
// "thinking harder..." chip until the new one has text, then the new one takes its place; a stop puts nothing in its
// place and leaves the old reply. The JVM suite (ThinkHarderKeepReplyTest) pins the store; this pins what is SEEN.

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThinkHarderKeepReplyJourneyTest {
    @get:Rule(order = 0) val compose = emptyComposeRule()
    @get:Rule(order = 1) val pageSizeDialog = PageSizeDialogRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var server: FakeSseServer? = null

    @After fun tearDown() { scenario?.close(); server?.close() }

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun texts(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().map { n ->
        "${n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString()?.take(40)}@${n.boundsInRoot.top}" }
    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    /** Every reply's first token comes [firstTokenDelayMs] after the request: the window the old reply waits, dimmed. */
    private fun launch(firstTokenDelayMs: Long) {
        server = FakeSseServer(lines = 3, delayMs = 6, firstTokenDelayMs = firstTokenDelayMs)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server!!.baseUrl)
            putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }
        Thread.sleep(2_500)   // the seed runs off the main thread
    }

    private fun askAndWait(prompt: String) {
        compose.onAllNodesWithTag("chat.composer")[0].performClick(); Thread.sleep(600)
        compose.onAllNodesWithTag("chat.composer")[0].performTextInput(prompt); Thread.sleep(300)
        compose.onAllNodesWithTag("chat.send")[0].performClick()
        compose.waitUntil(30_000) { exists("Paragraph 1") }
        compose.waitUntil(20_000) { !tagExists("chat.stop") }
    }

    private fun thinkHarder() {
        val replies = compose.onAllNodes(SemanticsMatcher("a reply") { n -> n.config.contains(SemanticsActions.CustomActions) })
        replies[replies.fetchSemanticsNodes().size - 1].performTouchInput { longClick() }
        compose.waitUntil(5_000) { exists(ReplyMenu.THINK_HARDER) }
        compose.onNodeWithText(ReplyMenu.THINK_HARDER).performClick()
    }

    @Test fun theOldReplyStaysUnderTheChipUntilTheNewOneHasText() {
        launch(firstTokenDelayMs = 5_000)
        askAndWait("why is the sky blue")
        val questionNodes = count("why is the sky blue")   // the bubble and the chat's title
        thinkHarder()
        compose.waitUntil(4_000) { exists("thinking harder") }
        assertTrue("the old reply is still on screen", exists("Paragraph 1"))
        // the new reply's text arrives at 5 s: it takes the old one's place, once
        compose.waitUntil(30_000) { exists("Paragraph 2") }
        compose.waitUntil(20_000) { !tagExists("chat.stop") }
        Thread.sleep(1_000)
        assertFalse(exists("thinking harder"))
        assertEquals("the reply was replaced, not added to: ${texts("Paragraph 1")}", 1, count("Paragraph 1"))
        assertEquals("the question is not asked twice: ${texts("why is the sky blue")}", questionNodes, count("why is the sky blue"))
    }

    @Test fun aStoppedThinkHarderKeepsTheOldReply() {
        launch(firstTokenDelayMs = 8_000)
        askAndWait("why is the sky blue")
        thinkHarder()
        compose.waitUntil(4_000) { exists("thinking harder") && tagExists("chat.stop") }
        compose.onAllNodesWithTag("chat.stop")[0].performClick()
        compose.waitUntil(5_000) { !exists("thinking harder") }
        Thread.sleep(500)
        assertEquals("the old reply, as it was", 1, count("Paragraph 1"))
        assertFalse("no turn left running", tagExists("chat.stop"))
    }
}
