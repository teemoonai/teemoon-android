// ports: teemoon/Views/Chat/ChatView.swift @ v1.0.4 (the toolbar and the chip actions), teemoon/Views/Chat/E2EETitleBlock.swift
//        @ v1.0.1 (one button, the trust ladder), teemoon/Views/Chat/MessageView.swift @ v1.0.4 (the context menus, the
//        thinking disclosure), teemoon/Views/Chat/WebSearchChip.swift @ v1.0.1 — the interaction contract of the chat
//        screen, one test per control: what a tap opens, and what it must NOT open.
package ai.teemoon.app

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every control on the chat screen, and what it does — read off the Swift, not off the Android code. A control that
 * opens the wrong thing (the title block opened the where sheet from its title and the trust ladder from its caption,
 * 2026-09-09) is a divergence no screenshot comparison sees, because both screens look right.
 */
@RunWith(AndroidJUnit4::class)
class ChatControlsContractTest {
    @get:Rule val compose = emptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var server: FakeSseServer? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun stop() { scenario?.close(); server?.close() }

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun exists(label: String) = compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun tap(label: String) { compose.waitUntil(10_000) { exists(label) }; compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label))[0].performClick(); compose.waitForIdle(); Thread.sleep(700) }
    private fun tapTag(tag: String) { compose.waitUntil(10_000) { tagExists(tag) }; compose.onAllNodesWithTag(tag)[0].performClick(); compose.waitForIdle(); Thread.sleep(700) }
    /** [slow]: a reply that streams for ~30 s, so a test can act mid-stream — the default 3 lines at 4 ms is over in under a second. */
    private fun launch(thinking: Boolean = false, braveKey: Boolean = false, slow: Boolean = false, failStatus: Int? = null, developer: Boolean = false, lines: Int = 3) {
        // slow = a word every 150 ms (~20 s): faster, the stream's main-thread recompositions leave the v1 Compose rule
        // no idle frame to sync on mid-stream, and the stop control "never appears" to a lookup
        server = if (slow) FakeSseServer(lines = 12, delayMs = 150, thinking = thinking) else FakeSseServer(lines = lines, delayMs = 4, thinking = thinking, failStatus = failStatus)
        val keys = File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }
        File(keys, ".BRAVE_API_KEY").apply { delete(); if (braveKey) writeText("uitest-brave-key") }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server!!.baseUrl); putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            if (braveKey) putExtra(UiTestSeeding.EXTRA_GROUNDING, true) else putExtra(UiTestSeeding.EXTRA_NO_BRAVE_KEY, true)   // the device may hold a key from an earlier seed
            if (developer) putExtra(UiTestSeeding.EXTRA_DEVELOPER, true)
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }; Thread.sleep(2_500)
    }
    private fun sendAndWait(text: String) {
        tapTag("chat.composer"); compose.onAllNodesWithTag("chat.composer")[0].performTextInput(text); Thread.sleep(300); tapTag("chat.send")
        compose.waitUntil(30_000) { exists("Paragraph 2:") && !tagExists("chat.stop") }; Thread.sleep(1_500)
    }

    /** ToolbarItem(.topBarLeading) list.bullet → the chats list. */
    @Test fun theChatsButtonOpensTheChatsList() { launch(); tap("chats"); compose.waitUntil(5_000) { exists("search") && exists("close") }; assertTrue("the where sheet opened", !exists("ready now")) }

    /** E2EETitleBlock: one button → the trust ladder, in every state; never the where sheet. */
    @Test fun theTitleBlockOpensTheTrustLadder() { launch(); tapTag("chat.titleBlock"); compose.waitUntil(5_000) { exists("who can read this?") }; assertTrue("the where sheet opened", !exists("ready now")) }

    /** ToolbarItem(.topBarTrailing) gear → settings. */
    @Test fun theGearOpensSettings() { launch(); tap("settings"); compose.waitUntil(5_000) { exists("appearance") }; assertTrue("the chat is still in front", !tagExists("chat.send") || exists("appearance")) }

    /** WhereChip's action → the where sheet, with the selected place marked. */
    @Test fun theWhereChipOpensTheWhereSheet() { launch(); tapTag("chat.whereChip"); compose.waitUntil(5_000) { exists("ready now") && exists("selected") } }

    /** ChatView's chip Menu (iOS 835d7c8): a long press offers "model info" and "copy model id"; model info opens the card. */
    @Test fun theWhereChipLongPressOffersTheModelCard() {
        launch()
        compose.onAllNodesWithTag("chat.whereChip")[0].performTouchInput { longClick() }
        compose.waitUntil(5_000) { exists("model info") && exists("copy model id") }
        tap("model info")
        compose.waitUntil(10_000) { exists("what it costs") }
        assertTrue("the card names the model's id", exists("fake-model"))
    }

    /** WebSearchChip with no Brave key → the web search settings page (ChatView: `if settings.braveSearchKey.isEmpty { showSettingsSearch = true }`). */
    @Test fun theWebChipWithoutAKeyOpensWebSearchSettings() { launch(braveKey = false); tapTag("chat.webSearchChip"); compose.waitUntil(5_000) { exists("web search") }; assertTrue("it stayed on the chat instead", exists("web search")) }

    /** WebSearchChip with a key → toggles grounding in place; nothing opens. */
    @Test fun theWebChipWithAKeyTogglesInPlace() { launch(braveKey = true); tapTag("chat.webSearchChip"); Thread.sleep(800); assertTrue("something opened over the chat", tagExists("chat.send") && !exists("web search") && !exists("ready now")) }

    /** The user bubble's context menu: copy. */
    @Test fun theUserBubbleLongPressOffersCopy() { launch(); sendAndWait("hello there"); compose.onAllNodes(hasText("hello there"))[0].performTouchInput { longClick() }; compose.waitUntil(5_000) { exists("copy") } }

    /** MessageView's thinking disclosure: collapsed after the turn ("thought for"), a tap opens the reasoning, a tap closes it. */
    @Test fun theThinkingDisclosureToggles() {
        launch(thinking = true); sendAndWait("why")
        compose.waitUntil(5_000) { exists("thought for") }
        assertTrue("the reasoning is open after the turn", !exists("Reasoning: weigh"))
        tap("thought for"); compose.waitUntil(5_000) { exists("Reasoning: weigh") }
        tap("thought for"); compose.waitUntil(5_000) { !exists("Reasoning: weigh") }
    }

    /**
     * A failed turn: the error card stands directly under the prompt — the 36dp a reply gets, not a blank reply row's
     * worth more — and the settle pins it into view (ConversationView.items: the error is the last item; the follow
     * lands the end). The Brave 422 card on the Pixel sat a screen-height under its prompt, unscrolled (2026-09-17).
     */
    @Test fun aFailedTurnShowsTheErrorCardRightUnderThePrompt() {
        launch(failStatus = 500)
        tapTag("chat.composer"); compose.onAllNodesWithTag("chat.composer")[0].performTextInput("hello there"); Thread.sleep(300); tapTag("chat.send")
        compose.waitUntil(30_000) { exists("HTTP 500") && !tagExists("chat.stop") }; Thread.sleep(2_000)
        val card = compose.onAllNodes(hasText("HTTP 500", substring = true))[0]
        card.assertIsDisplayed()
        val prompt = compose.onAllNodes(hasText("hello there", substring = true))[0]
        // Text node to header node, so the bubble's and the card's own insets are inside the number: measured 54dp
        // with the placeholder row gone, 90dp with it left behind (Pixel 10 Pro, 2026-09-17).
        val gapDp = (card.getBoundsInRoot().top - prompt.getBoundsInRoot().bottom).value
        assertTrue("the error card sits ${gapDp}dp under the prompt; 54 with no blank row between them", gapDp in 40.0..64.0)
    }

    /**
     * Developer mode: the request card stands 32dp under the reply's last line (iOS pads the reply cell and the debug
     * cell 16 each), and scrolled to the end its bottom lands on the chrome — no band beneath it. The band was the anchor
     * reserve, measured from the reply row alone and never re-read once the card appeared under it (Pixel, 2026-09-17).
     */
    @Test fun theDebugCardStandsUnderTheReplyWithNoBandBeneathIt() {
        // ONE line of reply, as on the phone: a short reply leaves the turn-end reserve large, which is what the
        // opened card then has to be measured against. Three paragraphs already fill the band and hide the bug.
        launch(developer = true, lines = 1)
        tapTag("chat.composer"); compose.onAllNodesWithTag("chat.composer")[0].performTextInput("hello there"); Thread.sleep(300); tapTag("chat.send")
        compose.waitUntil(30_000) { exists("Paragraph 0:") && !tagExists("chat.stop") }; Thread.sleep(1_500)
        compose.waitUntil(10_000) { tagExists("chat.debugCard") }; Thread.sleep(1_500)
        val gapAbove = (compose.onAllNodesWithTag("chat.debugCard")[0].getBoundsInRoot().top - compose.onAllNodes(hasText("Paragraph 0:", substring = true))[0].getBoundsInRoot().bottom).value
        assertTrue("the card sits ${gapAbove}dp under the reply's last line; iOS gives it 32", gapAbove in 24.0..44.0)
        // Open the card the way it was on the phone: the request body and the response — ink past the viewport now,
        // so the reserve owes nothing. (A collapsed card under a three-line reply still leaves the geometry's reserve,
        // as iOS does; the bug was a reserve that never shrank once the ink grew.)
        for (row in listOf("request body", "response")) { compose.onAllNodes(hasText(row))[0].performClick(); compose.waitForIdle(); Thread.sleep(400) }
        repeat(4) { compose.onAllNodesWithTag("chat.transcript")[0].performTouchInput { swipeUp() }; Thread.sleep(400) }
        Thread.sleep(1_500)
        val band = (compose.onAllNodesWithTag("chat.whereChip")[0].getBoundsInRoot().top - compose.onAllNodesWithTag("chat.debugCard")[0].getBoundsInRoot().bottom).value
        assertTrue("a ${band}dp band stands under the opened debug card at the end of the transcript", band in -8.0..48.0)
    }

    /** The stop button ends the turn quietly: no error card, and the next send works. (The bug: "StandaloneCoroutine was cancelled" as a card.) */
    @Test fun stopMidStreamShowsNoErrorCardAndTheNextSendWorks() {
        launch(slow = true); tapTag("chat.composer"); compose.onAllNodesWithTag("chat.composer")[0].performTextInput("a long one"); Thread.sleep(300); tapTag("chat.send")
        compose.waitUntil(10_000) { tagExists("chat.stop") }; Thread.sleep(2_000)   // streaming: the stop control is up and text is arriving
        tapTag("chat.stop"); Thread.sleep(800)
        assertTrue("a stop raised an error card", !exists("unexpected error") && !exists("cancelled"))
        sendAndWait("and again"); assertTrue("the next turn did not answer", exists("Paragraph 2:"))
        assertTrue("an error card appeared after the retry", !exists("unexpected error"))
    }
}
