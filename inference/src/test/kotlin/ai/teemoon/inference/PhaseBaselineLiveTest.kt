// Opt-in baseline for the 1.0.6 phase timings over REAL HTTP servers, through the app's own OpenAiTransport and
// GenerationEngine: one model per provider, two short prompts, once each — a baseline, not a sweep (the maintainer: "dont waste
// any tokens"). Skipped unless asked for:
//   TEEMOON_BASELINE_OLLAMA=gemma4:e4b      the Mac's Ollama (the "home" path); keyless, local, the web tool keyless too
//   TEEMOON_BASELINE_CLOUD=1                the BYOK clouds and near.ai, keys from the host's ~/.<NAME>_API_KEY files,
//                                           web search through the real Brave LLM Context API (~/.BRAVE_API_KEY)
//   ./gradlew :inference:testDebugUnitTest --tests '*PhaseBaselineLiveTest*' -i | grep baseline
// near.ai here is a PLAIN request to the gateway: the attestation gate and E2EE are app-side and not in this number.
package ai.teemoon.inference

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhaseBaselineLiveTest {
    private val prompts = listOf("What is the capital of France? One sentence.", "What's the weather in Tokyo right now?")

    private suspend fun run(label: String, endpoint: String, model: String, key: String?, brave: String) {
        for (prompt in prompts) {
            val start = java.time.Instant.now()
            var last: RequestResult? = null
            val events = object : StreamCallbacks { override fun onText(text: String) {}; override fun onSuccess(result: RequestResult) { last = result } }
            // 1024: a thinking model given 128 spent it all on reasoning and answered nothing (gemma4:e4b, 2026-10-19).
            val transport = OpenAiTransport(endpoint, model, apiKey = key, extraParams = mapOf("max_tokens" to "1024"), providerName = label)
            val outcome = runCatching { GenerationEngine(transport, listOf(BraveWebSearchTool(apiKey = brave)), events).run(emptyList(), prompt) }
            println("[baseline] $label $model \"${prompt.take(30)}\": ${TurnPhase.line(start, last?.phases.orEmpty())} · " +
                (outcome.exceptionOrNull()?.let { "failed: ${it.message?.take(120)}" } ?: "${outcome.getOrNull()?.length} chars"))
        }
    }

    private fun hostKey(name: String): String? = File(System.getProperty("user.home"), ".${name}_API_KEY").takeIf { it.isFile }?.readText()?.trim()

    @Test fun ollamaPhases() = runBlocking {
        val model = System.getenv("TEEMOON_BASELINE_OLLAMA").orEmpty()
        assumeTrue("set TEEMOON_BASELINE_OLLAMA to run", model.isNotEmpty())
        run("ollama", System.getenv("TEEMOON_BASELINE_URL") ?: "http://localhost:11434/v1/chat/completions", model, null, brave = "")
    }

    @Test fun cloudPhases() = runBlocking {
        assumeTrue("set TEEMOON_BASELINE_CLOUD to run", System.getenv("TEEMOON_BASELINE_CLOUD") != null)
        val brave = hostKey("BRAVE").orEmpty()
        listOf(
            Triple("fireworks", "https://api.fireworks.ai/inference/v1/chat/completions", "accounts/fireworks/models/glm-5p3-flash") to "FIREWORKS",
            Triple("openrouter", "https://openrouter.ai/api/v1/chat/completions", "z-ai/glm-5.3-flash") to "OPENROUTER",
            Triple("nvidia", "https://integrate.api.nvidia.com/v1/chat/completions", "z-ai/glm-5.3-flash") to "NVIDIA",
            Triple("near.ai", "https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.3-flash") to "NEAR_AI",
            Triple("xai", "https://api.x.ai/v1/chat/completions", "grok-4.3") to "XAI",
        ).forEach { (p, keyName) ->
            val key = hostKey(keyName)
            if (key == null) println("[baseline] ${p.first}: skipped (no ~/.${keyName}_API_KEY)") else run(p.first, p.second, p.third, key, brave)
        }
    }

    /**
     * Row 18 ("watching the reply arrive"): how the answer's text ARRIVES, per provider — the gaps between deltas and the
     * characters in each, as GenerationEngine hands them to the view model (onText is cumulative; a delta is the
     * growth). Several SSE events in one TCP read show up as back-to-back deltas (gap ~0). One ~250-word answer each.
     *   TEEMOON_BASELINE_CADENCE=1 (clouds, host keys) and/or TEEMOON_BASELINE_OLLAMA=<model> (the Mac's Ollama)
     */
    @Test fun deltaCadence() = runBlocking {
        val cloud = System.getenv("TEEMOON_BASELINE_CADENCE") != null; val ollama = System.getenv("TEEMOON_BASELINE_OLLAMA").orEmpty()
        assumeTrue("set TEEMOON_BASELINE_CADENCE or TEEMOON_BASELINE_OLLAMA to run", cloud || ollama.isNotEmpty())
        val only = System.getenv("TEEMOON_CADENCE_ONLY")?.split(",")   // e.g. fireworks,near.ai; TEEMOON_CADENCE_MAX the token cap
        val runs = buildList {
            if (cloud) {
                add(listOf("fireworks", "https://api.fireworks.ai/inference/v1/chat/completions", "accounts/fireworks/models/glm-5p3-flash", "FIREWORKS"))
                add(listOf("openrouter", "https://openrouter.ai/api/v1/chat/completions", "z-ai/glm-5.3-flash", "OPENROUTER"))
                add(listOf("near.ai", "https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.3-flash", "NEAR_AI"))
                add(listOf("xai", "https://api.x.ai/v1/chat/completions", "grok-4.3", "XAI"))
            }
            if (ollama.isNotEmpty()) add(listOf("ollama", System.getenv("TEEMOON_BASELINE_URL") ?: "http://localhost:11434/v1/chat/completions", ollama, ""))
        }
        for ((label, endpoint, model, keyName) in runs.filter { only == null || it[0] in only }) {
            val key = if (keyName.isEmpty()) null else hostKey(keyName) ?: continue
            val stamps = mutableListOf<Pair<Long, Int>>()
            var last: RequestResult? = null
            val events = object : StreamCallbacks { override fun onText(text: String) { stamps += System.nanoTime() to text.length }; override fun onSuccess(result: RequestResult) { last = result } }
            val transport = OpenAiTransport(endpoint, model, apiKey = key, extraParams = mapOf("max_tokens" to (System.getenv("TEEMOON_CADENCE_MAX") ?: "1200")), providerName = label)
            val outcome = runCatching { GenerationEngine(transport, emptyList(), events).run(emptyList(), "Explain how a lighthouse lens works, in about 250 words of plain prose.") }
            val deltas = stamps.zipWithNext { a, b -> (b.first - a.first) / 1e6 to (b.second - a.second) }.filter { it.second > 0 }
            fun List<Double>.q(p: Int) = if (isEmpty()) 0.0 else sorted()[(size * p / 100).coerceAtMost(size - 1)]
            val gaps = deltas.map { it.first }; val chars = deltas.map { it.second.toDouble() }
            val span = if (stamps.size > 1) (stamps.last().first - stamps.first().first) / 1e9 else 0.0
            println("[cadence] $label $model: ${deltas.size + 1} deltas, ${stamps.lastOrNull()?.second ?: 0} chars over ${"%.1f".format(span)} s · " +
                "gap P50 ${"%.0f".format(gaps.q(50))} / P90 ${"%.0f".format(gaps.q(90))} / max ${"%.0f".format(gaps.maxOrNull() ?: 0.0)} ms · " +
                "under 2 ms (same read) ${gaps.count { it < 2 }} · chars/delta P50 ${"%.0f".format(chars.q(50))} / P90 ${"%.0f".format(chars.q(90))} / max ${"%.0f".format(chars.maxOrNull() ?: 0.0)}" +
                " · reply ${outcome.getOrNull()?.length} chars, ${last?.outputTokens} output tokens" +
                (outcome.exceptionOrNull()?.let { " · failed: ${it.message?.take(100)}" } ?: ""))
        }
    }

    /**
     * The {{datetime}} fix, measured once (TEEMOON_BASELINE_CACHE=<fireworks model>): two turns of one chat a minute
     * apart. BEFORE, the system prompt carries the minute ("… 2026 at 9:01 AM" then "9:02 AM"); AFTER, it carries the
     * day and the minute rides on the user turn (iOS 224ec87). The second turn's cache share is the number.
     *
     * MEASURED 2026-10-19 (deepseek-v4p1-flash-1017, ~1.4k-token prompt): 0% both before AND after. Not the resolver:
     * Fireworks' prefix cache is per replica. With curl, an identical request cached 1246 of 1375 on repeats, but a NEW
     * turn behind the same system prompt cached 0 without `x-session-affinity` and 1234 of 1373 (90%) with it. So on
     * Fireworks the day resolver pays only alongside a per-chat affinity header — a wire change, iOS first.
     */
    @Test fun cacheBeforeAndAfterTheDayResolver() = runBlocking {
        val model = System.getenv("TEEMOON_BASELINE_CACHE").orEmpty()
        assumeTrue("set TEEMOON_BASELINE_CACHE to a Fireworks model to run", model.isNotEmpty())
        val key = hostKey("FIREWORKS") ?: return@runBlocking
        val persona = "You are a knowledgeable, direct AI assistant. Your primary commitment is to accuracy and truth. Prefer concise, substantive responses."
        // Long enough to be worth caching: a reference block every turn of this chat repeats.
        val reference = (1..120).joinToString(" ") { "Fact $it: the number $it is ${if (it % 2 == 0) "even" else "odd"}." }
        var affinity: String? = null
        suspend fun turn(system: String, history: List<WireMessage>, user: String): String? {
            var last: RequestResult? = null
            val events = object : StreamCallbacks { override fun onText(text: String) {}; override fun onSuccess(result: RequestResult) { last = result } }
            val transport = OpenAiTransport("https://api.fireworks.ai/inference/v1/chat/completions", model, apiKey = key, extraParams = mapOf("max_tokens" to "64"), providerName = "fireworks", affinityID = affinity)
            GenerationEngine(transport, emptyList(), events).run(listOf(WireMessage("system", "$system\n\n$persona\n\n$reference")) + history, user)
            val r = last?.phases?.firstOrNull { it.kind == TurnPhase.Kind.RESPONSE }
            return "prompt ${r?.tokens} cached ${r?.cachedTokens} (${TurnPhase.cachePercent(last?.phases.orEmpty())}%)"
        }
        val day = "The current date and time is Monday, October 19, 2026."
        for ((label, sys1, sys2, note1, note2) in listOf(
            listOf("before", "The current date and time is Monday, October 19, 2026 at 9:01 AM.", "The current date and time is Monday, October 19, 2026 at 9:02 AM.", "", ""),
            listOf("after", day, day, "\n\n[Current local time: 9:01 AM]", "\n\n[Current local time: 9:02 AM]"),
        )) {
            val salt = "${label}-${System.nanoTime()}"
            affinity = SessionAffinity.id(salt)   // as the app sends it since iOS 68aebea: one random id per chat   // a fresh chat each run, so a cache from a previous run cannot answer turn 1
            val q1 = "Chat $salt. Is 7 odd?"
            val first = turn(sys1, emptyList(), q1 + note1)
            val second = turn(sys2, listOf(WireMessage("user", q1), WireMessage("assistant", "Yes.")), "And 8?" + note2)
            println("[baseline] cache $label: turn 1 $first · turn 2 $second")
        }
    }
}
