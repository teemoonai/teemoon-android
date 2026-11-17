// ports: teemoonTests/ReasoningLevelTests.swift @ 0e98aaf1 (all but aChoiceCountsOnlyInDeveloperMode, which is
//        app/src/test/.../ReasoningLevelStoreTest.kt: the storage is AppSettings' here)
//
// Developer mode's per-model reasoning level (docs/REASONING_LEVEL.md, the maintainer 2026-11-13): low unless chosen, the
// model's own levels, and with developer mode off nothing changes on the wire.
package ai.teemoon.inference

import ai.teemoon.providers.Provider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ReasoningLevelTest {
    @Before fun forget() { ReasoningEffort.forgetRefusals(); ReasoningControl.forget(); ReasoningLevel.chooser = { null } }
    @After fun forgetAfter() = forget()

    private fun provider(endpoint: String, model: String = "m") = Provider(id = "p", name = "p", endpoint = endpoint, model = model, requiresAPIKey = true)
    private val fireworks = provider("https://api.fireworks.ai/inference/v1/chat/completions")
    private val near = provider("https://cloud-api.near.ai/v1/chat/completions")
    private val nvidia = provider("https://integrate.api.nvidia.com/v1/chat/completions")
    private val openRouter get() = provider("https://openrouter.ai/api/v1/chat/completions").also { ReasoningControl.note(ReasoningControl.OpenRouter, it) }
    /** Refusals are launch-wide state: a refusal test gets a model of its own. */
    private fun refusing() = provider("https://api.fireworks.ai/inference/v1/chat/completions", model = "refused-${UUID.randomUUID()}")

    private fun Map<String, JsonElement>?.effort() = (this?.get("reasoning_effort") as? JsonPrimitive)?.content
    private fun Map<String, JsonElement>?.nested() = ((this?.get("reasoning") as? JsonObject)?.get("effort") as? JsonPrimitive)?.content
    private fun Map<String, JsonElement>?.thinking() = ((this?.get("chat_template_kwargs") as? JsonObject)?.get("enable_thinking") as? JsonPrimitive)?.content?.toBoolean()

    // The menu

    @Test fun eachControlOffersItsOwnLevels() {
        assertEquals(listOf("low", "medium", "high"), ReasoningLevel.menu(ReasoningControl.Effort(null), emptyList()))
        assertEquals(listOf("off", "on"), ReasoningLevel.menu(ReasoningControl.ThinkingSwitch, emptyList()))
        assertEquals(listOf("off", "on"), ReasoningLevel.menu(ReasoningControl.OnDevice, emptyList()))
        assertTrue("nothing would be sent", ReasoningLevel.menu(ReasoningControl.None, listOf("low", "high")).isEmpty())
    }

    @Test fun openRouterOffersTheModelsListInItsOrder() {
        assertEquals(listOf("xhigh", "high", "medium", "low", "none"),
            ReasoningLevel.menu(ReasoningControl.OpenRouter, listOf("xhigh", "high", "medium", "low", "none")))
        assertEquals(listOf("low", "medium", "high"), ReasoningLevel.menu(ReasoningControl.OpenRouter, emptyList()))
    }

    @Test fun theDefaultIsTheControlsLow() {
        assertEquals("low", ReasoningLevel.defaultLevel(ReasoningControl.Effort(null)))
        assertEquals("low", ReasoningLevel.defaultLevel(ReasoningControl.OpenRouter))
        assertEquals("off", ReasoningLevel.defaultLevel(ReasoningControl.ThinkingSwitch))
        assertEquals("off", ReasoningLevel.defaultLevel(ReasoningControl.OnDevice))
    }

    /** With no choice in force the body is byte for byte today's, so the prompt cache cannot move. */
    @Test fun noChoiceSendsTodaysBytes() {
        for (p in listOf(fireworks, near, nvidia)) for (effort in ReasoningEffort.entries)
            assertEquals("${p.endpoint} $effort", effort.bodyFields(p).toString(), effort.bodyFields(p, chosen = null).toString())
    }

    /** The transport reads the app's choice per request: what [ReasoningLevel.chooser] answers is what is sent. */
    @Test fun theTransportSendsTheAppsChoice() {
        ReasoningLevel.chooser = { if (it.model == fireworks.model) "medium" else null }
        assertEquals("medium", ReasoningEffort.FAST.bodyFields(fireworks).effort())
        assertEquals("medium", ReasoningEffort.For(ReasoningEffort.FAST, fireworks).bodyFields().effort())
    }

    // What is sent

    @Test fun aChosenEffortReplacesLow() {
        assertEquals("medium", ReasoningEffort.FAST.bodyFields(fireworks, chosen = "medium").effort())
        assertNull("near.ai's cap is Think Harder's only", ReasoningEffort.FAST.bodyFields(near, chosen = "medium")?.get("max_tokens"))
    }

    @Test fun openRouterSendsTheChosenLevelNested() {
        val p = openRouter
        assertEquals("xhigh", ReasoningEffort.FAST.bodyFields(p, chosen = "xhigh").nested())
        assertEquals("none only where the model lists it, and only by choice", "none", ReasoningEffort.FAST.bodyFields(p, chosen = "none").nested())
    }

    @Test fun aSwitchSendsTheChosenSide() {
        assertEquals(true, ReasoningEffort.FAST.bodyFields(nvidia, chosen = "on").thinking())
        assertEquals(false, ReasoningEffort.FAST.bodyFields(nvidia, chosen = "off").thinking())
    }

    @Test fun onDeviceThinksWhenChosenOn() {
        assertFalse(ReasoningLevel.thinksOnDevice(ReasoningEffort.FAST, chosen = null))
        assertTrue(ReasoningLevel.thinksOnDevice(ReasoningEffort.FAST, chosen = "on"))
        assertTrue(ReasoningLevel.thinksOnDevice(ReasoningEffort.THINK_HARDER, chosen = null))
        assertTrue("Think Harder still thinks", ReasoningLevel.thinksOnDevice(ReasoningEffort.THINK_HARDER, chosen = "off"))
    }

    /** Think Harder never thinks less than the plain send. */
    @Test fun thinkHarderSendsHighOrTheChosenLevelAboveIt() {
        assertEquals("high", ReasoningEffort.THINK_HARDER.bodyFields(fireworks, chosen = "medium").effort())
        assertEquals("high", ReasoningEffort.THINK_HARDER.bodyFields(fireworks, chosen = "low").effort())
        assertEquals("xhigh", ReasoningEffort.THINK_HARDER.bodyFields(openRouter, chosen = "xhigh").nested())
        assertEquals(JsonPrimitive(8192), ReasoningEffort.THINK_HARDER.bodyFields(near, chosen = "medium")?.get("max_tokens"))
        assertEquals(true, ReasoningEffort.THINK_HARDER.bodyFields(nvidia, chosen = "off").thinking())
    }

    /** A refusal is today's path: no level at all for this launch, and the row says so. */
    @Test fun aRefusedLevelSendsNothingAndIsShown() {
        val p = refusing()
        assertFalse(ReasoningEffort.wasRefused(p))
        ReasoningEffort.noteRefused(p)
        assertNull(ReasoningEffort.FAST.bodyFields(p, chosen = "medium"))
        assertTrue(ReasoningEffort.wasRefused(p))
    }

    // The detail page's row

    @Test fun theRowShowsOnlyInDeveloperModeAndWhereALevelIsSent() {
        val p = refusing()
        val control = ReasoningControl.current(p)
        assertNull(ReasoningLevel.Row.of(ReasoningLevel.Setup(p, control), developerMode = false, offered = emptyList()))
        assertNull("no setup: today's read-only row", ReasoningLevel.Row.of(null, developerMode = true, offered = emptyList()))
        assertNull("nothing would be sent", ReasoningLevel.Row.of(ReasoningLevel.Setup(Provider.braveAnswers, ReasoningControl.current(Provider.braveAnswers)), developerMode = true, offered = emptyList()))
        val row = ReasoningLevel.Row.of(ReasoningLevel.Setup(p, control), developerMode = true, offered = emptyList())!!
        assertEquals(listOf("low", "medium", "high"), row.levels)
        assertEquals("low (default)", row.label("low"))
        assertEquals("medium", row.label("medium"))
        assertEquals("off (default)", ReasoningLevel.Row.of(ReasoningLevel.Setup(nvidia, ReasoningControl.current(nvidia)), developerMode = true, offered = emptyList())!!.label("off"))
        assertFalse(row.refused)
        ReasoningEffort.noteRefused(p)
        assertTrue(ReasoningLevel.Row.of(ReasoningLevel.Setup(p, control), developerMode = true, offered = emptyList())!!.refused)
    }

    /** A stored level the setup's control can't send counts as no choice, never a bad field. */
    @Test fun aLevelTheControlCantSendIsIgnored() {
        assertNull(ReasoningLevel.usable("on", ReasoningControl.Effort(null)))
        assertNull(ReasoningLevel.usable("medium", ReasoningControl.ThinkingSwitch))
        assertEquals("medium", ReasoningLevel.usable("medium", ReasoningControl.Effort(null)))
        assertEquals("xhigh", ReasoningLevel.usable("xhigh", ReasoningControl.OpenRouter))
        assertEquals("low", ReasoningEffort.FAST.bodyFields(fireworks, chosen = "on").effort())
    }

    // Design review 2026-11-13

    /** Low is what is sent without a choice, so it is always on the menu: a list without it left the value blank. */
    @Test fun openRouterKeepsLowOnTheMenu() {
        assertEquals(listOf("high", "medium", "low"), ReasoningLevel.menu(ReasoningControl.OpenRouter, listOf("high", "medium")))
        val row = ReasoningLevel.Row.of(ReasoningLevel.Setup(fireworks, ReasoningControl.OpenRouter), developerMode = true, offered = listOf("high", "medium"))!!
        assertEquals("low", row.value(stored = null))
    }

    /** A stored level the list no longer has shows as the default. */
    @Test fun aStoredLevelOffTheListShowsTheDefault() {
        val row = ReasoningLevel.Row.of(ReasoningLevel.Setup(fireworks, ReasoningControl.current(fireworks)), developerMode = true, offered = emptyList())!!
        assertEquals("medium", row.value(stored = "medium"))
        assertEquals("low", row.value(stored = "xhigh"))
        assertEquals("low", row.value(stored = null))
    }

    /** After a refusal nothing goes on the wire, so the row says the model's own default, and which pick was turned down. */
    @Test fun aRefusalNamesTheRefusedLevelAndShowsNone() {
        val p = refusing()
        val setup = ReasoningLevel.Setup(p, ReasoningControl.current(p))
        assertNull(ReasoningLevel.Row.of(setup, developerMode = true, offered = emptyList())!!.footer(stored = "medium"))
        ReasoningEffort.noteRefused(p)
        val row = ReasoningLevel.Row.of(setup, developerMode = true, offered = emptyList())!!
        assertEquals("model's default", row.shown(stored = "medium"))
        assertEquals("the server refused medium, so teemoon leaves the setting out and this model uses its own default for now.", row.footer(stored = "medium"))
        assertEquals("the server refused low, so teemoon leaves the setting out and this model uses its own default for now.", row.footer(stored = null))
    }
}
