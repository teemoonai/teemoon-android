// android-only: the request-path check after DisclosurePolicy (a6e80c8f; pm 2026-11-12, "live cache check on Fireworks").
//
// LIVE. Costs three small GLM 5.3 Flash requests. Never part of the offline gate: it runs only when TEEMOON_LIVE=1 and
// ~/.FIREWORKS_API_KEY exists (read here, never printed), and skips otherwise.
//
//     TEEMOON_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*FireworksDisclosureCacheLiveTest*' -i
package ai.teemoon.app

import ai.teemoon.inference.OpenAiTransport
import ai.teemoon.inference.WireMessage
import ai.teemoon.inference.wireContent
import ai.teemoon.providers.DisclosurePolicy
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FireworksDisclosureCacheLiveTest {
    /**
     * A three-turn Fireworks chat built as ChatViewModel builds it for a plain-TLS cloud: the persona's {{date}} as the
     * UTC day, every user turn with its "[Sent at HH:mm UTC]" from its own timestamp. Sends 2 and 3 must be served from
     * the provider's cache for most of their prompt — the day-level date and the fixed notes keep the prefix byte-stable.
     * The system prompt carries a per-run salt, so an earlier run's cache cannot flatter this one (fireworks-cache-effort).
     */
    @Test fun sendsTwoAndThreeHitTheCache() = runBlocking {
        val keyFile = File(System.getProperty("user.home"), ".FIREWORKS_API_KEY")
        assumeTrue("set TEEMOON_LIVE=1 and keep ~/.FIREWORKS_API_KEY to run", System.getenv("TEEMOON_LIVE") == "1" && keyFile.isFile)
        val key = keyFile.readText().trim()
        val place = Provider.fireworks.copy(model = "accounts/fireworks/models/glm-5p3-flash")
        assertEquals("Fireworks is a plain-TLS cloud", DisclosurePolicy.Tier.MINIMISED, DisclosurePolicy.of(WhereLocality.of(place), sealed = place.claimsEndToEndEncryption).tier)
        // TEEMOON_TIER=private: the same chat in the local format, the baseline to compare a miss against
        val disclosure = if (System.getenv("TEEMOON_TIER") == "private") DisclosurePolicy(DisclosurePolicy.Tier.PRIVATE)
            else DisclosurePolicy.of(WhereLocality.of(place), sealed = place.claimsEndToEndEncryption)
        val salt = java.util.UUID.randomUUID().toString().take(8)
        // a persona long enough to clear the provider's cache minimum (a short prompt is never cached): stable filler
        val persona = AppSettings.DEFAULT_SYSTEM_PROMPT + "\n\n(run $salt)\n\n" + (1..60).joinToString("\n") { "Guideline $it: answer plainly, cite sources when you search, and keep numbers exact." }
        val system = ChatGeneration.resolvePromptTemplates(persona, disclosure = disclosure)
        println("[live] tier ${disclosure.tier}: ${system.lineSequence().first()}")
        val t0 = System.currentTimeMillis() - 120_000
        val asks = listOf("Name one prime number.", "Name another, larger one.", "Add the two.")
        val history = ArrayList<WireMessage>()
        val cached = ArrayList<Pair<Int?, Int?>>()
        for ((i, ask) in asks.withIndex()) {
            val user = WireMessage("user", ask + ChatGeneration.timeNote(t0 + i * 40_000L, disclosure))
            val wire = ChatGeneration.buildWireMessages(history + user + WireMessage("user", ""), system, maxMessages = null)
                .map { mapOf("role" to it.role, "content" to wireContent(it.content, it.images)) } + mapOf("role" to "user", "content" to user.content)
            // the thread's affinity id, as ChatViewModel sends it (SessionAffinity): Fireworks routes the chat to one cache
            val t = OpenAiTransport(place.chatCompletionsUrl, place.model, apiKey = key, providerName = place.name, affinityID = ai.teemoon.inference.SessionAffinity.id("live-$salt"))
            // the web-search tool as the app offers it, its "Today is" the request's toolDay (the UTC day here)
            val tool = ai.teemoon.inference.BraveWebSearchTool(apiKey = "", today = disclosure.toolDay(java.time.Instant.ofEpochMilli(t0)))
            val r = t.runTurn(wire, true, listOf(tool)) {}
            cached += r.report.promptTokens to r.report.cachedPromptTokens
            println("[live] send ${i + 1}: prompt ${r.report.promptTokens} cached ${r.report.cachedPromptTokens} · note \"${user.content.substringAfter("\n\n")}\"")
            history += user; history += WireMessage("assistant", r.content)
        }
        for (i in 1..2) {
            val (prompt, hit) = cached[i]
            assertTrue("send ${i + 1} reported no cache figure", prompt != null && hit != null)
            assertTrue("send ${i + 1}: only $hit of $prompt cached", hit!! * 2 >= prompt!!)
        }
    }
}
