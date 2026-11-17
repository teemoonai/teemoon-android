package ai.teemoon.app

// android-only: the two 1.0.2 presets over the REAL wire, with real keys. UserJourneysTest walks the everyday paths
// over a fake server and stays offline on purpose; this class is the part a fake cannot stand in for — a key the host
// accepts, a list it actually answers, a reply from a model it actually runs. Added 2026-09-22, after adding the keys on
// a Pixel found four defects no offline suite could (settings probed every cloud generically; a key field that did not
// tell the keyboard it held a secret; OpenRouter defaulting to a closed model; a store door never wired).
//
// OPT-IN AND OFFLINE-SAFE: every case SKIPS (Assume) unless its host key file has been staged into the app, which only
// tools/live_cloud_suite.sh does. Costs a few cents of OpenRouter credit and a few NVIDIA free-tier requests per run.

import android.content.Intent
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot

@RunWith(AndroidJUnit4::class)
class LiveCloudTurnsTest {
    @get:Rule(order = 0) val compose = emptyComposeRule()
    /** What was on screen when a live case failed: the semantics tree in logcat (LIVE) and a screenshot under files/journeys. */
    @get:Rule(order = 1) val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("LIVE") }
            runCatching {
                val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val dir = File(ctx.getExternalFilesDir(null), "journeys").apply { mkdirs() }
                File(dir, "live-${description.methodName}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun staged(file: String) = File(File(ctx.filesDir, UiTestSeeding.KEY_DIR), file).let { it.isFile && it.readText().isNotBlank() }
    private fun exists(label: String) = compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun tap(label: String) { compose.waitUntil(20_000) { exists(label) }; compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label))[0].performClick(); compose.waitForIdle(); Thread.sleep(600) }
    private fun tapTag(tag: String) { compose.waitUntil(20_000) { tagExists(tag) }; compose.onAllNodesWithTag(tag)[0].let { runCatching { it.performScrollTo() }; it.performClick() }; compose.waitForIdle(); Thread.sleep(600) }
    private fun launch(presets: String, vararg extras: Pair<String, Any>) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            if (presets.isNotEmpty()) putExtra(UiTestSeeding.EXTRA_PRESETS, presets)   // the first preset is selected, keyed from its staged host file
            extras.forEach { (k, v) -> when (v) { is String -> putExtra(k, v); is Boolean -> putExtra(k, v) } }
        }
        scenario?.close(); scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }
    }

    /**
     * Sends a question whose answer is not in the question, so the wait cannot be satisfied by the user's own bubble.
     * Hosted reasoning models think before the first visible token — nemotron took 5.8 s on the Pixel — so the wait is long.
     */
    private fun askAndAwaitAnswer() {
        fun ask() {
            tapTag("chat.composer"); compose.onAllNodes(hasSetTextAction())[0].performTextInput("what is 17 plus 25? reply with the number only")
            Thread.sleep(300); tapTag("chat.send")
            compose.waitUntil(120_000) { exists("42") || exists("sent an empty answer") || exists("couldn't") }
        }
        ask()
        // A hosted reasoning model sometimes answers with an empty completion — NVIDIA's nemotron did on the fourth
        // run of 2026-09-22, and the app showed exactly what it should: an error card saying "ask again to retry".
        // That is the host's blip, not the app's; the case asks again ONCE, as the card tells a user to, and a second
        // miss fails immediately naming the card rather than timing out.
        if (!exists("42") && exists("sent an empty answer")) ask()
        assertTrue("no answer: " + compose.onAllNodes(hasText("NVIDIA", substring = true) or hasText("OpenRouter", substring = true))
            .fetchSemanticsNodes().joinToString { it.config.toString().take(160) }, exists("42"))
    }

    /**
     * The cache gate's second provider (pm, adopted by the maintainer 2026-10-22): two fresh chats × four normal sends, the cache
     * figure read from each reply's card. A pass is any send 2–4 above 0%. `-e cache_preset grok|openrouter` and
     * `-e cache_model` pick the setup (default xAI grok-4.3, which routes a chat to one server by x-grok-conv-id).
     */
    @Test fun theSecondProviderCachesAChatsNextSends() {
        val args = InstrumentationRegistry.getArguments()
        val preset = args.getString("cache_preset") ?: "grok"
        val model = args.getString("cache_model") ?: "grok-4.3"
        assumeTrue("no key staged for $preset — run tools/live_cloud_suite.sh", UiTestSeeding.keyFileNames(preset).any { staged(it) })
        launch(preset, UiTestSeeding.EXTRA_PRESET_MODEL to model, UiTestSeeding.EXTRA_DEVELOPER to true)
        val questions = listOf(listOf("Why is the sky blue? One sentence.", "And why are sunsets red? One sentence.", "What colour is the sky on Mars? One sentence.", "And at sunset there? One sentence."),
            listOf("What is the speed of light? One sentence.", "And the speed of sound? One sentence.", "Which is faster, and by how much? One sentence.", "How long does light take from the Sun? One sentence."))
        val hits = ArrayList<Int>()
        fun cacheLines() = compose.onAllNodes(hasText("cache", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") }.filter { "· cache" in it || it.startsWith("cache") }
        for ((c, chat) in questions.withIndex()) {
            if (c > 0) { tap("chats"); tap("new chat"); compose.waitUntil(20_000) { tagExists("chat.composer") } }
            for ((i, q) in chat.withIndex()) {
                // The card stands under the newest reply only, so wait for ITS line to be a new one.
                val before = cacheLines().lastOrNull()
                tapTag("chat.composer"); compose.onAllNodes(hasSetTextAction())[0].performTextInput(q); Thread.sleep(300); tapTag("chat.send")
                compose.waitUntil(120_000) { !tagExists("chat.stop") && cacheLines().lastOrNull().let { it != null && it != before } }
                val line = cacheLines().last()
                android.util.Log.w("teemoon", "[cache-gate] SUMMARY $preset $model chat ${c + 1} send ${i + 1}: $line")
                if (i > 0) Regex("""cache (\d+)%""").find(line)?.groupValues?.get(1)?.toInt()?.let { hits += it }
            }
        }
        assertTrue("no send 2–4 cached anything on $preset $model: $hits", hits.any { it > 0 })
    }

    /**
     * The search chat's cache (pm, 2026-10-22, after the prefix cases in ConsecutiveSendsPrefixTest): one fresh chat that
     * searches on message 1, then three normal sends; with `-e cache_plain_chat true`, a second chat with a plain message 1
     * as well. Each send logs the card's figure and every request's cached/prompt tokens, which the card sums into a share.
     * The counts come from the open chat's view model (test only: the card shows the share alone, as on iOS).
     */
    @Test fun aSearchChatCachesItsNextSends() {
        val args = InstrumentationRegistry.getArguments()
        val preset = args.getString("cache_preset") ?: "fireworks"
        val model = args.getString("cache_model") ?: "accounts/fireworks/models/glm-5p3-flash"
        assumeTrue("no key staged for $preset — run tools/live_cloud_suite.sh", UiTestSeeding.keyFileNames(preset).any { staged(it) })
        assumeTrue("no ~/.BRAVE_API_KEY staged", staged(".BRAVE_API_KEY"))
        launch(preset, UiTestSeeding.EXTRA_PRESET_MODEL to model, UiTestSeeding.EXTRA_DEVELOPER to true, UiTestSeeding.EXTRA_GROUNDING to true)
        val chats = mutableListOf("search" to listOf("search the web: what is the latest stable linux kernel version? one line, cite your source.",
            "And what was the version before it? One sentence.", "Who maintains the stable series? One sentence.", "How often do stable releases come out? One sentence."))
        if (args.getString("cache_plain_chat") == "true") chats += "plain" to listOf("Why is the sky blue? One sentence.", "And why are sunsets red? One sentence.")
        fun cacheLines() = compose.onAllNodes(hasText("cache", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") }.filter { "· cache" in it || it.startsWith("cache") }
        /** Every request of the last send: (prompt, cached) as the provider reported them. */
        fun usage(): List<Pair<Int?, Int?>> {
            var out: List<Pair<Int?, Int?>> = emptyList()
            scenario!!.onActivity { a ->
                @Suppress("RestrictedApi") val store = a.viewModelStore
                @Suppress("RestrictedApi") val vm = store.keys().mapNotNull { store[it] as? ChatViewModel }.lastOrNull { it.state.value.messages.isNotEmpty() } ?: return@onActivity
                val f = ChatGeneration::class.java.getDeclaredField("lastPhases").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val phases = f.get(vm.generation) as List<ai.teemoon.inference.TurnPhase>
                out = phases.filter { it.kind == ai.teemoon.inference.TurnPhase.Kind.RESPONSE }.map { it.tokens to it.cachedTokens }
            }
            return out
        }
        for ((c, chat) in chats.withIndex()) {
            if (c > 0) { tap("chats"); tap("new chat"); compose.waitUntil(20_000) { tagExists("chat.composer") } }
            for ((i, q) in chat.second.withIndex()) {
                val before = cacheLines().lastOrNull()
                tapTag("chat.composer"); compose.onAllNodes(hasSetTextAction())[0].performTextInput(q); Thread.sleep(300); tapTag("chat.send")
                compose.waitUntil(180_000) { !tagExists("chat.stop") && cacheLines().lastOrNull().let { it != null && it != before } }
                Thread.sleep(500)
                val requests = usage()
                android.util.Log.w("teemoon", "[cache-gate] SUMMARY $preset $model ${chat.first} chat send ${i + 1}: ${cacheLines().last()} · " +
                    "requests ${requests.size}: " + requests.joinToString { (p, k) -> "${k ?: "-"}/${p ?: "-"}" } +
                    " · total ${requests.sumOf { it.second ?: 0 }}/${requests.sumOf { it.first ?: 0 }}")
            }
        }
    }

    /** OpenRouter: seeded from its key, the default is an OPEN-WEIGHT model (0ae4b57), and a real turn answers. */
    @Test fun anOpenRouterTurnAnswers() {
        assumeTrue("no .OPENROUTER_API_KEY staged — run tools/live_cloud_suite.sh", staged(".OPENROUTER_API_KEY"))
        launch("openrouter")
        askAndAwaitAnswer()
    }

    /** NVIDIA's free tier: a key the host accepts, and a reply from the model the strongest-open-weight rule picked. */
    @Test fun anNvidiaTurnAnswers() {
        assumeTrue("no .NVIDIA_API_KEY staged — run tools/live_cloud_suite.sh", staged(".NVIDIA_API_KEY"))
        launch("nvidia")
        askAndAwaitAnswer()
    }

    /** Brave Answers: the user's key, Brave's own answer engine, a real turn. The one keyed place with no live case until 2026-10-13. */
    @Test fun aBraveAnswersTurnAnswers() {
        assumeTrue("no .BRAVE_ANSWERS_API_KEY staged — run tools/live_cloud_suite.sh", staged(".BRAVE_ANSWERS_API_KEY"))
        launch("braveanswers")
        askAndAwaitAnswer()
    }

    /**
     * The model card's providers table (835d7c8), from the browser a user reaches it by: where → browse openrouter →
     * search → long-press → model info. With a key, OpenRouter reports first-token time and speed, which the public
     * list leaves null — the one part of the card only a live keyed request can show.
     */
    @Test fun theOpenRouterCardListsWhoCanServeAModel() {
        assumeTrue("no .OPENROUTER_API_KEY staged — run tools/live_cloud_suite.sh", staged(".OPENROUTER_API_KEY"))
        launch("openrouter")
        // Opened, then EXPANDED before a row is tapped (ParityCaptureTest.expand): the sheet opens half-height, and a tap on
        // a row below its fold lands on the scrim and dismisses it — the first run of this case ended on the chat.
        tapTag("chat.whereChip"); compose.waitUntil(20_000) { exists("where") }
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.5f, height * 0.6f), Offset(width * 0.5f, height * 0.12f), 300) }
        Thread.sleep(1_000)
        // The keyed preset's row in "get" is titled by the provider alone (1.0.4 parity, 4266713 — iOS WhereGetSection);
        // "browse <name>" is a custom place's row. Matched exactly: a ready-now row's caption also says "openrouter · …".
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("openrouter")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("openrouter")).let { rows -> rows[rows.fetchSemanticsNodes().size - 1] }.let { runCatching { it.performScrollTo() }; it.performClick() }
        compose.waitForIdle(); Thread.sleep(600)
        compose.waitUntil(60_000) { exists("models") && !exists("loading models") }
        // Searched by ID: the search matches ids, but a row shows OpenRouter's product name ("gpt 5.4", no hyphen), so the
        // row is found by the one label every OpenRouter openai row shares — not by the id it was searched for.
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size.let { n -> compose.onAllNodes(hasSetTextAction())[n - 1].performTextInput("openai/gpt-5.4") }
        compose.waitUntil(20_000) { exists("1 model") || exists(" of ") }
        val row = hasText("gpt", substring = true, ignoreCase = true) and !hasSetTextAction()
        compose.waitUntil(20_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(row)[0].performTouchInput { longClick() }
        tap("model info")
        compose.waitUntil(60_000) { exists("providers") }
        compose.waitUntil(10_000) { exists("uptime · 1d") }
        compose.waitUntil(10_000) { exists("first token") && exists("speed") }
    }

    /**
     * near.ai's sheet, over a real attestation: the model is NAMED (not the HF snapshot hash its compose points at — iOS
     * d1ac2be) and the published reviews reach it (the audit index was never fetched on Android until 2026-09-23, so no
     * everyday audit line and no fable miniaudit ever rendered). Both passed every offline suite: the fixtures carry a
     * clean model path and a fixed index. Read-only — it opens the sheet, and sends nothing.
     */
    @Test fun theNearAISheetNamesTheModelAndShowsTheReviews() {
        assumeTrue("no .NEAR_AI_API_KEY staged — run tools/live_cloud_suite.sh", staged(".NEAR_AI_API_KEY"))
        launch("nearai")
        // A cold install's first attestation (quotes, the GPU report, the image lookups) runs past tap()'s 20 s.
        compose.waitUntil(60_000) { exists("end-to-end encrypted") }
        tap("end-to-end encrypted")
        // The hero is "encrypted to <model>"; "— only it can read this" is added only over a reviewed, code-checked
        // build (TrustVerdict.heroTitle, trust-leaks-wording, cf79270d), so the wait is on the part every state shows.
        compose.waitUntil(60_000) { exists("encrypted to ") }
        val hero = compose.onAllNodes(hasText("encrypted to ", substring = true)).fetchSemanticsNodes().joinToString { it.config.toString() }
        assertTrue("the hero names a snapshot hash, not the model: $hero", !Regex("encrypted to [0-9a-f]{12,}").containsMatchIn(hero))
        assertTrue("the hero should name GLM: $hero", hero.contains("encrypted to GLM"))
        tap("expert")
        compose.waitUntil(30_000) { exists("fable miniaudit") }
    }

    /**
     * E10: re-verify from the sheet runs the attestation again — the stamp's nonce changes, as fresh nonces must — and
     * the expert rung's copy puts the generated self-verify script on the clipboard. Live: both read a real record.
     */
    @Test fun reverifyRefreshesTheStampAndCopyPutsTheScriptOnTheClipboard() {
        assumeTrue("no .NEAR_AI_API_KEY staged — run tools/live_cloud_suite.sh", staged(".NEAR_AI_API_KEY"))
        launch("nearai")
        compose.waitUntil(60_000) { exists("end-to-end encrypted") }   // a cold install's first attestation outlasts tap()'s 20 s
        tap("end-to-end encrypted")
        val nonce = Regex("nonce [0-9a-fA-F]{4}…[0-9a-fA-F]{4}")
        fun stamp() = compose.onAllNodes(hasText("nonce ", substring = true)).fetchSemanticsNodes()
            .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.let { nonce.find(it)?.value } }
        compose.waitUntil(60_000) { stamp() != null }
        val first = stamp()!!
        tap(A11yLabels.RE_RUN_VERIFICATION)
        compose.waitUntil(60_000) { stamp().let { it != null && it != first } }
        tap("expert")
        compose.waitUntil(30_000) { exists("re-check the live service yourself") }
        compose.onAllNodes(hasText("copy")).fetchSemanticsNodes().let { assumeTrue("no script card on this record", it.isNotEmpty()) }
        compose.onAllNodes(hasText("copy"))[0].let { runCatching { it.performScrollTo() }; it.performClick() }
        compose.waitUntil(5_000) { exists("copied") }
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertTrue("the clipboard does not hold the verify script: ${clip?.take(80)}", clip != null && clip!!.contains("python", ignoreCase = true))
    }

    // MARK: cheap models only. Every case that spends pins the cheapest model that can do the job (2026-10-05 prices):
    // Grok grok-build-0.1 (xAI's cheapest), Fireworks glm-5p3-flash ($0.15/M in; gpt-oss-20b is no
    // longer served there, and the $0.05 nemotron lightning answered without searching), OpenRouter gpt-oss-20b (~$0.02/M in), near.ai
    // glm-5.3-flash ($0.15/M in). The attestation cases (E7, E12) send nothing: an attestation fetch costs nothing.

    /** The home server for L2 and the Ollama search case: the tailnet name on a phone that has Tailscale, else the Mac's own Ollama. */
    private val ollamaEndpoint get() = InstrumentationRegistry.getArguments().getString("ollamaEndpoint") ?: "http://10.0.2.2:11434/v1"
    private val ollamaModel get() = InstrumentationRegistry.getArguments().getString("ollamaModel") ?: "gemma4:e4b"
    /** Every text and description on screen, lowercased — what the assertions read. */
    private fun screen(): String = runCatching {
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root ->
            fun walk(n: SemanticsNode): List<String> =
                listOfNotNull(n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
                    n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")) + n.children.flatMap { walk(it) }
            walk(root)
        }.joinToString(" | ")
    }.getOrDefault("").lowercase()
    private val streaming get() = tagExists("chat.stop")
    private fun waitOrSay(what: String, ms: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) { if (runCatching(cond).getOrDefault(false)) return; Thread.sleep(250) }
        throw AssertionError("timed out waiting for $what; on screen: ${screen().replace(Regex("\\s+"), " ").take(1500)}")
    }
    private fun type(text: String) { tapTag("chat.composer"); compose.onAllNodes(hasSetTextAction())[0].performTextInput(text); Thread.sleep(300); tapTag("chat.send") }

    /** D7: a search-worthy question, grounding on: the model calls web_search and the reply carries its sources. */
    private fun searchAndExpectSources() {
        assumeTrue("no ~/.BRAVE_API_KEY staged", staged(".BRAVE_API_KEY"))
        compose.waitUntil(30_000) { tagExists("chat.composer") }
        type("search the web: what is the latest stable linux kernel version? one line, cite your source.")
        waitOrSay("the turn to start", 15_000) { streaming }
        waitOrSay("the turn to end", 240_000) { !streaming }
        Thread.sleep(1_500)
        assertTrue("the reply carries no sources — the model never searched: ${screen().takeLast(600)}", tagExists("chat.sourcesChip"))
    }
    @Test fun grokSearchesAndTheReplyCarriesSources() {
        assumeTrue("no .XAI_API_KEY staged", staged(".XAI_API_KEY"))
        launch("grok", UiTestSeeding.EXTRA_PRESET_MODEL to "grok-build-0.1", UiTestSeeding.EXTRA_GROUNDING to true); searchAndExpectSources()
    }
    @Test fun fireworksSearchesAndTheReplyCarriesSources() {
        assumeTrue("no .FIREWORKS_API_KEY staged", staged(".FIREWORKS_API_KEY"))
        launch("fireworks", UiTestSeeding.EXTRA_PRESET_MODEL to "accounts/fireworks/models/glm-5p3-flash", UiTestSeeding.EXTRA_GROUNDING to true); searchAndExpectSources()
    }
    @Test fun openRouterSearchesAndTheReplyCarriesSources() {
        assumeTrue("no .OPENROUTER_API_KEY staged", staged(".OPENROUTER_API_KEY"))
        launch("openrouter", UiTestSeeding.EXTRA_PRESET_MODEL to "openai/gpt-oss-20b", UiTestSeeding.EXTRA_GROUNDING to true); searchAndExpectSources()
    }
    @Test fun ollamaSearchesAndTheReplyCarriesSources() {
        launch("", UiTestSeeding.EXTRA_LOCAL_ENDPOINT to ollamaEndpoint, UiTestSeeding.EXTRA_LOCAL_MODEL to ollamaModel, UiTestSeeding.EXTRA_GROUNDING to true); searchAndExpectSources()
    }

    /** L2: a real home server — Ollama, over the tailnet where the phone has it — answers a turn. */
    @Test fun aRealHomeServerAnswersATurn() {
        launch("", UiTestSeeding.EXTRA_LOCAL_ENDPOINT to ollamaEndpoint, UiTestSeeding.EXTRA_LOCAL_MODEL to ollamaModel)
        type("what is 17 plus 25? reply with the number only")
        compose.waitUntil(240_000) { exists("42") && !streaming }
    }

    /** E11: two live sealed turns on near.ai — both replies check out, the session never blocks, the debug card stamps E2EE. */
    @Test fun twoLiveNearAITurnsCheckOutAndStampE2EE() {
        assumeTrue("no .NEAR_AI_API_KEY staged", staged(".NEAR_AI_API_KEY"))
        launch("nearai", UiTestSeeding.EXTRA_PRESET_MODEL to "z-ai/glm-5.3-flash", UiTestSeeding.EXTRA_DEVELOPER to true)
        compose.waitUntil(60_000) { exists("end-to-end encrypted") }
        for ((i, prompt) in listOf("reply with the single word one", "now reply with the single word two").withIndex()) {
            // the turn started: streaming seen, or already answered — GLM answers in under a second (0.7 s on the AVD,
            // 2026-11-14), and the stop button could come and go between polls. The developer card sits under the newest
            // reply only, so a NEW card (its turn's own timings) is the answer; a turn that never happens still fails here.
            fun card() = compose.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("chat.debugCard")), useUnmergedTree = true)
                .fetchSemanticsNodes().joinToString("|") { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString().orEmpty() }
            val cardBefore = card()
            type(prompt)
            compose.waitUntil(15_000) { streaming || (card().isNotEmpty() && card() != cardBefore) }
            compose.waitUntil(180_000) { !streaming }
            Thread.sleep(2_000)
            assertTrue("turn ${i + 1}: the debug card does not stamp E2EE", tagExists("chat.debugCard.e2ee"))
            val s = screen()
            assertFalse("turn ${i + 1}: a reply didn't check out", s.contains("didn't check out") || s.contains("didn’t check out"))
            assertFalse("turn ${i + 1}: the session blocked", s.contains("sending blocked"))
        }
    }

    /** The sheet, read to the end: every line the expert rung realises, scrolled through. */
    private fun expertSheetText(): String {
        tap("end-to-end encrypted")
        // the stamp, not the hero's wording: a build whose review found its logs copy what you type says so in the hero
        waitOrSay("the verified sheet", 60_000) { exists("verified just now") || exists("verified ") && exists("nonce") }
        tap("expert"); Thread.sleep(3_000)
        val seen = StringBuilder(screen())
        repeat(30) { compose.onRoot().performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.3f) }; Thread.sleep(500); seen.append(screen()) }
        return seen.toString()
    }

    /** E12: each near.ai model's expert sheet is about THAT model — its own name, an engine, no other family's. */
    @Test fun eachNearAIModelsSheetShowsItsOwnEnclave() {
        assumeTrue("no .NEAR_AI_API_KEY staged", staged(".NEAR_AI_API_KEY"))
        // near.ai's own fleet (`owned_by: nearai` on /v1/models) — the end-to-end encrypted ones; each named by a token
        // only it carries, since two are Qwens
        val models = listOf("z-ai/glm-5.3-flash" to "glm", "Qwen/Qwen3.6-35B-A3B-FP8" to "qwen3.6", "Qwen/Qwen3.8-27B" to "qwen3.8")
        for ((id, family) in models) {
            launch("nearai", UiTestSeeding.EXTRA_PRESET_MODEL to id)
            waitOrSay("$id to verify", 60_000) { exists("end-to-end encrypted") }
            val text = expertSheetText()
            assertTrue("$id: the sheet never names its model ($family)", text.contains(family))
            assertTrue("$id: the sheet shows no serving engine", text.contains("vllm") || text.contains("sglang"))
            // ABOVE "the running recipe" only: the recipe section lists every image the signed recipe declares and their
            // audits — fleet-wide by design, so other models' services are named there legitimately
            val scoped = text.substringBefore("the running recipe")
            for ((_, other) in models) if (other != family) assertFalse("$id: the sheet names $other above the recipe", scoped.contains(other))
        }
    }

    /** E7: switching near.ai's model never shows the previous model's attestation. */
    @Test fun switchingTheNearAIModelNeverShowsTheOldAttestation() {
        assumeTrue("no .NEAR_AI_API_KEY staged", staged(".NEAR_AI_API_KEY"))
        launch("nearai", UiTestSeeding.EXTRA_PRESET_MODEL to "z-ai/glm-5.3-flash")
        tap("end-to-end encrypted"); compose.waitUntil(60_000) { exists("GLM") }
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.95f) }; Thread.sleep(1_000)
        if (exists("who can read this?")) tapTag("chat.composer")
        // switch through where → cloud → browse near.ai → search → pick
        tapTag("chat.whereChip"); tap("cloud"); tapTag("where.preset.near.ai")
        compose.waitUntil(30_000) { exists("search models") }
        compose.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1].performTextInput("qwen3.6") }; Thread.sleep(1_000)
        // the browser lists display names ("qwen 3.6 35b a3b fp8"), not ids
        val pick = compose.onAllNodes(hasText("qwen 3.6", substring = true, ignoreCase = true)).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.EditableText) == null }
        if (pick.isEmpty()) waitOrSay("a qwen3.6 row in the browser", 1) { false }
        compose.onAllNodes(hasText(pick.first().config[SemanticsProperties.Text].first().text))[0].performClick()
        compose.waitUntil(15_000) { !exists("search models") }
        tap("end-to-end encrypted")
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            assertFalse("the switched sheet shows the old model's attestation", screen().contains("glm"))
            Thread.sleep(500)
        }
        compose.waitUntil(45_000) { screen().contains("qwen") }
    }

    /**
     * A5: a cloud key the host refuses — OpenRouter's own key check answers 401 for a made-up key (free: nothing is
     * generated), and the form says so before anything is saved.
     */
    @Test fun aRejectedCloudKeyIsSaidByTheForm() {
        launch("", UiTestSeeding.EXTRA_LOCAL_ENDPOINT to "http://127.0.0.1:9/v1")   // a place, so the sheet is past first run
        tapTag("chat.whereChip"); tap("cloud"); tapTag("where.preset.openrouter")
        waitOrSay("the openrouter key form", 15_000) { exists("add openrouter key") }
        compose.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1].performTextInput("sk-or-v1-0000000000000000000000000000000000000000000000000000000000000000") }
        Thread.sleep(3_000)   // editing the key re-probes on its own (ProviderModelSection)
        if (!screen().contains("rejected") && (exists("test connection") || exists("fetch models"))) tap(if (exists("test connection")) "test connection" else "fetch models")
        waitOrSay("the form to say the key is refused, before save", 30_000) { screen().contains("rejected") || screen().contains("didn't recognise") || screen().contains("not valid") }
        // Save may still keep a refused cloud key (AddEditProviderModel.save does not consult the check, iOS and Android
        // alike): by decision (2026-10-05), not a defect — the form saying so is the whole requirement.
    }
}
