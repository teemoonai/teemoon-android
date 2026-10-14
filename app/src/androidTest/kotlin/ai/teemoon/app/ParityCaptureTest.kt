package ai.teemoon.app

// android-only: the Android side of the screen audit (teemoon-ios: teemoonUITests/ParityCaptureUITests.swift).
// In-process Compose UI test: each state launches MainActivity with the UiTestSeeding extras (keys from the files
// tools/parity_capture_compose.sh pushes), drives the screens by semantics, and writes full-screen shots to the
// app's external files dir. Seconds per state, no uiautomator tree dumps, nothing typed on screen but the prompts.

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.FixMethodOrder
import org.junit.Rule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.layout.positionOnScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereProviderPresentation
import android.accessibilityservice.AccessibilityService
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot

/**
 * The seeded near.ai preset's row, as the where sheet labels it. DERIVED, not typed: these journeys named "glm-5.2"
 * and broke when the preset's default moved off that retired model (b760889) — nothing on the JVM could see it.
 */
private val NEAR_AI_ROW = WhereProviderPresentation.modelLabel(Provider.nearAI)

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ParityCaptureTest {
    @get:Rule val compose = emptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val out = File(ctx.getExternalFilesDir(null), "parity").apply { mkdirs() }
    private val local = "http://10.0.2.2:8765/v1"

    private fun launch(vararg extras: Pair<String, Any>): ActivityScenario<MainActivity> {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            extras.forEach { (k, v) -> when (v) { is String -> putExtra(k, v); is Boolean -> putExtra(k, v); else -> error(k) } }
        }
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        waitTag("chat.composer", 20_000)
        Thread.sleep(2_500)   // the seeding runs off the main thread
        return scenario
    }
    /** [expect]: a label the intended screen carries; the shot waits for it, and logs when it never came (the audit then shows the wrong screen, named). */
    private fun shot(name: String, expect: String? = null) {
        if (expect != null && !runCatching { compose.waitUntil(8_000) { exists(expect) }; true }.getOrDefault(false)) Log.w("teemoon", "[parity] $name: expected '$expect' on screen and it never came")
        compose.waitForIdle(); Thread.sleep(700)
        // UiAutomation returns null when the compositor is busy; a retry a moment later is enough
        val bmp: Bitmap = (1..4).firstNotNullOfOrNull { inst.uiAutomation.takeScreenshot() ?: run { Thread.sleep(500); null } } ?: error("no screenshot for $name")
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The element tree beside the pixels (iOS writes app.debugDescription): what is on screen, labelled, and where —
        // tools/structure_audit.py pairs the two and flags a label missing on one side or sitting somewhere else.
        File(out, "$name.tree.json").writeText(elementTreeJson())
    }
    private fun elementTreeJson(): String {
        // The Compose semantics tree, not the accessibility tree: UiAutomation's nodes lagged the screen by a state
        // (the "appearance" capture carried the settings root, 2026-09-09), the test rule's tree is current, and it
        // carries hideFromAccessibility — the chat behind a presented sheet is dropped, as a screen reader drops it.
        val dm = ctx.resources.displayMetrics; val sb = StringBuilder("["); var first = true
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        fun walk(n: SemanticsNode) {
            val c = n.config
            if (c.contains(SemanticsProperties.HideFromAccessibility)) return
            val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: c.getOrNull(SemanticsProperties.EditableText)?.text ?: ""
            val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: ""
            // screen coordinates: a dialog's window sits mid-screen, and its nodes' window bounds start at its own origin
            val w = n.boundsInWindow; val screen = runCatching { n.layoutInfo.coordinates.positionOnScreen() }.getOrNull()
            val r = if (screen != null && screen.x.isFinite()) Rect(screen.x, screen.y, screen.x + w.width, screen.y + w.height) else w
            if ((text.isNotEmpty() || desc.isNotEmpty()) && r.width > 0 && r.height > 0) {
                if (!first) sb.append(","); first = false
                val clickable = c.contains(SemanticsActions.OnClick)
                sb.append("{\"text\":\"${esc(text)}\",\"desc\":\"${esc(desc)}\",\"class\":\"${c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Role)?.toString() ?: "node"}\",\"clickable\":$clickable,\"l\":${r.left.toInt()},\"t\":${r.top.toInt()},\"r\":${r.right.toInt()},\"b\":${r.bottom.toInt()}}")
            }
            n.children.forEach { walk(it) }
        }
        // the LAST root is the frontmost window (a dialog over the app), as the last top-level sibling is on iOS
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().lastOrNull()?.let { walk(it) }
        return "{\"screen\":[${dm.widthPixels},${dm.heightPixels}],\"elements\":${sb.append("]")}}"
    }
    private fun exists(label: String) = compose.onAllNodes(hasText(label) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String, ms: Long = 8_000) = compose.waitUntil(ms) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun wait(label: String, ms: Long = 20_000) = compose.waitUntil(ms) { exists(label) }
    /** Taps the node whose text or content description is exactly [label]; [index] picks among duplicates. */
    private fun tap(label: String, index: Int = 0, settle: Long = 700) {
        wait(label); compose.onAllNodes(hasText(label) or hasContentDescription(label))[index].performClick(); compose.waitForIdle(); Thread.sleep(settle)
    }
    private fun tapTag(tag: String, settle: Long = 700) { waitTag(tag); compose.onAllNodesWithTag(tag)[0].performClick(); compose.waitForIdle(); Thread.sleep(settle) }
    private fun back() { inst.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(900) }
    /** iOS's `app.swipeUp()`: drag the sheet to its large detent. */
    private fun expand() { compose.onRoot().performTouchInput { swipe(Offset(width * 0.5f, height * 0.6f), Offset(width * 0.5f, height * 0.12f), 300) }; Thread.sleep(1_000) }
    /** Cancels the add/edit form and waits for its sheet to be gone — its keyboard leaves with it and the where sheet re-lays out. */
    private fun cancelForm() { tap("cancel", settle = 300); compose.waitUntil(10_000) { compose.onAllNodes(hasText("save")).fetchSemanticsNodes().isEmpty() }; Thread.sleep(1_000) }
    private fun whereChip() { tapTag("chat.whereChip"); wait("where") }
    private fun send(prompt: String, waitReply: Boolean) {
        tapTag("chat.composer", 300); compose.onAllNodesWithTag("chat.composer")[0].performTextInput(prompt); Thread.sleep(300)
        tapTag("chat.send", 500)
        if (waitReply) compose.waitUntil(180_000) { compose.onAllNodesWithTag("chat.stop").fetchSemanticsNodes().isEmpty() && !exists("…") } else Thread.sleep(8_000)
    }

    /** State 0 — nothing configured: the gemma hero. (The script ran `pm clear` before the class.) */
    @Test fun a_firstRun() {
        launch().use { shot("chat_first_run"); whereChip(); shot("where_first_run"); back() }
    }

    /** State A — a home server, no keys. */
    @Test fun b_noKeys() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to local, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model").use {
            tap("chats"); shot("chats_list"); tap("close")
            whereChip(); tap("cloud"); shot("where_cloud"); tap("all"); expand(); tapTag("where.preset.grok"); shot("add_key_grok", expect = "add grok key"); tap("cancel"); tap("close")
            tap("settings"); tap("search"); shot("search_settings", expect = "web search"); back(); tap("places & keys", 1); shot("places_hub"); tap("cloud keys"); shot("places_cloud"); back(); back(); back()
            // the home server with no keys, as iOS's test1 (these paired against a keyed Android state until 2026-09-10)
            tap("chats"); tap("new chat"); shot("chat_empty")   // the home server is the seeded place already; picking it again left the sheet open
            whereChip(); shot("where_all"); tap("phone"); shot("where_phone"); tap("home"); shot("where_home"); tap("all"); expand(); tap("connect a computer"); shot("add_computer"); cancelForm(); tap("close")
            tap("settings"); shot("settings_root", expect = "appearance"); tap("appearance"); shot("appearance", expect = "font"); back(); tap("chats", 1); shot("chats_settings", expect = "chats"); back(); back()
            tap("settings"); tap("places & keys", 1); tap("home machines"); shot("places_machines"); back(); back(); back()
            tap("on your own machine"); shot("trust_local"); back()
            send("what is the capital of france?", waitReply = false); shot("chat_failed_turn")   // the send drops the prompt focus itself now (ChatView.performGenerate)
        }
    }

    /** State B — the home server + near.ai keyed + the Brave grounding key (mirrors test1 + test2). */
    @Test fun c_keyed() {
        launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to local, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model",
               UiTestSeeding.EXTRA_PRESETS to "nearai", UiTestSeeding.EXTRA_GROUNDING to true).use {
            // near.ai first, so the list holds its own thread only
            tap("chats"); tap("new chat"); whereChip(); expand(); tap(NEAR_AI_ROW); wait("end-to-end encrypted", 180_000); shot("chat_near_verified")
            tap("end-to-end encrypted"); shot("trust_everyday"); tap("expert"); shot("trust_proof"); expand(); shot("trust_proof_2"); back()
            whereChip(); shot("where_all_keyed"); expand(); tap("browse near.ai"); Thread.sleep(6_000); shot("model_browser_near"); tap("cancel"); tap("close")
            send("name three moons of jupiter.", waitReply = true); shot("chat_near_reply")
            // the sources sheet, when the reply was grounded (GroundingSourcesView) — paired with iOS's sources_sheet
            if (compose.onAllNodesWithTag("chat.sourcesChip").fetchSemanticsNodes().isNotEmpty()) { tapTag("chat.sourcesChip"); Thread.sleep(900); shot("sources_sheet"); tap("close") }
            tap("chats"); shot("chats_list_populated"); tap("close")
            whereChip(); tap("cloud"); shot("where_cloud_keyed"); tap("all"); tap("close")
            tap("settings"); tap("search"); shot("search_settings_keyed", expect = "web search"); back(); tap("places & keys", 1); shot("places_hub_keyed"); tap("cloud keys"); shot("places_cloud_keyed"); back(); back(); back()
        }
    }

    @Test fun d_grok() { launch(UiTestSeeding.EXTRA_PRESETS to "grok").use { whereChip(); expand(); tap("browse grok"); Thread.sleep(6_000); shot("browse_grok"); tap("cancel"); tap("close") } }
    @Test fun e_fireworks() { launch(UiTestSeeding.EXTRA_PRESETS to "fireworks").use { whereChip(); expand(); tap("browse fireworks"); Thread.sleep(6_000); shot("browse_fireworks"); tap("cancel"); tap("close") } }
    @Test fun f_brave() {
        launch(UiTestSeeding.EXTRA_PRESETS to "braveanswers,nearai,grok,fireworks", UiTestSeeding.EXTRA_GROUNDING to true).use {
            shot("chat_brave"); tap("settings"); tap("search"); shot("search_settings_builtin", expect = "web search"); back(); tap("close")
            whereChip(); tap("cloud"); shot("where_cloud_allkeys"); tap("all"); tap("close")
        }
    }
    @Test fun g_gate() {
        launch(UiTestSeeding.EXTRA_PRESETS to "nearai", UiTestSeeding.EXTRA_ATTESTATION to "degraded").use {
            shot("chat_degraded"); send("hello", waitReply = false); if (exists("send unencrypted")) { shot("send_gate"); tap("cancel") }
        }
    }

    /** The chat itself with a reply (in-process fake server), and the populated chats list — iOS's test4_chatReply. */
    @Test fun h_chatReply() {
        val reply = "## Harbour notes\n\nThe tide turns at **four**, and the pilot boards at the outer buoy.\n\n- first buoy: green, flashing\n- second buoy: red, steady\n\n```\nlet tide = 4\n```\n\nSee [the chart](https://example.org/chart) for the soundings."
        FakeSseServer(reply = reply).use { server ->
            launch(UiTestSeeding.EXTRA_LOCAL_ENDPOINT to server.baseUrl, UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model", UiTestSeeding.EXTRA_THREADS to true).use {
                tap("chats"); tap("new chat")
                send("what are the harbour notes?", waitReply = true); back()   // the keyboard down
                shot("chat_reply", expect = "the soundings")
                tap("chats"); shot("chats_populated")
            }
        }
    }
}
