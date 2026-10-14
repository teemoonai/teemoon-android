package ai.teemoon.app

// android-only journeys: real on-device gemma turns driven through the UI and watched on screen frame by frame. The
// JVM suite fed the thinking opener in one piece and passed; on the Pixel 10 Pro (Tensor bundle, 2026-10-03) the opener
// streamed in pieces and "<|channel>" showed in the bubble while gemma thought. Nothing ran gemma through the UI
// before these. They need weights on a PHONE (the Tensor bundle is the one that streams channel markup); they skip on
// the AVD and without weights.

import android.content.Intent
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties

@RunWith(AndroidJUnit4::class)
class OnDeviceThinkingJourneyTest {
    @get:Rule val compose = emptyComposeRule()
    @get:Rule val pageSizeDialog = PageSizeDialogRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    @After fun close() { scenario?.close() }

    /** Markup that must never reach a person: thinking openers and closers (whole or partial), and tool-call syntax. */
    private val markup = Regex("<\\|[^\\n]{0,30}|<channel\\|>|</?think>?|<th(?:i(?:n(?:k)?)?)?$|</?tool_call>?|\\{\"name\"\\s*:\\s*\"web_search\"", RegexOption.MULTILINE)

    /** Every window's text, read through accessibility — what a person sees, not what the compose tree promises. */
    private fun screenText(): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { out.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        runCatching { ins.uiAutomation.windows.forEach { walk(it.root) } }
        if (out.isEmpty()) walk(ins.uiAutomation.rootInActiveWindow)
        return out.toString()
    }

