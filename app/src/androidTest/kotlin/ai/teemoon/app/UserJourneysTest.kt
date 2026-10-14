package ai.teemoon.app

// android-only: the everyday paths, walked on a device the way a thumb does, over the fake wire — connect a computer
// and chat on it, a chat that persists and reopens and can be deleted, the send gate on a degraded attestation, and
// switching the place from the chip. Added 2026-09-08 with GroundedTurnTest, after a failing search that no test had
// touched: a ported policy with a JVM suite is not a tested feature.

import androidx.compose.ui.semantics.getOrNull
import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.flow.first
import ai.teemoon.chat.ChatDatabase
import ai.teemoon.chat.ChatRepository
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelDownloader
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereProviderPresentation
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isToggleable
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/**
 * The seeded near.ai preset's row, as the where sheet labels it. DERIVED, not typed: these journeys named "glm-5.2"
 * and broke when the preset's default moved off that retired model (b760889) — nothing on the JVM could see it.
 */
private val NEAR_AI_ROW = WhereProviderPresentation.modelLabel(Provider.nearAI)

@RunWith(AndroidJUnit4::class)
class UserJourneysTest {
    @get:Rule(order = 0) val compose = emptyComposeRule()
    @get:Rule(order = 3) val pageSizeDialog = PageSizeDialogRule()
    /** What the user was looking at when a journey failed: a screenshot under files/journeys and the semantics tree in logcat (JOURNEY). */
    /** Step goldens (Goldens.kt): the journey fails at its end if what the user saw at any step drifted from the recorded baseline. */
    @get:Rule(order = 2) val goldens = GoldenRule()
    private fun golden(name: String, settleMs: Long = 800) { compose.waitForIdle(); Thread.sleep(settleMs); goldens.capture(name) }
    @get:Rule(order = 1) val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("JOURNEY") }
            runCatching {
                val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "journeys").apply { mkdirs() }
                File(dir, description.methodName + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: FakeSseServer
    private var scenario: ActivityScenario<MainActivity>? = null
    @Before fun start() { server = FakeSseServer() }
    // The scenario is NOT closed here: @After runs before the failure watcher, and the watcher needs the screen. The next
    // launch closes it, and the process ends with the class.
    @After fun stop() {
        server.close()
        // Put back any host key file a journey replaced with a fake: a staged REAL key (live_cloud_suite.sh, or a
        // hand-run capture) survived as "uitest-near-key" and every later near.ai attestation 401'd at the gateway —
        // an empty gateway card that read as an app defect for an afternoon (2026-09-24).
        replacedKeys.forEach { (f, original) -> if (original == null) f.delete() else f.writeText(original) }
        replacedKeys.clear()
    }
    private val replacedKeys = LinkedHashMap<File, String?>()

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun exists(label: String) = compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun tap(label: String, index: Int = 0) {
        compose.waitUntil(20_000) { exists(label) }
        // Scrolled into view first, as a thumb would: a sheet at its medium detent lays rows out below the fold, and a
        // click at an off-screen row's spot lands on the scrim and closes the sheet (H1's teal, F4's delete all).
        compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label))[index].let { n -> runCatching { n.performScrollTo() }; n.performClick() }
        compose.waitForIdle(); Thread.sleep(600)
    }
    private fun tapTag(tag: String) { compose.waitUntil(20_000) { tagExists(tag) }; compose.onAllNodesWithTag(tag)[0].let { runCatching { it.performScrollTo() }; it.performClick() }; compose.waitForIdle(); Thread.sleep(600) }
    private fun field(index: Int) = compose.onAllNodes(hasSetTextAction())[index]
    /** The sheet's fields sit after the chat's composer in the tree: the LAST [count] set-text nodes are the form's. */
    private fun sheetField(indexFromEnd: Int): SemanticsNodeInteraction { val n = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size; return compose.onAllNodes(hasSetTextAction())[n - 1 - indexFromEnd] }
    private fun keyFile(name: String, value: String) {
        val f = File(File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }, name)
        if (f !in replacedKeys) replacedKeys[f] = f.takeIf { it.isFile }?.readText()
        f.delete(); f.writeText(value)
    }
    private fun launch(vararg extras: Pair<String, Any>) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)   // every journey starts from an empty store, as a fresh install would
            putExtra(UiTestSeeding.EXTRA_AVAILABLE_MB, 8_000)   // and on a phone with room for the recommended model
            extras.forEach { (k, v) -> when (v) { is String -> putExtra(k, v); is Boolean -> putExtra(k, v); is Int -> putExtra(k, v) } }
        }
        scenario?.close(); scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }; Thread.sleep(2_500)
    }
    private fun send(text: String) { tapTag("chat.composer"); field(0).performTextInput(text); Thread.sleep(300); tapTag("chat.send") }
    private fun waitForReply() = compose.waitUntil(60_000) { exists("Paragraph 5:") }

    /** Nothing configured. From the where sheet: connect a computer, fetch its models, pick one, save — and the next message goes to it. */
    @Test fun connectAComputerFromWhereAndChatOnIt() {
        launch()
        tapTag("chat.whereChip"); golden("connect.where-sheet"); tap("connect a computer")
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3 }   // composer + label + host
        sheetField(1).performTextInput("loop")
        sheetField(0).performTextInput("127.0.0.1:1/v1"); Thread.sleep(500)   // the chip already says http://
        golden("connect.form-filled")   // before the real port goes in: the golden must not carry a port that changes per run
        sheetField(0).performTextClearance(); sheetField(0).performTextInput(server.baseUrl.removePrefix("http://")); Thread.sleep(500)
        tap("fetch models")
        compose.waitUntil(30_000) { exists("fake-model") }
        tap("fake-model"); tap("save")
        // From FIRST RUN a save ends the sheet, not just the push (iOS cf251b5): the user came to start chatting, and the
        // composer with the chip naming the new model is that. (From a populated sheet, save still returns to it.)
        compose.waitUntil(10_000) { !exists("save") && !exists("ready now") && !exists("start here") }
        golden("connect.chip-names-the-place")
        assertTrue("the chip does not name the new place's model", compose.onAllNodesWithTag("chat.whereChip").fetchSemanticsNodes().isNotEmpty() && exists("fake-model"))
        send("hello"); waitForReply(); golden("connect.reply", settleMs = 2_000)
    }

    /** A turn is persisted: a new chat leaves it, the chats list brings it back with its reply, and a swipe deletes it. */
    @Test fun aChatPersistsReopensAndCanBeDeleted() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        send("remember this one"); waitForReply(); golden("persist.reply", settleMs = 2_000)
        tap("chats"); golden("persist.chats"); tap("new chat")
        assertTrue("the new chat is not empty", !exists("Paragraph 5:"))
        tap("chats"); tap("remember this one")
        compose.waitUntil(10_000) { exists("Paragraph 5:") }
        tap("chats")
        // the row's long press asks (the swipe reveals the same "delete"; a synthetic fling under the test clock does not reliably cross the dismiss threshold)
        // the open chat's header carries the same title beneath the sheet; the list row is the LAST match
        val rows = compose.onAllNodes(hasText("remember this one", substring = true)).fetchSemanticsNodes().size
        compose.onAllNodes(hasText("remember this one", substring = true))[rows - 1].performTouchInput { longClick() }
        compose.waitUntil(10_000) { exists("delete this chat?") }; golden("persist.delete-ask")
        // the swipe's red background also says "delete"; the confirmation's action is the last match
        val deletes = compose.onAllNodes(hasText("delete")).fetchSemanticsNodes().size
        compose.onAllNodes(hasText("delete"))[deletes - 1].performClick(); compose.waitForIdle()
        // deleting the OPEN chat leaves a fresh one behind, the list dismissed (iOS ChatsListView.deleteThread)
        compose.waitUntil(10_000) { tagExists("chat.composer") && !exists("Paragraph 5:") }
        tap("chats"); Thread.sleep(800); golden("persist.chats-after-delete")
        assertTrue("the deleted chat is still listed", !exists("remember this one"))
    }

    /** An attesting place whose attestation is degraded: the first send ASKS, and cancel keeps the draft. */
    @Test fun theSendGateAsksWhenAttestationIsDegraded() {
        keyFile(".NEAR_AI_API_KEY", "uitest-near-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "nearai", UiTestSeeding.EXTRA_ATTESTATION to "degraded")
        tapTag("chat.composer"); field(0).performTextInput("hello"); Thread.sleep(300); tapTag("chat.send")
        compose.waitUntil(15_000) { exists("retry verification") }; golden("gate.ask")
        assertTrue("the gate offers no cancel", exists("cancel"))
        tap("cancel")
        assertTrue("cancel lost the draft", compose.onAllNodes(hasText("hello")).fetchSemanticsNodes().isNotEmpty())
    }

    /** The merged text of the where sheet's row for [preset] (`where.preset.<name>`), or null when the row is not on screen. */
    private fun presetRowText(preset: String): String? {
        val tag = hasTestTag("where.preset.$preset")
        if (compose.onAllNodes(tag).fetchSemanticsNodes().isEmpty()) return null
        // the tag sits on the row's column; its text is merged into the clickable row beneath it
        return compose.onAllNodes(hasAnyAncestor(tag)).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" ") { it.text }
    }

    /**
     * A4 (docs/USE_CASES.md). Add a cloud key from Where: cloud filter → the near.ai row ("add key") → paste → save. The
     * sheet comes back ON THE CLOUD FILTER, near.ai is ready, and its get row no longer says "add key". Reported on the
     * Pixel 2026-10-04 as "it returns to where → all and the near.ai row still shows add key"; this is that path.
     * Offline: the key is a stand-in and nothing here needs near.ai to answer (the count may not load; "add key" must go).
     */
    @Test fun addingACloudKeyFromWhereLeavesTheRowKeyedAndTheFilterKept() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        tapTag("chat.whereChip"); tap("cloud")
        compose.waitUntil(10_000) { presetRowText("near.ai") != null }
        assertTrue("near.ai should start keyless: ${presetRowText("near.ai")}", presetRowText("near.ai")!!.contains("add key"))
        tapTag("where.preset.near.ai")
        compose.waitUntil(10_000) { exists("add near.ai key") }
        sheetField(0).performTextInput("uitest-near-key"); Thread.sleep(400)
        tap("save")
        compose.waitUntil(15_000) { !exists("add near.ai key") }
        Thread.sleep(1_500)
        assertTrue("the sheet did not come back on the cloud filter", exists("runs on someone else's hardware"))
        val row = presetRowText("near.ai")
        assertTrue("near.ai's get row still says \"add key\" after its key was saved: $row", row != null && !row.contains("add key"))
        assertTrue("near.ai is not in ready now", exists("near.ai · end-to-end encrypted"))
        // and again from a closed sheet: the state is the store's, not the screen's memory of the save
        tap("close"); tapTag("chat.whereChip"); tap("cloud")
        compose.waitUntil(10_000) { presetRowText("near.ai") != null }
        assertTrue("reopened, near.ai's row says \"add key\" again: ${presetRowText("near.ai")}", !presetRowText("near.ai")!!.contains("add key"))
    }

    /**
     * A4 from the ALL filter, through the same row: save returns to the sheet, and the row reads keyed there too.
     */
    @Test fun addingACloudKeyFromTheAllFilterLeavesTheRowKeyed() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        tapTag("chat.whereChip")
        compose.waitUntil(10_000) { presetRowText("near.ai") != null }
        tapTag("where.preset.near.ai")
        compose.waitUntil(10_000) { exists("add near.ai key") }
        sheetField(0).performTextInput("uitest-near-key"); Thread.sleep(400)
        tap("save")
        compose.waitUntil(15_000) { !exists("add near.ai key") }
        Thread.sleep(1_500)
        val row = presetRowText("near.ai")
        assertTrue("near.ai's get row still says \"add key\" after its key was saved: $row", row != null && !row.contains("add key"))
    }

    private val appState get() = (ctx.applicationContext as TeemoonApplication).state!!
    /** Runs [block] and puts the user's settings back after, whatever it changed: on a phone they are the owner's. */
    private fun <T> keepingSettings(block: () -> T): T {
        val s = appState.settings
        val prompt = s.systemPrompt; val width = s.appFontWidth; val tint = s.appTintColor; val size = s.appFontSize; val design = s.appFontDesign
        try { return block() } finally { s.systemPrompt = prompt; s.appFontWidth = width; s.appTintColor = tint; s.appFontSize = size; s.appFontDesign = design }
    }
    private fun countOf(label: String) = compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().size
    /** The LAST match: under a sheet the chat's own "chats" button is still in the tree, ahead of the sheet's row. */
    private fun tapLast(label: String) { compose.waitUntil(20_000) { exists(label) }; tap(label, index = compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label)).fetchSemanticsNodes().size - 1) }

    /** B1: send, reply, send again — the second message gets its own reply in the same thread, both on the wire as one conversation. */
    @Test fun aSecondSendGetsASecondReplyInTheSameThread() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        send("first question"); waitForReply()
        compose.waitUntil(30_000) { !tagExists("chat.stop") }
        send("second question")
        compose.waitUntil(60_000) { countOf("Paragraph 5:") >= 2 && !tagExists("chat.stop") }
        assertTrue("the second question is not in the transcript", exists("second question"))
        assertEquals("two completions should have gone out", 2, server.bodies.size)
        assertTrue("the second request did not carry the first turn: the thread was not one conversation", server.bodies[1].contains("first question"))
    }

    /**
     * B9: a place that answers one question at a time (maxMessages 1 — Brave Answers' preset) is sent ONLY the new
     * question, and the transcript says so above every turn after the first rather than implying it saw the thread.
     */
    @Test fun aSingleTurnPlaceStartsFreshEachMessage() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model", UiTestSeeding.EXTRA_LOCAL_MAX_MESSAGES to 1)
        send("first question"); waitForReply()
        compose.waitUntil(30_000) { !tagExists("chat.stop") }
        assertFalse("the first turn is marked as a fresh start", exists(FreshStart.LABEL))
        send("second question")
        compose.waitUntil(60_000) { countOf("Paragraph 5:") >= 2 && !tagExists("chat.stop") }
        assertEquals("two completions should have gone out", 2, server.bodies.size)
        assertTrue("the second request lost its own question", server.bodies[1].contains("second question"))
        assertFalse("a single-turn place was sent the thread", server.bodies[1].contains("first question"))
        assertTrue("the second turn does not say it starts fresh", exists(FreshStart.LABEL))
    }

    /** B3: a failed turn (HTTP 500) shows its error card under the prompt; retry resends, and the reply lands in its place. */
    @Test fun aFailedTurnRetriesAndTheReplyLands() {
        server.close(); server = FakeSseServer(failFirst = 1)
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        send("try me")
        compose.waitUntil(30_000) { exists("HTTP 500") && !tagExists("chat.stop") }
        tapTag("chat.retry")
        waitForReply()
        compose.waitUntil(30_000) { !tagExists("chat.stop") }
        assertFalse("the error card stayed after a successful retry", exists("HTTP 500"))
        assertEquals("the prompt is shown twice: retry must resend it, not add another", 1, compose.onAllNodes(hasText("try me")).fetchSemanticsNodes().count { it.boundsInRoot.height > 0 }.coerceAtMost(1))
        assertEquals(2, server.bodies.size)
    }

    /** B8: the system prompt set in settings → chats is what the next turn sends, with {{datetime}} filled in. */
    @Test fun theSystemPromptFromSettingsReachesTheWire() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        keepingSettings {
        tap("settings"); tapLast("chats")
        compose.waitUntil(10_000) { exists("use {{datetime}}") }
        val promptField = sheetField(0)
        promptField.performTextClearance(); promptField.performTextInput("speak like a lighthouse keeper. now: {{datetime}}"); Thread.sleep(500)
        tap("back"); tap(A11yLabels.CLOSE_SETTINGS)
        send("hello keeper"); waitForReply()
        val body = server.bodies.last()
        assertTrue("the system prompt from settings did not reach the wire: ${body.take(300)}", body.contains("speak like a lighthouse keeper"))
        assertFalse("{{datetime}} went out unexpanded", body.contains("{{datetime}}"))
        }
    }

    /** F3: search the chats for a word from a reply → the hit → that thread opens; a word nowhere says "no results". */
    @Test fun searchingTheChatsOpensTheThreadWithTheHit() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        send("where is the lighthouse"); waitForReply()
        compose.waitUntil(30_000) { !tagExists("chat.stop") }
        tap("chats"); tap("new chat")
        tap("chats")
        val search = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
        compose.onAllNodes(hasSetTextAction())[search].performTextInput("zzqx-nowhere"); Thread.sleep(1_200)
        compose.waitUntil(10_000) { exists("no results") }
        compose.onAllNodes(hasSetTextAction())[search].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[search].performTextInput("lighthouse"); Thread.sleep(1_200)
        // the hits are laid out under the keyboard at the sheet's medium detent: put the keyboard away, as a reader would
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(800)
        compose.waitUntil(10_000) { exists("where is the lighthouse") }
        val rows = countOf("where is the lighthouse")
        compose.onAllNodes(hasText("where is the lighthouse", substring = true))[rows - 1].performClick(); Thread.sleep(1_500)
        compose.waitUntil(10_000) { exists("Paragraph 5:") }
    }

    /** F4: settings → chats → delete all chats, confirmed → the chats list is empty. */
    @Test fun deletingAllChatsFromSettingsEmptiesTheList() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        send("one to delete"); waitForReply()
        compose.waitUntil(30_000) { !tagExists("chat.stop") }
        tap("settings"); compose.waitUntil(5_000) { exists("appearance") }
        tapLast("chats"); compose.waitUntil(5_000) { exists("delete all chats") }
        // below the medium detent's fold: scrolled to, as a thumb would — a click at its off-screen spot lands on the scrim and closes the sheet
        compose.onAllNodes(hasText("delete all chats"))[0].performScrollTo().performClick(); Thread.sleep(600)
        compose.waitUntil(5_000) { exists("are you sure?") }
        tap("delete", index = countOf("delete") - 1)
        compose.waitUntil(5_000) { !exists("are you sure?") }
        tap("back"); tap(A11yLabels.CLOSE_SETTINGS)
        // the open chat goes with them (ChatsSettingsView: currentThread = nil): no transcript of a deleted thread behind the list
        assertFalse("the deleted chat is still drawn in the transcript", exists("Paragraph 0:"))
        tap("chats"); Thread.sleep(800)
        assertTrue("the chats list is not empty after delete all", exists("no chats yet"))
    }

    /** H1: appearance — tint and width chosen in settings stick, and survive the app being relaunched. */
    @Test fun appearanceChoicesStickAcrossARelaunch() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        keepingSettings {
        tap("settings"); tap("appearance")
        tap("width"); tap("condensed")
        compose.waitUntil(5_000) { exists("condensed") }
        assertEquals(AppFontWidth.CONDENSED, appState.settings.appFontWidth)
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        tap("settings"); tap("appearance")
        compose.waitUntil(5_000) { exists("condensed") }
        assertEquals("the width did not survive a relaunch", AppFontWidth.CONDENSED, appState.settings.appFontWidth)
        }
    }

    private fun local(vararg more: Pair<String, Any>) = arrayOf<Pair<String, Any>>(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model", *more)
    private fun back() { InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); compose.waitForIdle(); Thread.sleep(800) }
    private fun failing(status: Int) { server.close(); server = FakeSseServer(failStatus = status, failFirst = 1) }

    /** B4: a 401 earns "manage api key at <vendor>", a 402 "add credits at <vendor>" — the vendor named from the setup. */
    @Test fun aKeyOrBillingFailureOffersTheVendorsConsole() {
        for ((status, label) in listOf(401 to "manage api key at Fireworks", 402 to "add credits at Fireworks")) {
            failing(status)
            launch(*local(UiTestSeeding.EXTRA_LOCAL_NAME to "Fireworks"))
            send("status $status")
            compose.waitUntil(30_000) { exists("HTTP $status") && !tagExists("chat.stop") }
            assertTrue("HTTP $status offered no \"$label\"", exists(label))
        }
    }

    /** B4: a 400 with web search on offers "turn off & retry", and the retry re-asks WITHOUT the search tool and answers. */
    @Test fun aToolRefusalOffersSearchOffAndRetryAnswers() {
        keyFile(".BRAVE_API_KEY", "uitest-brave-key")
        failing(400)
        launch(*local(UiTestSeeding.EXTRA_GROUNDING to true, UiTestSeeding.EXTRA_BRAVE_ENDPOINT to "${server.baseUrl}/llm/context"))
        keepingSettings {
            send("what is the weather")
            compose.waitUntil(30_000) { exists("HTTP 400") && !tagExists("chat.stop") }
            tap("turn off & retry")
            waitForReply()
            assertTrue("the first request did not carry the search tool: nothing to turn off", server.bodies.first().contains("web_search"))
            val last = server.bodies.last(); val at = last.indexOf("web_search")
            assertFalse("the retry still asked for web search (${server.bodies.size} requests): …${if (at >= 0) last.substring((at - 300).coerceAtLeast(0), (at + 200).coerceAtMost(last.length)) else ""}…", at >= 0)
            appState.setGroundingEnabled(false)
        }
    }

    /** C3: each filter shows exactly its locality's ready rows — phone, home, cloud — and "all" shows them all. */
    @Test fun theWhereFiltersShowExactlyTheirLocality() {
        keyFile(".NEAR_AI_API_KEY", "uitest-near-key")
        launch(*local(UiTestSeeding.EXTRA_PRESETS to "nearai"))
        tapTag("chat.whereChip")
        val cloud = NEAR_AI_ROW; val home = "fake-model"
        compose.waitUntil(10_000) { exists(home) && exists(cloud) }
        tap("home"); Thread.sleep(500)
        assertTrue("home lost its machine", exists(home)); assertFalse("home shows the cloud place", exists("near.ai · end-to-end encrypted"))
        tap("cloud"); Thread.sleep(500)
        assertTrue("cloud lost near.ai", exists("near.ai · end-to-end encrypted")); assertFalse("cloud shows the home machine", exists("$home") && compose.onAllNodes(hasText(home)).fetchSemanticsNodes().any { it.boundsInRoot.top > 0 && it.boundsInRoot.height > 0 && exists("lm studio") })
        tap("all"); Thread.sleep(500)
        assertTrue("all is missing a place", exists(home) && exists("near.ai · end-to-end encrypted"))
    }

    /**
     * C5: edit a place — rename it, and delete it from places & keys (asked first; keep keeps it). A home machine's row
     * is titled by its HOST (PlaceManageRow's rule, as on iOS), so the row is opened by its "edit".
     */
    @Test fun aPlaceIsRenamedAndDeletedFromPlacesAndKeys() {
        launch(*local(UiTestSeeding.EXTRA_LOCAL_NAME to "studio box"))
        tap("settings"); compose.waitUntil(5_000) { exists("appearance") }
        tapLast("places & keys"); tap("home machines")
        compose.waitUntil(5_000) { exists("127.0.0.1") }
        tap("edit"); compose.waitUntil(5_000) { exists("computer") && exists("save") }
        val nameField = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().indexOfLast { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "studio box" }
        assertTrue("the edit form has no field holding the place's name", nameField >= 0)
        compose.onAllNodes(hasSetTextAction())[nameField].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[nameField].performTextInput("attic server"); Thread.sleep(400)
        tap("save"); Thread.sleep(800)
        assertEquals("the rename did not reach the store", listOf("attic server"), appState.providers.value.map { it.name })
        // delete from the place's own form: "forget this computer" asks; cancel keeps it; "delete provider" removes it
        tap("close"); tap("settings"); tapLast("places & keys"); tap("home machines")
        compose.waitUntil(5_000) { exists("127.0.0.1") }
        tap("edit"); compose.onAllNodes(hasText("forget this computer"))[0].performScrollTo().performClick(); Thread.sleep(600)
        compose.waitUntil(5_000) { exists("delete this provider?") }
        tapLast("cancel"); assertTrue("cancel removed the place", appState.providers.value.isNotEmpty())
        compose.onAllNodes(hasText("forget this computer"))[0].performScrollTo().performClick(); Thread.sleep(600)
        compose.waitUntil(5_000) { exists("delete this provider?") }
        tap("delete provider"); Thread.sleep(800)
        assertTrue("the place survived its delete", appState.providers.value.isEmpty())
    }

    /**
     * C5: a keyed setup's key cannot be emptied from its form — the save is refused, saying why, and the key stays. To
     * revoke a key the setup is deleted (the cloud keys page says so), as on iOS (NoKeySendGate: "empty-key save refused").
     */
    @Test fun aKeyedSetupRefusesToSaveWithItsKeyEmptied() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am force-stop org.chromium.webview_shell").close()
        keyFile(".FIREWORKS_API_KEY", "uitest-fireworks-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "fireworks")
        tap("settings"); compose.waitUntil(5_000) { exists("appearance") }
        tapLast("places & keys"); tap("cloud keys")
        tap("edit"); compose.waitUntil(5_000) { exists("cloud key") && exists("save") }
        val texts = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty() }
        val keyField = texts.indexOfLast { it == "uitest-fireworks-key" || (it.isNotEmpty() && it.all { c -> c == '•' }) }
        assertTrue("no field holds the key: ${texts.size} fields", keyField >= 0)
        compose.onAllNodes(hasSetTextAction())[keyField].performTextClearance(); Thread.sleep(400)
        tap("save"); Thread.sleep(1_000)
        assertTrue("the refusal does not say why", exists("enter your fireworks api key to save this setup."))
        assertTrue("the form closed on a refused save", exists("save") && exists("cloud key"))
        assertTrue("the stored key was lost to a refused save", appState.credentials.credential(appState.providers.value.first()).isNotEmpty())
    }

    /** I4 (part): the activity is recreated on a nested settings screen and comes back on that screen, not the chat. */
    @Test fun aRecreatedActivityComesBackOnTheSameScreen() {
        launch(*local())
        tap("settings"); tap("appearance"); compose.waitUntil(5_000) { exists("width") }
        scenario!!.recreate(); Thread.sleep(2_000)
        compose.waitUntil(10_000) { exists("width") && exists("design") }
    }

    /** I6: system back pops each sheet to its parent: appearance → settings → chat; where → its add form → where → chat; the chat → the chats list. */
    @Test fun systemBackPopsEachScreenToItsParent() {
        launch(*local())
        tap("settings"); tap("appearance"); compose.waitUntil(5_000) { exists("width") }
        back(); compose.waitUntil(5_000) { exists("places & keys") && !exists("width") }
        back(); compose.waitUntil(5_000) { !exists("places & keys") }
        tapTag("chat.whereChip"); tap("connect a computer"); compose.waitUntil(5_000) { exists("http is unencrypted") }
        back()   // the form's field has focus: the first back puts the keyboard away, as on every Android form
        back(); compose.waitUntil(5_000) { exists("ready now") && !exists("http is unencrypted") }
        back(); compose.waitUntil(5_000) { !exists("ready now") }
        // the chat's own back opens the chats list (docs/ANDROID_NATIVE.md: chat → the list; the list → leaves the app).
        // Closing a sheet hands focus back to the composer, so the keyboard is up and the first back puts it away.
        back(); back(); compose.waitUntil(5_000) { exists("new chat") }
    }

    private fun sendEnabled() = compose.onAllNodesWithTag("chat.send").fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Disabled) == null

    /**
     * A1: nothing configured — the chip says "choose where"; send is off while the composer is empty (ChatComposer:
     * `.disabled(isPromptEmpty)`), and a typed send asks for a provider instead of going anywhere.
     */
    @Test fun withNothingConfiguredSendWaitsForAPlace() {
        launch()
        assertTrue("the chip does not ask where", exists("choose where"))
        assertFalse("send is on with an empty composer", sendEnabled())
        tapTag("chat.composer"); field(0).performTextInput("hello?"); Thread.sleep(400)
        assertTrue("send is still off with text typed", sendEnabled())
        tapTag("chat.send")
        compose.waitUntil(5_000) { exists("no provider configured") }
        assertTrue("the ask offers no way in", exists("add a provider"))
        assertEquals("a message went out with nowhere to go", 0, server.bodies.size)
    }

    /** B10: a draft in the composer does not ride into a new chat. */
    @Test fun aNewChatStartsWithAnEmptyComposer() {
        launch(*local())
        send("a first message"); waitForReply()
        compose.waitUntil(30_000) { !tagExists("chat.stop") }
        tapTag("chat.composer"); field(0).performTextInput("half-written draft"); Thread.sleep(400)
        tap("chats"); tap("new chat")
        compose.waitUntil(5_000) { tagExists("chat.composer") }
        assertFalse("the draft rode into the new chat", exists("half-written draft"))
    }

    /** F5 (part): a long thread opens on its newest message, not its first. */
    @Test fun aLongThreadOpensOnItsNewestMessage() {
        launch(*local(UiTestSeeding.EXTRA_LONG_THREAD to 60))
        tap("chats"); tap("long thread")
        // the newest message's LAST line: the thread may land with the reply's end in view and its first words above it
        compose.waitUntil(15_000) { exists("reply 59:") || exists("Each row is one claim the verifier checked.") }
        assertFalse("the first question is on screen: the thread opened at its top", compose.onAllNodes(hasText("question 0:", substring = true)).fetchSemanticsNodes().any { it.boundsInRoot.height > 0 })
        // F5: dragging up — a finger, not a scroll-to — reaches the first message (LongThreadScrollUITests on iOS)
        val firstOnScreen = { compose.onAllNodes(hasText("question 0:", substring = true)).fetchSemanticsNodes().any { it.boundsInRoot.height > 0 } }
        repeat(80) { if (!firstOnScreen()) { compose.onRoot().performTouchInput { swipeDown(startY = height * 0.25f, endY = height * 0.85f, durationMillis = 150) }; Thread.sleep(150) } }
        assertTrue("dragging up never reached the first message", firstOnScreen())
    }

    private fun nearAI(attestation: String) { keyFile(".NEAR_AI_API_KEY", "uitest-near-key"); launch(UiTestSeeding.EXTRA_PRESETS to "nearai", UiTestSeeding.EXTRA_ATTESTATION to attestation) }

    /** E2 (ProductE2ETests.testHardFailureBlocksSend): a hard failure says "sending blocked", and a send is refused outright — no bypass. */
    @Test fun aHardFailureBlocksTheSend() {
        nearAI("hardFailure")
        compose.waitUntil(15_000) { exists("sending blocked") }
        send("should-not-leave")
        compose.waitUntil(5_000) { exists("verification failed") || countOf("sending blocked") > 1 }
        assertFalse("the hard block offered a way to send anyway", exists("send anyway"))
        assertFalse("a send went out under a hard block", tagExists("chat.stop"))
    }

    /**
     * E3 (testE2EEUnavailableRefusalShowsRetryAffordance): the verdict ALLOWS the send but no E2EE key ever arrived —
     * the refusal is an error that says nothing was sent, never a plaintext send, and its retry refuses again.
     */
    @Test fun anUnavailableE2EEKeyRefusesTheSendAndSaysNothingWasSent() {
        nearAI("e2eeUnavailable")
        compose.waitUntil(15_000) { tagExists("chat.titleBlock") || exists("near.ai") }
        assertFalse("the seeded verdict must allow the send: the title says blocked", exists("sending blocked"))
        send("seal-me-or-refuse")
        // the refusal comes when the app's own wait for the key runs out — 15 s (ConfidentialSession.e2eeKey) — so a
        // 15 s wait here left the test under a second; red now and then in long full runs (2026-10-13/14)
        compose.waitUntil(25_000) { exists("nothing was sent") }
        assertFalse("a refused turn started generating", tagExists("chat.stop"))
        if (tagExists("chat.retry")) {
            tapTag("chat.retry")
            // the retry waits out the same 15 s for the key; should it still time out, the screen is the evidence
            // (pulled from files/e3-retry.png)
            runCatching { compose.waitUntil(25_000) { exists("nothing was sent") } }.onFailure {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { b ->
                    File(ctx.getExternalFilesDir(null), "e3-retry.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
                throw it
            }
            assertFalse("the retry sent in plaintext", tagExists("chat.stop"))
        }
    }

    /** E4 (testMismatchCaptionDoesNotBlockSend): one reply that didn't check out is said, and sending still works. */
    @Test fun aMismatchIsSaidAndSendingStillWorks() {
        launch(*local(UiTestSeeding.EXTRA_ATTESTATION to "mismatch"))
        compose.waitUntil(15_000) { exists("check out") }   // "a reply didn’t check out": a typographic apostrophe
        send("still-send"); waitForReply()
    }

    /** E4 (testReplyDidntCheckOutOpensEverydaySheet): on near.ai the title's caption opens the sheet, which explains it. */
    @Test fun theMismatchCaptionOpensTheSheetThatExplainsIt() {
        nearAI("mismatch")
        compose.waitUntil(15_000) { exists("check out") }   // "a reply didn’t check out": a typographic apostrophe
        tapTag("chat.titleBlock")
        compose.waitUntil(10_000) { exists("who can read this?") }
        assertTrue("the sheet does not say a reply didn't check out", countOf("check out") >= 2)   // the title's caption, and the sheet's own
    }

    /**
     * C7: browse a keyed cloud's catalogue from where — the browser opens, a search narrows it, and picking a model
     * makes it the chat's model (the chip names it). near.ai's list is the shipped catalogue plus what the host answers.
     */
    @Test fun browsingACloudPicksAModelForTheChat() {
        keyFile(".NEAR_AI_API_KEY", "uitest-near-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "nearai")
        tapTag("chat.whereChip"); tap("cloud")
        compose.waitUntil(10_000) { presetRowText("near.ai") != null }
        tapTag("where.preset.near.ai")
        compose.waitUntil(20_000) { exists("search models") }
        compose.waitUntil(30_000) { exists("qwen") || exists("Qwen") }
        val search = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
        compose.onAllNodes(hasSetTextAction())[search].performTextInput("qwen"); Thread.sleep(1_000)
        back()   // the keyboard covers the rows
        val rows = compose.onAllNodes(hasText("qwen", substring = true, ignoreCase = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0 && it.config.getOrNull(SemanticsProperties.EditableText) == null }
        assertTrue("the search for qwen found no row", rows.isNotEmpty())
        val picked = rows.first().config[SemanticsProperties.Text].first().text
        compose.onAllNodes(hasText(picked)).fetchSemanticsNodes().let { compose.onAllNodes(hasText(picked))[0].performClick() }; Thread.sleep(1_500)
        compose.waitUntil(10_000) { !exists("search models") }
        assertTrue("the pick did not become the chat's model: ${appState.providers.value.map { it.model }}",
            appState.providers.value.any { it.model.contains("qwen", ignoreCase = true) && it.id == appState.selectedId.value })
    }

    /** C7: an id the list does not have is offered as typed ("use … as model id"), and using it makes it the chat's model. */
    @Test fun aTypedModelIdIsUsedWhenTheListHasNoMatch() {
        keyFile(".OPENROUTER_API_KEY", "uitest-openrouter-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "openrouter")
        tapTag("chat.whereChip"); tap("cloud")
        compose.waitUntil(10_000) { presetRowText("openrouter") != null }
        tapTag("where.preset.openrouter")
        compose.waitUntil(20_000) { exists("search models") }
        val typed = "uitest/typed-model-zz"
        val search = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
        compose.onAllNodes(hasSetTextAction())[search].performTextInput(typed); Thread.sleep(1_000)
        back()   // the keyboard covers the rows
        compose.waitUntil(20_000) { exists("use “$typed” as model id") }
        tap("use “$typed” as model id")
        compose.waitUntil(10_000) { !exists("search models") }
        assertTrue("the typed id did not become the chat's model: ${appState.providers.value.map { it.model }}",
            appState.providers.value.any { it.model == typed && it.id == appState.selectedId.value })
    }

    /**
     * C7: a catalogue that cannot be fetched says so, and "try again" fetches again. Fireworks with a key it refuses and
     * nothing saved from an earlier visit: the browser has no list to fall back on, so the failure is the whole screen.
     */
    @Test fun aCatalogThatFailsToLoadSaysSoAndTryAgainFetchesAgain() {
        keyFile(".FIREWORKS_API_KEY", "uitest-refused-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "fireworks")
        tapTag("chat.whereChip"); tap("cloud")
        compose.waitUntil(10_000) { presetRowText("fireworks") != null }
        shell("logcat -c")
        tapTag("where.preset.fireworks")
        compose.waitUntil(30_000) { exists("couldn't load models") }
        compose.waitUntil(5_000) { browseFetches() >= 1 }
        val before = browseFetches()
        tapTag("modelBrowser.retry")
        // the retry FETCHES again: counted from the app's own "[browse] Fireworks: … live=failed" line. Watching the
        // failed block leave and return raced a refusal that lands within a frame.
        compose.waitUntil(30_000) { browseFetches() > before }
        compose.waitUntil(10_000) { exists("couldn't load models") }
        assertTrue("the failed state lost its retry", tagExists("modelBrowser.retry"))
    }

    /** E1: degraded — the first send asks; "send unencrypted" sends (the turn leaves: it streams, or the host answers with an error). */
    @Test fun theDegradedGatesSendAnywaySends() {
        keyFile(".NEAR_AI_API_KEY", "uitest-near-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "nearai", UiTestSeeding.EXTRA_ATTESTATION to "degraded")
        tapTag("chat.composer"); field(0).performTextInput("hello anyway"); Thread.sleep(300); tapTag("chat.send")
        // the bypass names what it does (TrustSnapshot.bypassLabel): unencrypted here; "send anyway (still encrypted)" only when provenance alone is short
        compose.waitUntil(15_000) { exists("send unencrypted") }
        tap("send unencrypted")
        compose.waitUntil(30_000) { tagExists("chat.stop") || tagExists("chat.error") }
        assertTrue("the prompt is not in the transcript", exists("hello anyway"))
    }

    /** A7: first run through "use a cloud api": the picker → near.ai → paste the key → save ends in the chat, the chip naming near.ai's model. */
    @Test fun firstRunThroughACloudKeyEndsInTheChat() {
        launch()
        tapTag("chat.whereChip"); compose.waitUntil(10_000) { exists("start here") }
        tap("use a cloud api")
        compose.waitUntil(10_000) { tagExists("cloudKey.preset.near.ai") }
        tapTag("cloudKey.preset.near.ai")
        compose.waitUntil(10_000) { exists("add near.ai key") }
        sheetField(0).performTextInput("uitest-near-key"); Thread.sleep(400)
        tap("save")
        compose.waitUntil(10_000) { !exists("add near.ai key") && !exists("start here") && !exists("ready now") }
        assertTrue("the chip does not name near.ai's model: ${appState.providers.value.map { it.model }}", exists(NEAR_AI_ROW) || exists(Provider.nearAI.model.substringAfterLast('/')))
        assertTrue("first run did not end in the chat", tagExists("chat.composer") && !exists("add near.ai key"))
    }

    /** A8: a keyed setup with no key stored: the send is gated with "no api key", and "add a key" leads to its key form. */
    @Test fun aKeylessSetupAsksForAKeyInsteadOfSending() {
        File(File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }, ".FIREWORKS_API_KEY").let { f -> if (f !in replacedKeys) replacedKeys[f] = f.takeIf { it.isFile }?.readText(); f.delete() }
        launch(UiTestSeeding.EXTRA_PRESETS to "fireworks")
        send("hello?")
        compose.waitUntil(10_000) { exists("no api key") }
        assertFalse("a keyless send went out", tagExists("chat.stop"))
        tap("add a key")
        compose.waitUntil(10_000) { exists("api key — required") || exists("cloud key") || exists("add fireworks key") }
    }

    /** B11: the title names the model and says where it runs ("on your own machine") for a home server. */
    @Test fun theTitleNamesTheModelAndTheMachine() {
        launch(*local())
        compose.waitUntil(10_000) { exists("on your own machine") }
        assertTrue("the chip does not name the model", exists("fake-model"))
    }

    /** E9: a self-hosted place's trust sheet is the honest "on your own machine" view — no everyday/expert rung picker. */
    @Test fun aHomeServersSheetSaysOnYourOwnMachineWithNoRungs() {
        launch(*local())
        tapTag("chat.titleBlock")
        compose.waitUntil(10_000) { countOf("on your own machine") >= 2 }
        assertFalse("a self-hosted sheet offers the expert rung", exists("expert"))
    }

    /** F2: deleting the OPEN chat from the list leaves a fresh, empty chat behind — never its transcript. */
    @Test fun deletingTheOpenChatLeavesAFreshOne() {
        launch(*local())
        send("about to go"); waitForReply(); compose.waitUntil(30_000) { !tagExists("chat.stop") }
        tap("chats")
        val rows = countOf("about to go")
        compose.onAllNodes(hasText("about to go", substring = true))[rows - 1].performTouchInput { longClick() }
        compose.waitUntil(10_000) { exists("delete this chat?") }
        tap("delete", index = countOf("delete") - 1)
        compose.waitUntil(10_000) { !exists("delete this chat?") }
        if (exists("new chat")) back()
        compose.waitUntil(10_000) { !exists("Paragraph 0:") }
        assertFalse("the deleted chat's transcript is still drawn", exists("about to go") && exists("Paragraph 5:"))
    }

    /** H2: developer mode on → the request card stands under the reply; off → it is gone. */
    @Test fun developerModeShowsAndHidesTheDebugCard() {
        launch(*local(UiTestSeeding.EXTRA_DEVELOPER to true))
        send("debug me"); waitForReply(); compose.waitUntil(30_000) { !tagExists("chat.stop") }
        compose.waitUntil(10_000) { tagExists("chat.debugCard") }
        tap("settings"); compose.waitUntil(5_000) { exists("developer mode") }
        compose.onAllNodes(hasText("developer mode"))[0].performScrollTo(); Thread.sleep(300)
        compose.onAllNodes(isToggleable())[0].performClick(); Thread.sleep(500)
        tap(A11yLabels.CLOSE_SETTINGS)
        compose.waitUntil(5_000) { !tagExists("chat.debugCard") }
    }

    /** Opens "connect a computer" from where and types [host] (the scheme chip says http:// already), then fetches. */
    private fun connectAndFetch(host: String) {
        tapTag("chat.whereChip"); tap("connect a computer")
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3 }
        sheetField(1).performTextInput("box"); sheetField(0).performTextInput(host); Thread.sleep(500)
        tap("fetch models")
    }

    /** A3: connect a computer, failure paths — nothing listening, a server with no models, and http to a public host refused. */
    @Test fun connectingAComputerSaysWhyItFailed() {
        launch()
        connectAndFetch("127.0.0.1:9/v1")
        compose.waitUntil(20_000) { exists("nothing listening there") }
        assertFalse("a failed probe can be saved", appState.providers.value.isNotEmpty())
        server.close(); server = FakeSseServer(models = emptyList())
        launch()
        connectAndFetch(server.baseUrl.removePrefix("http://"))
        compose.waitUntil(20_000) { exists("no models were returned") }
        launch()
        connectAndFetch("example.com/v1")
        compose.waitUntil(20_000) { exists("android blocked http") }
    }

    /**
     * A5: a key the host refuses (401 on /models). Offline through "connect a computer" — the custom cloud form is
     * https-only and the fake wire is http: the first fetch learns a key is required and shows the key field; with a
     * key the host still refuses, the form says "key rejected", and nothing is saved.
     */
    @Test fun aRejectedKeyIsSaidAndNothingIsSaved() {
        server.close(); server = FakeSseServer(rejectKey = true)
        launch()
        connectAndFetch(server.baseUrl.removePrefix("http://"))
        compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 4 }   // the key field appeared
        sheetField(0).performTextInput("uitest-bad-key"); Thread.sleep(500)
        tap("fetch models")
        compose.waitUntil(20_000) { exists("key rejected") }
        assertTrue("a refused key left a setup behind", appState.providers.value.isEmpty())
    }

    /** B7 (part): a long press on the question offers "copy", and the question is what lands on the clipboard. */
    @Test fun longPressingTheQuestionCopiesIt() {
        launch(*local())
        send("copy exactly this"); waitForReply(); compose.waitUntil(30_000) { !tagExists("chat.stop") }
        // the bubble, not the chat's title (which is the same words, up in the header): the match lowest on screen
        val nodes = compose.onAllNodes(hasText("copy exactly this")).fetchSemanticsNodes()
        val bubble = nodes.indices.maxByOrNull { nodes[it].boundsInRoot.top }!!
        compose.onAllNodes(hasText("copy exactly this"))[bubble].performTouchInput { longClick() }; Thread.sleep(600)
        // the menu's own "copy" — exactly that text; the bubble ("copy exactly this") contains the word too
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("copy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("copy")).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        Thread.sleep(500)
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals("the clipboard does not hold the question", "copy exactly this", clip)
    }

    /**
     * B7: part of an answer can be selected and copied. A long press on the answer selects the word under the finger
     * (the answer sits in a SelectionContainer, as iOS's is `.textSelection(.enabled)`); the platform's floating
     * toolbar is a window of its own, outside Compose's tree, so its "copy" is found and pressed through accessibility.
     */
    @Test fun partOfAnAnswerCanBeSelectedAndCopied() {
        val answer = "Selectable alpha bravo charlie delta echo foxtrot golf hotel india juliet."
        server.close(); server = FakeSseServer(reply = answer)
        launch(*local())
        send("say the alphabet"); compose.waitUntil(60_000) { exists("juliet") }; compose.waitUntil(30_000) { !tagExists("chat.stop") }
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "")) }
        compose.onAllNodes(hasText("charlie", substring = true))[0].performTouchInput { longClick(center) }
        fun copyButton(): AccessibilityNodeInfo? {
            ua.serviceInfo = ua.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            for (w in ua.windows) {
                fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (n == null) return null
                    if (n.text?.toString().equals("copy", ignoreCase = true) || n.contentDescription?.toString().equals("copy", ignoreCase = true)) return n
                    for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                    return null
                }
                find(w.root)?.let { return it }
            }
            return null
        }
        var button: AccessibilityNodeInfo? = null
        compose.waitUntil(5_000) { button = copyButton(); button != null }
        var target = button
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("the selection toolbar's copy is not pressable", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(500)
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        val copied = clip.orEmpty().trim()
        assertTrue("nothing was copied from the answer", copied.isNotEmpty())
        assertTrue("the copy \"$copied\" is not from the answer", answer.contains(copied))
        assertTrue("the whole answer was copied, not a part of it", copied.length < answer.length)
    }

    /** H1: tint, size and width chosen in settings stick, and survive a relaunch. */
    @Test fun everyAppearanceChoiceSurvivesARelaunch() {
        launch(*local())
        keepingSettings {
            tap("settings"); tap("appearance")
            // system back pops the color and size pages to appearance — not past it (it skipped to settings until 2026-10-04)
            tap("color"); tap("teal"); back()
            compose.waitUntil(5_000) { exists("teal") && exists("width") }
            tap("size"); tap("large"); back()
            compose.waitUntil(5_000) { exists("large") && exists("width") }
            tap("width"); tap("expanded")
            compose.waitUntil(5_000) { exists("expanded") }
            launch(*local())
            tap("settings"); tap("appearance")
            compose.waitUntil(5_000) { exists("teal") && exists("large") && exists("expanded") }
            assertEquals(AppTintColor.TEAL, appState.settings.appTintColor); assertEquals(AppFontSize.LARGE, appState.settings.appFontSize)
            assertEquals(AppFontWidth.EXPANDED, appState.settings.appFontWidth)
        }
    }

    /** H3: places & keys — the hub counts the computers, and "add cloud key" from cloud keys adds a row there. */
    @Test fun placesAndKeysAddsACloudKeyToItsList() {
        launch(*local())
        tap("settings"); tapLast("places & keys")
        compose.waitUntil(5_000) { exists("home machines") && exists("cloud keys") }
        tap("cloud keys"); compose.waitUntil(5_000) { exists("no cloud keys yet.") }
        tap("add cloud key")
        compose.waitUntil(10_000) { tagExists("cloudKey.preset.near.ai") }
        tapTag("cloudKey.preset.near.ai")
        compose.waitUntil(10_000) { exists("add near.ai key") }
        sheetField(0).performTextInput("uitest-near-key"); Thread.sleep(400)
        tap("save")
        compose.waitUntil(10_000) { !exists("add near.ai key") }
        assertTrue("near.ai was not saved", appState.providers.value.any { it.endpoint.contains("near.ai") })
    }

    /** I5: rotating the phone mid-stream — the stream continues to its end, and the reply lands. */
    @Test fun rotatingMidStreamKeepsTheStream() {
        server.close(); server = FakeSseServer(lines = 40, delayMs = 30)
        launch(*local())
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            send("turn me around")
            compose.waitUntil(20_000) { tagExists("chat.stop") }
            ui.setRotation(UiAutomation.ROTATION_FREEZE_90); Thread.sleep(2_500)
            ui.setRotation(UiAutomation.ROTATION_FREEZE_0); Thread.sleep(1_500)
            compose.waitUntil(60_000) { !tagExists("chat.stop") }
            compose.waitUntil(10_000) { exists("Paragraph 39:") }
            assertFalse("the rotation broke the turn", tagExists("chat.error"))
            assertEquals("the rotation re-sent the turn", 1, server.bodies.size)
        } finally { ui.setRotation(UiAutomation.ROTATION_UNFREEZE) }
    }

    private fun setClipboard(text: String) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("key", text))
    }

    /** B6 (part): a markdown reply renders as structure — heading, list, table cells and a code block with copy — with none of its syntax on screen. */
    @Test fun aMarkdownReplyRendersWithoutItsSyntax() {
        val md = "## Harbour notes\n\n- first buoy\n- second buoy\n\n| ship | berth |\n|---|---|\n| Aurora | 7 |\n\n```\nlet tide = 4\n```\n\nSee [the chart](https://example.org/chart) for more."
        server.close(); server = FakeSseServer(reply = md)
        launch(*local())
        send("show me markdown")
        compose.waitUntil(30_000) { exists("for more") && !tagExists("chat.stop") }
        assertTrue("the heading did not render", exists("Harbour notes")); assertFalse("the heading's ## is on screen", exists("## Harbour"))
        assertTrue("the list did not render", exists("first buoy") && exists("second buoy")); assertFalse("list syntax on screen", exists("- first buoy"))
        assertTrue("the table's cells did not render", exists("Aurora")); assertFalse("table syntax on screen", exists("|---|") || exists("| ship"))
        assertTrue("the code block did not render", exists("let tide = 4")); assertFalse("fence syntax on screen", exists("```"))
        assertTrue("the link text did not render", exists("the chart")); assertFalse("link syntax on screen", exists("](https"))
    }

    /** D3: no key — the model calls search, the offer card appears; a pasted key Brave accepts re-asks the question, and it answers. */
    @Test fun anOfferedSearchKeyIsCheckedAndTheQuestionIsAskedAgain() {
        server.close(); server = FakeSseServer(toolRound = true)
        launch(*local(UiTestSeeding.EXTRA_NO_BRAVE_KEY to true, UiTestSeeding.EXTRA_BRAVE_ENDPOINT to "${server.baseUrl}/llm/context"))
        keepingSettings {
            send("what is the weather in paris")
            compose.waitUntil(30_000) { tagExists("chat.webOffer") && !tagExists("chat.stop") }
            setClipboard("uitest-brave-key")
            tapTag("chat.webOffer.paste")
            compose.waitUntil(10_000) { exists("brave accepted this key") }
            // the re-ask: a third completion, now carrying the KEYED search tool, answered (this fake calls the tool on its
            // first request only, so the re-asked turn answers straight away)
            compose.waitUntil(30_000) { server.bodies.size >= 3 && !tagExists("chat.stop") && exists("Paragraph 5:") }
            assertTrue("the key was never checked with brave", server.searchRequests.get() >= 1)
            assertTrue("the re-ask went out without the search tool", server.bodies.last().contains("web_search"))
            appState.setBraveKey(""); appState.setGroundingEnabled(false)
        }
    }

    /** D6: search settings — a key Brave refuses is said so; a key it accepts turns search on. */
    @Test fun searchSettingsChecksTheKeyWithBrave() {
        for (rejects in listOf(true, false)) {
            server.close(); server = FakeSseServer(braveRejects = rejects)
            launch(*local(UiTestSeeding.EXTRA_NO_BRAVE_KEY to true, UiTestSeeding.EXTRA_BRAVE_ENDPOINT to "${server.baseUrl}/llm/context"))
            tap("settings"); tapLast("search")
            compose.waitUntil(5_000) { exists("already have a key?") }
            sheetField(0).performTextInput(if (rejects) "uitest-wrong-key" else "uitest-brave-key")
            // the key is committed when the field is LEFT (SearchSettingsView: onSubmit / focus loss) — tab moves focus on
            sheetField(0).performKeyInput { pressKey(Key.Tab) }; Thread.sleep(800)
            if (rejects) compose.waitUntil(10_000) { exists("didn't recognise this key") }
            else { compose.waitUntil(10_000) { exists("brave accepted") || appState.settings.braveGroundingEnabled }; assertTrue("an accepted key did not turn search on", appState.settings.braveGroundingEnabled) }
        }
        appState.setBraveKey(""); appState.setGroundingEnabled(false)
    }

    /**
     * E8 (testAddingAKeyFlipsTheEncryptedModelCTAWithoutReopeningTheSheet): a near.ai setup with no key stored and
     * nothing to attest draws the sheet's none branch, whose last button offers the key form. The form opens OVER the
     * sheet; once the key is saved the sheet is still there and the button says "switch" — no reopening.
     */
    @Test fun theEncryptedModelOfferBecomesASwitchOnceANearAIKeyIsAdded() {
        File(File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }, ".NEAR_AI_API_KEY").let { f -> if (f !in replacedKeys) replacedKeys[f] = f.takeIf { it.isFile }?.readText(); f.delete() }
        launch(UiTestSeeding.EXTRA_PRESETS to "nearai", UiTestSeeding.EXTRA_ATTESTATION to "none")
        tapTag("chat.titleBlock")
        compose.waitUntil(10_000) { tagExists("trust.encryptedModelCTA") }
        assertTrue("a keyless near.ai setup should be offered the key form", exists("end-to-end encryption is available on near.ai"))
        compose.onAllNodesWithTag("trust.encryptedModelCTA")[0].let { runCatching { it.performScrollTo() }; it.performClick() }; Thread.sleep(600)
        compose.waitUntil(10_000) { exists("save") && compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3 }
        sheetField(0).performTextInput("uitest-near-key"); Thread.sleep(400)
        tap("save")
        compose.waitUntil(15_000) { exists("switch to an end-to-end encrypted model on near.ai") }
        assertTrue("the sheet did not stay up under the form", exists("who can read this?"))
        assertFalse("the key form is still offered after a key was saved", exists("end-to-end encryption is available on near.ai"))
    }

    /** G5: delete a phone model's weights (asked first) → the row goes back to get and offers the download again. */
    @Test fun deletingAModelsWeightsOffersTheDownloadAgain() {
        val app = (ctx.applicationContext as TeemoonApplication)
        launch(*local())
        val m = app.state!!.localCatalog.firstOrNull { app.state!!.localStorage.isInstalled(it) }
        org.junit.Assume.assumeTrue("no installed phone model on this device", m != null)
        // The REAL file is set aside and a stand-in takes its place: the app deletes what it believes is the model, and
        // the 2.4 GB the other journeys need comes back after (a rename on the same disk, not a copy).
        val file = app.state!!.localStorage.file(m!!); val aside = File(ctx.filesDir, "uitest-weights-aside.litertlm")
        assertTrue("could not set the weights aside", file.renameTo(aside)); file.writeText("stand-in")
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.state!!.startAndSelect(m) }   // equipped as a place
            tapTag("chat.whereChip"); tap("phone")
            val rows = countOf(m.displayName.lowercase())
            compose.onAllNodes(hasText(m.displayName.lowercase(), substring = true))[rows - 1].performTouchInput { longClick() }; Thread.sleep(600)
            tapLast("delete")
            compose.waitUntil(5_000) { exists("delete ${m.displayName}?") }
            tapLast("delete")
            compose.waitUntil(10_000) { !app.state!!.localStorage.isInstalled(m) }
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("download")).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            file.parentFile?.mkdirs(); file.delete(); aside.renameTo(file)
        }
        assertTrue("the weights were not put back", app.state!!.localStorage.isInstalled(m))
    }

    /** The pull screen's link field, found by its prompt and typed into once it has focus. */
    private fun typeLink(ref: String) {
        tap("paste the link here")
        compose.onNode(isFocused()).performTextInput(ref); Thread.sleep(400)
    }

    /** The where sheet's home rows, opened fresh (the sheet refreshes the machine's state on appearance). */
    private fun openHome() { if (exists("ready now")) tap("close"); tapTag("chat.whereChip"); tap("home"); Thread.sleep(800) }

    /**
     * C6: a home server's rows — warm while the model is loaded, cold once it is not (read on the sheet's refresh) — a
     * server that refuses the delete says so and keeps the model; one that accepts it takes it off the machine and the list.
     */
    @Test fun aHomeServerShowsWarmthAndDeletesAModel() {
        server.close(); server = FakeSseServer(ollama = true)
        server.ollamaModels.add("other-model")
        launch(*local())
        openHome()
        compose.waitUntil(10_000) { exists("warm") }
        // the app warms the selected model on the sheet's appearance (as on iOS); a model the machine evicted and will not
        // reload is the cold one to watch
        server.ollamaWarmsOnGenerate = false; server.ollamaWarm.clear()
        openHome()
        compose.waitUntil(10_000) { exists("cold") && !exists("warm") }
        // refused first, then allowed
        server.ollamaDeleteFails = true
        val rows = countOf("fake-model")
        compose.onAllNodes(hasText("fake-model", substring = true))[rows - 1].performTouchInput { longClick() }; Thread.sleep(600)
        tapLast("delete from")
        compose.waitUntil(5_000) { exists("delete fake-model from that machine?") }
        tapLast("delete")
        compose.waitUntil(10_000) { exists("permission denied") || exists("couldn't delete") || exists("failed") }
        assertTrue("the refused delete removed the model", "fake-model" in server.ollamaModels)
        if (exists("ok")) tapLast("ok")
        compose.waitUntil(10_000) { exists("fake-model") }
        // and a delete the machine accepts takes it off the machine and off the list
        server.ollamaDeleteFails = false
        compose.onAllNodes(hasText("fake-model", substring = true))[countOf("fake-model") - 1].performTouchInput { longClick() }; Thread.sleep(600)
        tapLast("delete from")
        compose.waitUntil(5_000) { exists("delete fake-model from that machine?") }
        tapLast("delete")
        compose.waitUntil(10_000) { "fake-model" !in server.ollamaModels }
        compose.waitUntil(10_000) { !exists("delete fake-model from that machine?") }
        openHome()
        runCatching { compose.waitUntil(10_000) { exists("other-model") } }.onFailure {
            throw AssertionError("the machine's other model is not listed after the delete; places: ${appState.providers.value.map { "${it.id} model=${it.model} equipped=${it.equipped}" }}; probe: ${appState.homeProbe.infoByProvider.value}; server has ${server.ollamaModels}")
        }
        assertFalse("the deleted model is still equipped", appState.providers.value.any { it.id == "uitest-local" && "fake-model" in it.equipped })
    }

    /** C8: pull a model onto an Ollama machine from where — it shows progress, lands, and is listed; a failed pull says so and retries. */
    @Test fun pullingAModelOntoTheMachineLandsIt() {
        server.close(); server = FakeSseServer(ollama = true)
        launch(*local())
        openHome()
        tap("add a model to")
        compose.waitUntil(10_000) { exists("download a model") }
        typeLink("tinyllama")
        tap("download")
        compose.waitUntil(30_000) { "tinyllama" in server.ollamaModels }
        compose.waitUntil(15_000) { exists("installed") || !exists("downloading") }
        assertTrue("the pulled model was not equipped", appState.providers.value.any { p -> p.equipped.any { it.contains("tinyllama") } })
        // a pull that cannot succeed: after its attempts the where row says so, and a tap on it pulls again
        server.ollamaPullFails = true
        tap(if (exists("close")) "close" else "cancel"); openHome()
        tap("add a model to")
        compose.waitUntil(10_000) { exists("download a model") }
        typeLink("smollm")
        tap("download")
        if (exists("close")) tap("close")
        openHome()
        compose.waitUntil(90_000) { exists("download failed — tap to retry") }   // eight attempts with no progress, then it says so
        server.ollamaPullFails = false
        tapLast("download failed — tap to retry")
        compose.waitUntil(30_000) { "smollm" in server.ollamaModels }
    }

    private fun shell(cmd: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        .let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() } }
    /** How many live catalogue fetches the browser has logged for Fireworks since the last `logcat -c`. */
    private fun browseFetches(): Int = shell("logcat -d -s teemoon").lines().count { it.contains("[browse] Fireworks:") }

    /** Text in every window that is NOT this app's — the browser a link opened, say. */
    private fun otherAppsText(): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; n.text?.let { out.append(it).append('\n') }; for (i in 0 until n.childCount) walk(n.getChild(i)) }
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        ua.serviceInfo = ua.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        ua.windows.filter { it.root?.packageName != ctx.packageName }.forEach { walk(it.root) }
        return out.toString()
    }

    /** Taps [label] and asserts the browser opened at [host]; then comes back to the app. */
    private fun linkOpens(label: String, host: String) {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        ua.executeShellCommand("am force-stop org.chromium.webview_shell").close()
        tapLast(label)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !otherAppsText().contains(host)) Thread.sleep(300)
        assertTrue("\"$label\" did not open the browser at $host; other windows said: ${otherAppsText().take(300)}", otherAppsText().contains(host))
        ua.executeShellCommand("am force-stop org.chromium.webview_shell").close()
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); Thread.sleep(1_500)
    }

    /** H4: outbound links open the browser at the host they name — every credit, brave's api page, every preset's key page. */
    @Test fun outboundLinksOpenTheBrowserAtTheRightHost() {
        launch(*local())
        tap("settings"); tapLast("credits")
        for (c in CREDITS) { compose.waitUntil(10_000) { exists(c.name) }; linkOpens(c.name, URI(c.url).host.removePrefix("www.")) }
        launch(*local(UiTestSeeding.EXTRA_NO_BRAVE_KEY to true))
        tap("settings"); tapLast("search"); compose.waitUntil(5_000) { exists("already have a key?") }
        linkOpens("brave.com/search/api", "brave.com")
        // every preset's key form links to where that vendor issues keys (ProviderPresets.signupURL)
        for ((tag, name, host) in listOf(
            Triple("near.ai", "near.ai", "near.ai"), Triple("grok", "grok", "x.ai"), Triple("fireworks", "fireworks", "fireworks.ai"),
            Triple("openrouter", "openrouter", "openrouter.ai"), Triple("nvidia", "nvidia", "nvidia.com"), Triple("brave answers", "brave answers", "brave.com"))) {
            launch(*local())
            tapTag("chat.whereChip"); tap("cloud")
            runCatching { compose.waitUntil(10_000) { tagExists("where.preset.$tag") } }.onFailure {
                throw AssertionError("no where.preset.$tag row; texts: " + compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }.joinToString(" | ").take(800))
            }
            tapTag("where.preset.$tag")
            // the link names the vendor's console ("get xai api key" on grok's form), so it is found, not spelled
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("api key", substring = true)).fetchSemanticsNodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("get ") } == true } }
            val link = compose.onAllNodes(hasText("api key", substring = true)).fetchSemanticsNodes().firstNotNullOf { n -> n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.startsWith("get ") }?.text }
            linkOpens(link, host)
        }
    }

    /** Two places: the chip names the selected model, and picking a row on the where sheet moves it. */
    @Test fun switchingThePlaceFromTheChip() {
        keyFile(".NEAR_AI_API_KEY", "uitest-near-key")
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model", UiTestSeeding.EXTRA_PRESETS to "nearai")
        tapTag("chat.whereChip"); compose.waitUntil(10_000) { exists(NEAR_AI_ROW) }; golden("switch.where-sheet"); tap(NEAR_AI_ROW)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat.whereChip").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the chip does not name $NEAR_AI_ROW after picking it", compose.onAllNodes(hasText(NEAR_AI_ROW, substring = true)).fetchSemanticsNodes().isNotEmpty())
        tapTag("chat.whereChip"); tap("fake-model")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat.whereChip").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the chip does not name fake-model after switching back", exists("fake-model"))
        send("hi"); waitForReply(); golden("switch.reply", settleMs = 2_000)
    }

    /**
     * iOS f2fb40b (ProductE2ETests.testSwitchingFromAVerifyingAttestedSessionToALocalProviderSends). NVIDIA on the dev
     * build, 2026-09-12: after a near.ai session whose verifier was still running, every send on a plain-TLS provider
     * was refused with "Verification is still running, so nothing was sent". The provider smokes never see this — they
     * bypass the gate. This is the sequence a user does constantly: verify on near.ai, switch, send. Offline: the near.ai
     * session is seeded verifying with a TLS verifier that never answers, and the local setup is the fake server.
     */
    @Test fun switchingFromAVerifyingAttestedSessionToALocalProviderSends() {
        keyFile(".NEAR_AI_API_KEY", "uitest-near-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "nearai", UiTestSeeding.EXTRA_ATTESTATION to "verifyingPending",
            UiTestSeeding.EXTRA_ALSO_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        // Still VERIFYING either way: with no first record, the 8 s verifier timer (VerifierTimeout, TrustLadderView)
        // turns the label to "can't reach verifier" — a slower launch on the Pixel crossed it before this line.
        val onScreen = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } }.joinToString(" | ").take(600)
        assertTrue("precondition: the near.ai session is verifying — on screen: $onScreen", exists("verifying") || exists("can\u2019t reach verifier"))
        // Switch to the local setup through the Where sheet.
        tapTag("chat.whereChip"); tap("fake-model")
        compose.waitUntil(10_000) { tagExists("chat.composer") && exists("fake-model") }
        send("after-switch"); waitForReply()
        assertFalse("a plain-TLS send after an attested session must not raise the error surface", tagExists("chat.error"))
        assertFalse("no refusal on a plain-TLS send", exists("Verification is still running"))
    }

    /**
     * I2 (docs/JOURNEYS.md): tapping a "reply ready" notification opens THAT thread. The notification put the thread
     * on its intent from the start, and nothing read it — the tap brought the app forward on whatever it showed
     * (found by the 2026-09-24 inventory). The real PendingIntent is sent, so this is exactly what a tap does.
     */
    @Test fun tappingAReplyNotificationOpensItsThread() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model")
        send("the notified question"); waitForReply()
        // Somewhere else: a new, empty chat.
        tap("chats"); tap("new chat")
        compose.waitUntil(10_000) { !exists("Paragraph 5:") }
        val threads = runBlocking { ChatRepository(ChatDatabase.get(ctx).chatDao()).threads().first() }
        val id = threads.first { t -> t.title?.contains("notified question") == true }.id
        TurnService.open(ctx, id).send()
        compose.waitUntil(20_000) { exists("the notified question") && exists("Paragraph 5:") }
    }

    /**
     * G3 (docs/JOURNEYS.md; iOS DownloadLifecycleUITests.testMobileDataPromptAndWaitingForWifi). On mobile data a phone
     * download ASKS; "wait for wi-fi" parks it — the row says so and no byte is fetched — and wi-fi coming back starts
     * it. Until 2026-09-24 the question was resolved and never drawn, so a download tapped on mobile data never
     * started, and the answer was dropped anyway. Gemma 4 E4B, never E2B: a phone that has E2B keeps it untouched.
     */
    /** G4 (testIntegrityFailureIsShownOnTheRow): a download whose bytes fail the pinned checksum is discarded, the row says so, and retry starts over. */
    @Test fun aDownloadThatFailsItsChecksumSaysSoAndRetryStartsOver() {
        server.close(); server = FakeSseServer(modelBytes = 4096)
        val app = (ctx.applicationContext as TeemoonApplication)
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model",
            UiTestSeeding.EXTRA_MODEL_HOST to server.rootUrl, UiTestSeeding.EXTRA_MOBILE_DATA to false)
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        try {
            tapTag("chat.whereChip"); tap("phone")
            tap(e4b.displayName.lowercase())   // starting a download closes the sheet, as on iOS
            compose.waitUntil(30_000) { server.modelRequests.get() >= 1 && app.state!!.localDownloads.jobs.value[e4b.id] == null }
            Thread.sleep(1_000)
            if (!exists("phone")) tapTag("chat.whereChip")
            tap("phone")
            compose.waitUntil(10_000) { exists("integrity check") }
            assertFalse("a file that failed its checksum was kept", app.state!!.localStorage.isInstalled(e4b))
            val before = server.modelRequests.get()
            // the place is under "ready now" with the failure as its caption; a tap on it downloads again (the chip under
            // the sheet names the same model, so the row is the LAST match)
            tapLast(e4b.displayName.lowercase())
            compose.waitUntil(20_000) { server.modelRequests.get() > before }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.state?.network?.uiTestOverrideMobileData(null); app.state?.deleteWeights(e4b) }
            LocalModelDownloader.hostOverride = null
        }
    }

    /** G1: download a phone model from where — it finishes (the fake host's bytes, their checksum pinned for the run), and is the chat's model. */
    @Test fun aPhoneModelDownloadFinishesAndIsSelected() {
        val bytes = 64 * 1024
        server.close(); server = FakeSseServer(modelBytes = bytes)
        val app = (ctx.applicationContext as TeemoonApplication)
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        val sha = MessageDigest.getInstance("SHA-256").digest(ByteArray(bytes) { 7 }).joinToString("") { "%02x".format(it) }
        LocalModelDownloader.shaOverride = mapOf(e4b.id to sha)
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model",
            UiTestSeeding.EXTRA_MODEL_HOST to server.rootUrl, UiTestSeeding.EXTRA_MOBILE_DATA to false)
        try {
            tapTag("chat.whereChip"); tap("phone")
            tap(e4b.displayName.lowercase())
            compose.waitUntil(30_000) { app.state!!.localStorage.isInstalled(e4b) }
            compose.waitUntil(10_000) { app.state!!.providers.value.any { it.localModelID == e4b.id && it.id == app.state!!.selectedId.value } }
            compose.waitUntil(10_000) { exists("gemma 4 e4b") }   // the chip names it
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.state?.network?.uiTestOverrideMobileData(null); app.state?.deleteWeights(e4b) }
            LocalModelDownloader.hostOverride = null
            LocalModelDownloader.shaOverride = emptyMap()
        }
    }

    /** G2 (part): a download keeps going while the app is in the background (a foreground service holds it), and is still going on return. */
    @Test fun aDownloadKeepsGoingWhileTheAppIsAway() {
        val app = (ctx.applicationContext as TeemoonApplication)
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model",
            UiTestSeeding.EXTRA_MODEL_HOST to server.rootUrl, UiTestSeeding.EXTRA_MOBILE_DATA to false)
        try {
            tapTag("chat.whereChip"); tap("phone")
            tap(e4b.displayName.lowercase())
            compose.waitUntil(20_000) { (app.state!!.localDownloads.jobs.value[e4b.id]?.fraction ?: 0.0) > 0.0 }
            val before = app.state!!.localDownloads.jobs.value[e4b.id]!!.fraction
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(8_000)
            val away = app.state!!.localDownloads.jobs.value[e4b.id]?.fraction ?: 0.0
            assertTrue("the download stopped while the app was away ($before → $away)", away > before)
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            compose.waitUntil(10_000) { tagExists("chat.composer") }
            assertTrue("the download did not survive the trip", app.state!!.localDownloads.jobs.value[e4b.id] != null)
            assertEquals("the trip restarted the download", 1, server.modelRequests.get())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.state?.localDownloads?.cancel(e4b.id); app.state?.network?.uiTestOverrideMobileData(null); app.state?.deleteWeights(e4b) }
            LocalModelDownloader.hostOverride = null
        }
    }

    /**
     * G1: with another phone model already a place, downloading a second one selects IT while it arrives — the chip
     * names it with its progress — and the first keeps its place. Every phone model shares the endpoint "on-device";
     * the store folded on it, overwrote the first and selected an id no list held: "choose where" (Pixel, 2026-10-05).
     */
    @Test fun aSecondPhoneModelDownloadIsSelectedAndTheFirstKeepsItsPlace() {
        val app = (ctx.applicationContext as TeemoonApplication)
        val e2b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E2B" }
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model",
            UiTestSeeding.EXTRA_MODEL_HOST to server.rootUrl, UiTestSeeding.EXTRA_MOBILE_DATA to false)
        var first: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { first = app.state!!.providerStore.addProvider(Provider.local(e2b)) }
        try {
            tapTag("chat.whereChip"); tap("phone")
            tap(e4b.displayName.lowercase())
            compose.waitUntil(20_000) { (app.state!!.localDownloads.jobs.value[e4b.id]?.fraction ?: 0.0) > 0.0 }
            val providers = app.state!!.providers.value
            assertTrue("the first phone model lost its place: ${providers.map { it.name }}", providers.any { it.id == first && it.localModelID == e2b.id })
            assertTrue("the arriving model is not the selected place", providers.any { it.id == app.state!!.selectedId.value && it.localModelID == e4b.id })
            compose.waitUntil(10_000) { exists("gemma 4 e4b") }
            assertFalse("the chip says choose where while the model arrives", exists("choose where"))
        } finally {
            // the E2B PLACE this test added goes, never its weights: the dev app's real bundle is what the goldens and
            // the weight journeys run on (deleting it here broke six of them on the next run, 2026-10-05)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.state?.localDownloads?.cancel(e4b.id); app.state?.network?.uiTestOverrideMobileData(null); app.state?.deleteWeights(e4b); first?.let { app.state?.remove(it) } }
            LocalModelDownloader.hostOverride = null
        }
    }

    @Test fun onMobileDataADownloadAsksAndWaitingForWifiParksIt() {
        val app = (ctx.applicationContext as TeemoonApplication)
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model",
            UiTestSeeding.EXTRA_MODEL_HOST to server.rootUrl, UiTestSeeding.EXTRA_MOBILE_DATA to true)
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        try {
            tapTag("chat.whereChip"); tap("phone")
            tap(e4b.displayName.lowercase())
            compose.waitUntil(10_000) { exists("download over mobile data?") }
            tap("wait for wi-fi")
            compose.waitUntil(10_000) { app.state!!.localDownloads.jobs.value[e4b.id]?.parked == true }
            Thread.sleep(1_500)
            assertEquals("no bytes on mobile data", 0, server.modelRequests.get())
            tapTag("chat.whereChip")
            compose.waitUntil(10_000) { exists("waiting for wi-fi") }
            // Wi-fi back: the parked job starts fetching.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.state!!.network.uiTestOverrideMobileData(false) }
            compose.waitUntil(20_000) { server.modelRequests.get() >= 1 }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                app.state?.network?.uiTestOverrideMobileData(null)
                app.state?.deleteWeights(e4b)
            }
            LocalModelDownloader.hostOverride = null
        }
    }

    // MARK: C9 — config problems are surfaced, never read as a first run

    /** C9: an unreadable providers.json is MOVED aside (its bytes kept) and the where sheet says so — no first-run hero. */
    @Test fun anUnreadableConfigIsKeptAndSurfacedNotReadAsAFirstRun() {
        File(ctx.filesDir, "providers.corrupt.json").delete()
        launch(UiTestSeeding.EXTRA_CONFIG to "corrupt")
        val kept = File(ctx.filesDir, "providers.corrupt.json")
        assertTrue("the unreadable file was not kept beside the config", kept.isFile && kept.readText().contains("this is not json"))
        tapTag("chat.whereChip")
        compose.waitUntil(10_000) { exists("couldn't read your setups") }
        assertTrue("the problem banner does not say the file was kept", exists("not deleted") || exists("kept a copy"))
        assertFalse("a load failure was presented as a first run", exists("start here"))
    }

    /** C9: one connection saved twice is merged at launch, and the where sheet says what it tidied; "ok" clears it. */
    @Test fun aDuplicateConnectionIsMergedAndAnnounced() {
        launch(UiTestSeeding.EXTRA_CONFIG to "duplicates")
        assertEquals("the duplicate was not merged", 1, appState.providers.value.count { it.endpoint == Provider.fireworks.endpoint })
        tapTag("chat.whereChip")
        compose.waitUntil(10_000) { exists("tidied up") }
        assertTrue("the notice does not say what was merged", exists("saved more than once"))
        tap("ok")
        compose.waitUntil(5_000) { !exists("tidied up") }
    }

    /** C9: offline, the get section says why nothing can be added rather than offering adds that will fail. */
    @Test fun offlineTheWhereSheetSaysWhyNothingCanBeAdded() {
        launch(UiTestSeeding.EXTRA_PRESETS to "fireworks", UiTestSeeding.EXTRA_OFFLINE to true)   // a place, so the sheet is past first run
        tapTag("chat.whereChip")
        compose.waitUntil(10_000) { exists("connect to a network to add cloud keys or reach home servers.") }
        assertFalse("offline, the sheet still offers a home server", exists("connect a computer"))
    }

    /**
     * C3: "recently used" lists what has ANSWERED — nothing before the first turn, the model after — and a tap on it
     * selects it and closes the sheet back to the chat (WhereSheetView.selectRecent: activate, then dismiss). Android
     * activated and left the sheet up (reported on the Pixel, 2026-10-06).
     */
    @Test fun aModelThatAnsweredIsRecentlyUsed() {
        launch(*local())
        tapTag("chat.whereChip"); compose.waitUntil(10_000) { exists("ready now") }
        assertFalse("recently used before anything answered", exists("recently used"))
        tap("close")
        send("hello a"); waitForReply(); compose.waitUntil(30_000) { !tagExists("chat.stop") }
        tapTag("chat.whereChip"); compose.waitUntil(10_000) { exists("ready now") }
        compose.waitUntil(5_000) { exists("recently used") }
        // the row UNDER the "recently used" header (the ready-now row above it names the same model)
        val header = compose.onAllNodes(hasText("recently used")).fetchSemanticsNodes().first().boundsInRoot.bottom
        val rows = compose.onAllNodes(hasText("fake-model", substring = true))
        // the NEAREST node under the header: the chat's where chip behind the sheet names it too, lower down
        val nodes = rows.fetchSemanticsNodes()
        val below = nodes.indices.filter { nodes[it].boundsInRoot.top > header && nodes[it].boundsInRoot.height > 0 }.minByOrNull { nodes[it].boundsInRoot.top } ?: -1
        assertTrue("no row under recently used", below >= 0)
        rows[below].performClick()
        compose.waitUntil(5_000) { !exists("ready now") }
        assertTrue("picking a recent model left the chat hidden", tagExists("chat.composer"))
    }

    /** C4: a cloud model unequipped from where leaves the list, and browsing its cloud puts it back. */
    @Test fun anUnequippedCloudModelIsReEquippedFromBrowse() {
        keyFile(".NEAR_AI_API_KEY", "uitest-near-key")
        launch(*local(UiTestSeeding.EXTRA_PRESETS to "nearai"))
        tapTag("chat.whereChip"); compose.waitUntil(10_000) { exists(NEAR_AI_ROW) }
        compose.onAllNodes(hasText(NEAR_AI_ROW, substring = true))[countOf(NEAR_AI_ROW) - 1].performTouchInput { longClick() }; Thread.sleep(600)
        tapLast("unequip")
        compose.waitUntil(10_000) { !exists(NEAR_AI_ROW) || countOf(NEAR_AI_ROW) == compose.onAllNodesWithTag("chat.whereChip").fetchSemanticsNodes().size }
        val nearAI = appState.providers.value.first { it.isNearAI }
        val unequipped = Provider.nearAI.model
        assertFalse("the unequipped model is still equipped", unequipped in nearAI.equipped)
        tap("cloud"); tapTag("where.preset.near.ai")
        compose.waitUntil(20_000) { exists("search models") }
        val search = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
        compose.onAllNodes(hasSetTextAction())[search].performTextInput(NEAR_AI_ROW); Thread.sleep(1_000)
        back()   // the keyboard covers the rows
        // the browser names it by display name ("glm 5.3 flash"), the ready row by id ("glm-5.3-flash")
        val shown = NEAR_AI_ROW.replace('-', ' ')
        val rows = compose.onAllNodes(hasText(shown, substring = true, ignoreCase = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0 && it.config.getOrNull(SemanticsProperties.EditableText) == null }
        assertTrue("the browser does not offer the unequipped model ($shown)", rows.isNotEmpty())
        val picked = rows.first().config[SemanticsProperties.Text].first().text
        compose.onAllNodes(hasText(picked))[0].performClick(); Thread.sleep(1_500)
        compose.waitUntil(10_000) { !exists("search models") }
        assertTrue("the re-equipped model is not equipped again", unequipped in appState.providers.value.first { it.isNearAI }.equipped)
    }

    /** D5: with a key the web chip toggles in place, and the next turn does — then does not — ask Brave. */
    @Test fun theWebChipWithAKeyTogglesWhetherTheNextTurnSearches() {
        keyFile(".BRAVE_API_KEY", "uitest-brave-key")
        server.close(); server = FakeSseServer(toolRound = true)
        launch(*local(UiTestSeeding.EXTRA_GROUNDING to true, UiTestSeeding.EXTRA_BRAVE_ENDPOINT to "${server.baseUrl}/llm/context"))
        keepingSettings {
            send("what is the weather in paris")
            compose.waitUntil(30_000) { server.bodies.size >= 2 && !tagExists("chat.stop") && exists("Paragraph 5:") }
            val searched = server.searchRequests.get()
            assertTrue("search on, yet brave was never asked", searched >= 1)
            tapTag("chat.webSearchChip")
            assertFalse("a keyed chip opened settings instead of toggling", exists("already have a key?"))
            assertFalse("the chip did not turn search off", appState.groundingEnabled.value)
            send("and in rome")
            compose.waitUntil(30_000) { server.bodies.size >= 3 && !tagExists("chat.stop") }
            assertEquals("search off, yet brave was asked", searched, server.searchRequests.get())
            appState.setBraveKey(""); appState.setGroundingEnabled(false)
        }
    }

    /** B6: a markdown reply's link opens the browser at the link's host. */
    @Test fun aMarkdownReplysLinkOpensTheBrowser() {
        server.close(); server = FakeSseServer(reply = "See [the chart](https://example.org/chart) for more.")
        launch(*local())
        send("a link please")
        compose.waitUntil(30_000) { exists("for more") && !tagExists("chat.stop") }
        linkOpens("the chart", "example.org")
    }

    /** B11: a llama.cpp-style GGUF path is shown as the model's name — no directories, no extension. */
    @Test fun aGgufPathIsShownAsAName() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "/Users/x/.cache/models/Qwen2.5-7B-Instruct-Q4_K_M.gguf")
        compose.waitUntil(10_000) { exists("on your own machine") }
        assertFalse("a path leaked into the chat's header or chip", exists("/Users/") || exists(".cache/models"))
        assertFalse("a file extension leaked into the chat's header or chip", exists(".gguf"))
        assertTrue("the model is not named", compose.onAllNodes(hasText("qwen2.5-7b", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty())
    }

    /** D6: on a provider that searches by itself (Brave Answers), search settings says so instead of asking for a key. */
    @Test fun searchSettingsSaysWhenTheProviderSearchesItself() {
        keyFile(".BRAVE_ANSWERS_API_KEY", "uitest-brave-answers-key")
        launch(UiTestSeeding.EXTRA_PRESETS to "braveanswers", UiTestSeeding.EXTRA_NO_BRAVE_KEY to true)
        tap("settings"); tapLast("search")
        compose.waitUntil(5_000) { exists("search is built in here") }
        assertFalse("built-in search still asks for a brave key", exists("already have a key?"))
    }

    /** C8: a pull shows its progress on its where row while it runs, and the row's cancel ends it — the model never lands. */
    @Test fun aPullShowsProgressAndStopEndsIt() {
        server.close(); server = FakeSseServer(ollama = true)
        server.ollamaPullSteps = 150; server.ollamaPullStepMs = 200L   // ~30 s: long enough to watch and stop on a phone
        launch(*local())
        openHome()
        tap("add a model to")
        compose.waitUntil(10_000) { exists("download a model") }
        typeLink("slowmodel")
        tap("download")
        // the sheet leaves at the start (OllamaDownloadScreen.startPull); the where row carries the pull from there —
        // "downloading N%" beside its cancel, as on iOS
        if (!exists("ready now")) openHome()
        compose.waitUntil(10_000) { exists("downloading") }
        assertTrue("the running pull shows no percentage", Regex("downloading \\d+%").containsMatchIn(compose.onAllNodes(hasText("downloading", substring = true)).fetchSemanticsNodes().joinToString { it.config.toString() }))
        tapLast(A11yLabels.CANCEL_DOWNLOAD)
        compose.waitUntil(10_000) { !exists("downloading") }
        Thread.sleep(31_000)   // past the end the pull would have reached
        assertFalse("a stopped pull landed on the machine anyway", "slowmodel" in server.ollamaModels)
        // The row stays until the sheet next reads the machine (iOS: "cancel the server's pull and leave the provider
        // alone"), and that read drops it — the machine's list is the equipped list (syncHomeEquipped).
        openHome()
        compose.waitUntil(15_000) { appState.providers.value.none { p -> p.equipped.any { it.contains("slowmodel") } } }
    }

    /**
     * E5: in every trust state the title opens the sheet; where it has rungs, everyday and expert each scroll to their
     * LAST line — nothing stranded under the bottom edge (a sheet whose end sat under the home bar shipped once).
     */
    @Test fun theTitleOpensTheLadderInEveryStateAndEachRungScrollsToItsEnd() {
        val dm = ctx.resources.displayMetrics
        val bottomLimit = dm.heightPixels - (24 * dm.density)   // the gesture bar's band
        fun lastTextBottom(): Float = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0 && it.boundsInRoot.top < dm.heightPixels }.maxOfOrNull { it.boundsInRoot.bottom } ?: 0f
        // to the END: the expert rung carries the live audit findings and runs to many screens — swipe until the
        // bottom-most line stops changing (a fixed count stopped short on iOS)
        fun bottomLine(): String = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0 && it.boundsInRoot.top < dm.heightPixels }.maxByOrNull { it.boundsInRoot.bottom }
            ?.let { n -> "${n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }}@${n.boundsInRoot.bottom}" } ?: ""
        // Swipes run down the sheet's left margin, outside the content's 20dp inset: from mid-screen they landed on the
        // expert rung's verify-script box, which scrolls on its own, so the SCRIPT scrolled and the page stopped with
        // the box running off the bottom — a red that came and went with a few pixels of layout (2026-10-13).
        val gutterX = 14 * dm.density
        fun scrollToEnd() {
            var previous = ""
            for (i in 0 until 40) {
                compose.onRoot().performTouchInput { swipe(start = androidx.compose.ui.geometry.Offset(gutterX, height * 0.85f),
                    end = androidx.compose.ui.geometry.Offset(gutterX, height * 0.25f), durationMillis = 200) }; Thread.sleep(300)
                val now = bottomLine(); if (now == previous) break; previous = now
            }
            Thread.sleep(600)
        }
        for (state in listOf("ok", "verifying", "hardFailure", "mismatch", "none", "degraded")) {
            nearAI(state)
            tapTag("chat.titleBlock")
            compose.waitUntil(10_000) { exists("who can read this?") || exists("nothing leaves") || exists("not end-to-end") || exists("verif") }
            val rungs = if (exists("expert")) listOf("everyday", "expert") else listOf(null)
            for (rung in rungs) {
                rung?.let { tapLast(it) }
                scrollToEnd()
                val bottom = lastTextBottom()
                assertTrue("$state/${rung ?: "sheet"}: the last line ends at $bottom, under the bottom edge ($bottomLimit)", bottom <= bottomLimit)
            }
            back()
        }
    }

    /**
     * K7: scrolling up while a reply streams — and while it is still thinking — leaves the app answering touches at once,
     * and the transcript never goes blank under the finger.
     */
    @Test fun scrollingUpMidStreamLeavesTheAppResponsiveAndTheTranscriptDrawn() {
        val dm = ctx.resources.displayMetrics
        fun anyTextOnScreen() = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.boundsInRoot.height > 0 && it.boundsInRoot.top > dm.heightPixels * 0.15f && it.boundsInRoot.bottom < dm.heightPixels * 0.8f }
        for (thinking in listOf(false, true)) {
            // a word every 150 ms: since the stream's state lands on the main thread (ChatScreen collects on Main), a
            // word every 40 ms left the v1 Compose rule no idle frame to sync on, and every lookup mid-stream timed out
            server.close(); server = FakeSseServer(lines = 20, delayMs = 150, thinking = thinking)
            launch(*local())
            send("a long one, please")
            compose.waitUntil(15_000) { tagExists("chat.stop") && exists("Paragraph 3:") }
            repeat(4) {
                compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.7f, durationMillis = 250) }
                Thread.sleep(150)
                assertTrue("thinking=$thinking: the transcript went blank under the finger", anyTextOnScreen())
            }
            // responsive: a tap opens the chats sheet within a beat while the stream is still running
            assertTrue("thinking=$thinking: the stream ended before the check — make it longer", tagExists("chat.stop"))
            val t0 = System.currentTimeMillis()
            compose.onAllNodes(hasContentDescription("chats"))[0].performClick()
            // Compose's sync drives the frames under the v1 rule (no sync, no frame: polling the accessibility tree saw the
            // sheet never open), and mid-stream it also waits on the stream's own recompositions — 3 s passed alone and
            // failed in a full run. 5 s still fails a hung app.
            runCatching { compose.waitUntil(5_000) { exists("no chats yet") || exists("today") || exists("TODAY") } }.onFailure {
                // what was on screen instead: the run's evidence (pulled from files/k7-*.png)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { b ->
                    File(ctx.getExternalFilesDir(null), "k7-thinking-$thinking.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
                throw it
            }
            assertTrue("thinking=$thinking: the app took ${System.currentTimeMillis() - t0} ms to answer a tap mid-stream", System.currentTimeMillis() - t0 < 5_000)
            tap("close")
            compose.waitUntil(60_000) { !tagExists("chat.stop") }
        }
    }
}
