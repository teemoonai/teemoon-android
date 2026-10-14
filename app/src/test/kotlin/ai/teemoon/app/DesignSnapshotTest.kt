// Paparazzi baselines for the design pass: the chats list, a chat with a transcript (light and dark), the Where sheet in the dark.
package ai.teemoon.app

import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.Provider
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DesignSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 360, screenHeight = 800, density = com.android.resources.Density.MEDIUM))

    private val now = 1_756_900_000_000L
    // The chats list prints times in the SYSTEM zone, so these goldens depended on where the Mac was: recorded on Hawaii
    // time, they drifted by six hours the day the machine was on US Eastern (2026-10-02). Pinned to the recording zone.
    private var savedZone: TimeZone? = null
    @Before fun pinZone() { savedZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu")) }
    @After fun restoreZone() { savedZone?.let { TimeZone.setDefault(it) } }
    private val threads = listOf(
        ThreadEntity("t1", "what is a trusted execution environment?", now - 60_000),
        ThreadEntity("t2", "compare glm5.1 and qwen3.5 122b", now - 3_600_000),
        ThreadEntity("t3", "what is surveillance capitalism?", now - 86_400_000),
        ThreadEntity("t4", "explain zero-knowledge proofs simply", now - 90_000_000),
    )
    private val previews = mapOf("t1" to "Exactly. Your input goes in, the model runs inside the enclave.", "t2" to "This is a mock response.", "t3" to "This is a mock response.", "t4" to "This is a mock response.")

    private fun chatVm(): ChatViewModel {
        val p = Provider(id = "n", name = "near.ai", endpoint = "https://cloud-api.near.ai/v1/chat/completions", model = "glm-5.2")
        val vm = ChatViewModel(provider = { p })
        vm.restore(listOf(
            MessageEntity("m1", "t1", "user", "what is a trusted execution environment?", now - 60_000),
            MessageEntity("m2", "t1", "assistant", "A **trusted execution environment** (TEE) is a secure area inside a processor that runs code in an isolated enclave — even the operating system and hardware owner can't inspect or tamper with what's running inside it.\n\nFor AI inference, this means your prompts and the model weights are processed inside the enclave. No server operator can read your conversations.", now - 50_000),
            MessageEntity("m3", "t1", "user", "so my data never leaves the enclave?", now - 20_000),
            MessageEntity("m4", "t1", "assistant", "Exactly. Your input goes in, the model runs inside the enclave, and only the output comes back out — encrypted, signed, and verifiable.", now - 10_000),
        ))
        return vm
    }

    @Test fun chatsList_buckets() {
        paparazzi.snapshot { TeemoonTheme { ThreadsScreen(threads = threads, onOpen = {}, onNew = {}, onClose = {}, previews = previews, nowMs = { now }) } }
    }

    /** The default theme IS dark: no `dark =` here, so this snapshot fails if the pin (Theme.kt) ever follows the system again. */
    @Test fun chat_transcript_dark() {
        paparazzi.snapshot { TeemoonTheme { ChatScreen(focusOnEmpty = false, vm = chatVm(), title = "glm-5.2 · near.ai", hasProvider = true, onOpenSettings = {}, webSearchState = WebSearchChipState.OFF) } }
    }

    @Test fun whereSheet_dark() {
        val home = Provider(id = "h", name = "second mac", endpoint = "http://10.0.2.2:11434/v1", model = "qwen3:14b", requiresAPIKey = false)
        val cloud = Provider(id = "n", name = "near.ai", endpoint = "https://cloud-api.near.ai/v1/chat/completions", model = "glm-5.2")
        paparazzi.snapshot { TeemoonTheme(dark = true) {
            WhereSheet(providers = listOf(home, cloud), selectedId = "n", problem = null, onSelect = {}, onBack = {}, hasKey = { it.id == "n" })
        } }
    }

    @Test fun addPlace_grokPreset() {
        val model = AddEditProviderModel(AddEditProviderModel.Mode.Add, initialPreset = Provider.presets.first { it.name.contains("grok", ignoreCase = true) })
        paparazzi.snapshot { TeemoonTheme(dark = true) { AddEditProviderScreen(model, onDone = {}, focusOnOpen = false) } }
    }

    /** iOS cf251b5: arriving with Brave Answers chosen, its two-key warning is said beside the key field. */
    @Test fun addPlace_braveAnswersPreset() {
        val model = AddEditProviderModel(AddEditProviderModel.Mode.Add, initialPreset = Provider.braveAnswers)
        paparazzi.snapshot { TeemoonTheme(dark = true) { AddEditProviderScreen(model, onDone = {}, focusOnOpen = false) } }
    }

    /** ProviderPresetSection (iOS fcf4d93 + cf251b5): the presets as rows — cloud glyph, caption, e2ee tag — and "custom". */
    @Test fun addPlace_presetRows() {
        val model = AddEditProviderModel(AddEditProviderModel.Mode.Add)
        model.selectedPresetName = "near.ai"
        paparazzi.snapshot { TeemoonTheme(dark = true) { AddEditProviderScreen(model, onDone = {}, focusOnOpen = false) } }
    }
}