    private fun tagExists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** waitUntil that says what WAS on screen when it gives up — a bare timeout explains nothing on a phone. */
    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (runCatching(cond).getOrDefault(false)) return; Thread.sleep(100) }
        throw AssertionError("timed out waiting for $what; on screen:\n${screenText().take(700)}")
    }
    private val streaming get() = tagExists("chat.stop")

    private fun launchOnDevice(vararg extras: Pair<String, Any>) {
        val weights = File(ctx.filesDir, "LocalModels").walkTopDown().any { it.name.endsWith(".litertlm") && !it.name.endsWith(".part") }
        assumeTrue("no on-device weights here", weights)
        // The AVD runs LiteRT on CPU with the GENERAL bundle (~4 tok/s, a 75 s cold load): it neither finishes a thinking
        // turn in time nor emits the Tensor bundle's channel markup. These journeys are for a phone (or the remote lab).
        assumeTrue("an emulator cannot run these journeys meaningfully", !Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("SDK"))
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).apply {
            // A cleared task and an empty chat store, as every journey starts: on the phone the app otherwise reopened the
            // owner's most recent thread over the test's, and the test typed into a window nobody could see.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_ON_DEVICE, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            extras.forEach { (k, v) -> when (v) { is String -> putExtra(k, v); is Boolean -> putExtra(k, v) } }
        })
        waitFor("the composer", 30_000) { tagExists("chat.composer") }
        Thread.sleep(2_500)   // the seed runs off the main thread
    }

    /** The composer's own text — empty once a message has gone. */
    private fun composerText(): String = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Types and sends, and CONFIRMS it left the composer: on the phone a send tap landing mid-IME-animation was lost. */
    private fun send(text: String) {
        compose.onAllNodes(hasTestTag("chat.composer"))[0].performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(text)
        Thread.sleep(600)
        repeat(3) {
            compose.onAllNodes(hasTestTag("chat.send"))[0].performClick()
            val gone = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < gone) { if (composerText().isEmpty()) return; Thread.sleep(100) }
        }
        throw AssertionError("the message never left the composer; on screen:\n${screenText().take(500)}")
    }

    /** Samples the screen every 100 ms until [done] holds for a second (or [timeoutMs]): every markup match seen, and whether the thinking chip showed. */
    private fun watch(timeoutMs: Long = 240_000, done: (String) -> Boolean): Pair<Set<String>, Boolean> {
        val leaks = LinkedHashSet<String>(); var sawThinking = false; var settled = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val t = screenText()
            markup.findAll(t).forEach { leaks += it.value }
            if (t.contains("thinking")) sawThinking = true
            if (done(t)) { if (++settled >= 10) break } else settled = 0
            Thread.sleep(100)
        }
        return leaks to sawThinking
    }

    /** "221" a THIRD time: the question is on screen twice already (its bubble and the chat's title). */
    private fun answered221(t: String) = !streaming && !t.contains("thinking") && Regex("221").findAll(t).count() >= 3

    @Test fun aThinkingReplyNeverShowsItsMarkupAndEndsWithAnAnswer() {
        launchOnDevice()
        send("Think step by step: is 221 a prime number? Answer in one sentence.")
        val (leaks, sawThinking) = watch(done = ::answered221)
        assertTrue("markup reached the screen while gemma thought: $leaks", leaks.isEmpty())
        val final = screenText()
        assertTrue("the turn never finished with an answer on screen:\n${final.take(600)}", answered221(final))
        assertTrue("the thinking chip never showed — did this turn think at all?", sawThinking)
    }

    /**
     * Gemma with web search: the model's tool call runs on the device (LiteRTToolBridge) against a fake Brave, the reply
     * carries the source, and neither the tool-call syntax nor thinking markup ever reaches the screen.
     */
    @Test fun aSearchOnTheDeviceCarriesItsSourceAndShowsNoToolSyntax() { FakeSseServer().use { server ->
        File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }.let { File(it, ".BRAVE_API_KEY").apply { delete() }.writeText("uitest-brave-key") }
        launchOnDevice(UiTestSeeding.EXTRA_GROUNDING to true, UiTestSeeding.EXTRA_BRAVE_ENDPOINT to "${server.baseUrl}/llm/context")
        send("What is the weather in Paris today? Search the web.")
        val (leaks, _) = watch { t -> !streaming && !t.contains("thinking") && !t.contains("searching") && tagExists("chat.sourcesChip") }
        assertTrue("tool or thinking markup reached the screen: $leaks", leaks.isEmpty())
        assertTrue("gemma never searched (fake Brave saw ${server.searchRequests.get()} requests)", server.searchRequests.get() > 0)
        assertTrue("the reply carries no source:\n${screenText().take(600)}", tagExists("chat.sourcesChip"))
    } }

    /** Stop while gemma is still thinking: the turn ends promptly, no markup is left behind, and the next turn answers. */
    @Test fun stoppingMidThoughtEndsTheTurnAndTheNextOneAnswers() {
        launchOnDevice()
        send("Think very carefully, step by step, about every prime below 200, then tell me how many there are.")
        waitFor("gemma to be thinking", 120_000) { screenText().contains("thinking") && streaming }
        Thread.sleep(1_500)   // a little reasoning in flight
        compose.onAllNodes(hasTestTag("chat.stop"))[0].performClick()
        waitFor("the stop to end the turn", 10_000) { !streaming }
        val afterStop = screenText()
        assertTrue("markup left on screen after stop: ${markup.findAll(afterStop).map { it.value }.toList()}", !markup.containsMatchIn(afterStop))
        send("Think step by step: is 221 a prime number? Answer in one sentence.")
        val (leaks, _) = watch(done = ::answered221)
        assertTrue("markup on the turn after a stop: $leaks", leaks.isEmpty())
        assertTrue("the engine did not answer after a stop:\n${screenText().take(600)}", answered221(screenText()))
    }

    /**
     * A turn sent and then backgrounded keeps running (TurnService holds the foreground) and is finished, cleanly, when
     * the user comes back — the decode is not dropped under them, and nothing half-parsed is left in the bubble.
     */
    @Test fun aTurnThatGoesToTheBackgroundFinishesWhileAway() {
        launchOnDevice()
        send("Think step by step: is 221 a prime number? Answer in one sentence.")
        waitFor("the turn to start streaming", 120_000) { streaming }
        ins.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(45_000)
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        waitFor("the chat after coming back", 15_000) { tagExists("chat.composer") }
        val (leaks, _) = watch(timeoutMs = 120_000, done = ::answered221)
        assertTrue("markup on screen after coming back: $leaks", leaks.isEmpty())
        assertTrue("the backgrounded turn did not finish with an answer:\n${screenText().take(600)}", answered221(screenText()))
    }

    /**
     * Opens the where sheet and CONFIRMS it opened: with the keyboard up (an empty chat focuses the composer, and so
     * does a finished turn) the phone dropped the chip's tap — the sheet never rose and the journey read the chat.
     */
    private fun openWhere() {
        repeat(3) {
            if (ins.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
                ins.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK).also { Thread.sleep(600) }
            compose.onAllNodes(hasTestTag("chat.whereChip"))[0].performClick()
            val until = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < until) { if (Regex("(?m)^where$").containsMatchIn(screenText())) return; Thread.sleep(150) }
        }
        throw AssertionError("the where sheet never opened; on screen:\n${screenText().take(500)}")
    }

    /** Clicks the first on-screen node whose text matches [label], in any window — the system's permission dialog included. */
    private fun clickAnywhere(label: String): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.text?.toString()?.equals(label, ignoreCase = true) == true) return n
            for (k in 0 until n.childCount) find(n.getChild(k))?.let { return it }
            return null
        }
        val node = ins.uiAutomation.windows.firstNotNullOfOrNull { find(it.root) } ?: return false
        var c: AccessibilityNodeInfo? = node
        while (c != null && !c.isClickable) c = c.parent
        return (c ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** C10: the phone's model reads "warm" on the where sheet once its engine is loaded — as a home server does, as iOS does. */
    @Test fun thePhonesModelReadsWarmOnceItHasAnsweredAndColdAfterTheAppWasAway() {
        launchOnDevice()
        send("Say hello in three words.")
        waitFor("the reply", 240_000) { !streaming && Regex("(?i)hello").findAll(screenText()).count() >= 3 }
        openWhere()
        waitFor("the phone row to read warm", 15_000) { screenText().contains("warm") }
        // C10's other half: leaving the app hands the memory back — the system sends TRIM_MEMORY_UI_HIDDEN and
        // TeemoonApplication evicts the engine — and the row says so: cold, as a home server's unloaded model does. The
        // trim is delivered here as the system would; pressing home took the test's own activity out from under it.
        ins.runOnMainSync { (ctx.applicationContext as TeemoonApplication).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
        Thread.sleep(1_500)
        ins.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(800)
        openWhere()
        waitFor("the phone row to read cold after the engine was evicted", 15_000) { screenText().contains("cold") }
    }

    /**
     * E9: the phone's model has the honest on-device sheet — "nothing leaves this phone", no everyday/expert rungs. Here
     * and not in UserJourneysTest: selecting the phone's model warms it (~5 GB resident on the CPU backend), and in a
     * shared test process that starved every later journey's first-run card of the memory it checks for.
     */
    @Test fun thePhonesModelSheetSaysNothingLeavesThisPhone() {
        launchOnDevice()
        compose.onAllNodes(hasTestTag("chat.titleBlock"))[0].performClick()
        waitFor("the on-device sheet", 10_000) { screenText().contains("nothing leaves this phone") }
        assertTrue("an on-device sheet offers the expert rung", !screenText().contains("expert"))
    }

    /** A6: first run on this phone: "start here" names the device's model and, with it on disk, offers "use" — not a download. */
    @Test fun firstRunOnThePhoneOffersTheModelAlreadyOnIt() {
        val weights = File(ctx.filesDir, "LocalModels").walkTopDown().any { it.name.endsWith(".litertlm") }
        assumeTrue("no on-device weights here", weights)
        assumeTrue("an emulator has no Tensor bundle to name", !Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("SDK"))
        // A real first run has no engine loaded. After the other journeys one is (3 GB, in this process), the free memory
        // reads low, and "start here" stops recommending the model — a property of the run order, not of a first run.
        (ctx.applicationContext as TeemoonApplication).state?.evictEngines()
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)   // no places at all: a first run
        })
        waitFor("the composer", 30_000) { tagExists("chat.composer") }; Thread.sleep(2_500)
        openWhere()
        waitFor("start here", 15_000) { screenText().contains("start here") }
        val t = screenText()
        assertTrue("start here does not offer the model on disk: ${t.take(400)}", t.contains("use gemma 4 e2b") && t.contains("already downloaded"))
        assertTrue("start here offered a download for a model already on the phone", !t.contains("download · "))
    }

    /** G9: keyless, on the phone model: a time-sensitive question raises the web-search offer card instead of a guess. */
    @Test fun aKeylessTimeSensitiveQuestionRaisesTheSearchOffer() {
        launchOnDevice(UiTestSeeding.EXTRA_NO_BRAVE_KEY to true)
        send("What is the weather in Paris right now?")
        waitFor("the turn to end", 240_000) { !streaming && !screenText().contains("thinking") }
        waitFor("the web-search offer", 10_000) { tagExists("chat.webOffer") }
    }

    /** I1: a turn that finishes while the app is away posts "your answer is ready", and nothing is lost. */
    @Test fun aTurnFinishedWhileAwayPostsItsNotification() {
        launchOnDevice()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        send("Think step by step: is 221 a prime number? Answer in one sentence.")
        waitFor("the turn to start streaming", 120_000) { streaming }
        ins.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val deadline = System.currentTimeMillis() + 240_000
        var posted: Notification? = null
        while (System.currentTimeMillis() < deadline && posted == null) {
            posted = nm.activeNotifications.map { it.notification }.firstOrNull { n ->
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                title.isNotEmpty() && title != "thinking…" }
            Thread.sleep(500)
        }
        assertTrue("no notification said the answer was ready", posted != null)
        val title = posted!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue("the notification was a failure: $title", title != "that turn didn't finish")
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        waitFor("the answer after coming back", 30_000) { answered221(screenText()) }
    }

    /** G7: with search on, a plain question on the phone model answers WITHOUT searching (the fake Brave sees nothing). */
    @Test fun aPlainQuestionOnThePhoneDoesNotSearch() { FakeSseServer().use { server ->
        File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }.let { File(it, ".BRAVE_API_KEY").apply { delete() }.writeText("uitest-brave-key") }
        launchOnDevice(UiTestSeeding.EXTRA_GROUNDING to true, UiTestSeeding.EXTRA_BRAVE_ENDPOINT to "${server.baseUrl}/llm/context")
        send("Write a haiku about the sea.")
        waitFor("the haiku", 240_000) { !streaming && !screenText().contains("thinking") && screenText().lines().count { it.isNotBlank() } > 6 }
        assertTrue("a haiku searched the web (${server.searchRequests.get()} requests)", server.searchRequests.get() == 0)
    } }

    /** D4: a dismissed web-search offer goes, and the same thread is not offered again (the keyless tool stops attaching there). */
    @Test fun aDismissedSearchOfferIsNotOfferedAgainInThatThread() {
        launchOnDevice(UiTestSeeding.EXTRA_NO_BRAVE_KEY to true)
        send("What is the weather in Paris right now?")
        waitFor("the web-search offer", 240_000) { !streaming && tagExists("chat.webOffer") }
        compose.onAllNodes(hasTestTag("chat.webOffer.dismiss"))[0].performClick(); Thread.sleep(800)
        assertTrue("the dismissed card stayed", !tagExists("chat.webOffer"))
        send("And what is the weather in Tokyo right now?")
        waitFor("the second turn to end", 240_000) { !streaming && !screenText().contains("thinking") }
        Thread.sleep(1_500)
        assertTrue("a dismissed thread was offered search again", !tagExists("chat.webOffer"))
    }
}
