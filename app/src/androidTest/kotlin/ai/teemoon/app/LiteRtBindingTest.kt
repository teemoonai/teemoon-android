// android-only: no Swift counterpart. The two places the app leans on litertlm-android's API shape rather than on
// its generation, checked against a real bundle so a binding upgrade that moves them fails here instead of in a turn.
// Neither runs on an emulator in the normal path — the drafter read happens only where speculative decoding is wanted
// (a Qualcomm GPU, the Snapdragon 8 Elite NPU), and the on-device journeys that attach a tool skip on an emulator.
//   0.16.1 → 0.18.0 (2026-10-14): `Capabilities(path)` became `ModelInfo.from(path)`, the drafter flag moved to
//   `LlmCapability`.
package ai.teemoon.app

import ai.teemoon.inference.BridgedTool
import ai.teemoon.inference.LiteRTToolBridge
import ai.teemoon.inference.LocalConversationSpec
import ai.teemoon.ondevice.LiteRtLmEngineFactory
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.LlmCapability
import com.google.ai.edge.litertlm.ModelInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtBindingTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(line: String) = Log.w("teemoon", "[binding] $line")

    /** The general bundle: the one every phone can open, and the one whose drafter the Qualcomm GPU path reads. */
    private fun generalBundle(): String? {
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        return LocalModelCatalog.all.firstOrNull(storage::isInstalled)?.let { storage.file(it).path }
            ?.takeIf { File(it).name == "gemma-4-E2B-it.litertlm" }
    }

    /**
     * The factory's `hasDrafter` swallows a failure into "no drafter" (speculative decoding off is a safe default),
     * so a read that broke would only show as a slower phone. Here it must succeed and say yes: Gemma 4 E2B carries
     * its drafter (the S25 Ultra's GPU measured it, 2026-10-14).
     */
    @Test fun theGeneralBundleReadsAsAnLlmWithADrafter() {
        val path = generalBundle(); assumeTrue("the general bundle is not installed", path != null)
        ModelInfo.from(path!!).use { info ->
            log("model type=${info.modelType} context=${info.maxContextTokens()} drafter=${(info as? LlmCapability)?.hasSpeculativeDecodingSupport()}")
            assertTrue("not read as an LLM: ${info.modelType}", info is LlmCapability)
            assertTrue("the bundle reads as having no drafter", (info as LlmCapability).hasSpeculativeDecodingSupport())
        }
    }

    /**
     * The tool shape crosses the binding by the factory's shim (LiteRTToolBridge's comment: the Kotlin binding
     * parses the FLAT function object and adds the envelope itself; handed the envelope, 0.16.1 failed at
     * createConversation). A conversation with web_search attached must open and answer. Whether the model chooses
     * to call it is the model's business, so that is logged, not asserted.
     */
    @Test fun aConversationWithTheSearchToolAttachedOpensAndAnswers() = runBlocking {
        val path = generalBundle(); assumeTrue("the general bundle is not installed", path != null)
        val bridge = LiteRTToolBridge()
        val description = bridge.descriptionJson(LiteRTToolBridge.BRIDGED_TOOL_NAME, "Search the web for current information.")
            .replace("\"function\":{", "\"function\":{\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]},")
        var calls = 0
        val tool = BridgedTool(LiteRTToolBridge.BRIDGED_TOOL_NAME, description) { args -> calls += 1; log("tool called: $args"); "Paris: 18 C, light rain." }
        val engine = LiteRtLmEngineFactory(ctx.cacheDir.path, ::log).open(path!!, 4096)
        val text = StringBuilder()
        try {
            val spec = LocalConversationSpec(systemMessage = null, history = emptyList(), tools = listOf(tool),
                userText = "Use web_search to find the weather in Paris right now, then tell me.", maxOutputTokens = 96,
                enableThinking = false, temperature = 0f)
            engine.stream(spec) { text.append(it) }
        } finally { engine.close() }
        log("tool calls=$calls reply=${text.toString().take(200)}")
        assertTrue("the conversation with a tool attached produced no text", text.isNotBlank())
    }
}
