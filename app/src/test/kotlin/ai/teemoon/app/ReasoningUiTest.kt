// The reasoning design (the maintainer 2026-11-13, card composer-reasoning-chip, preview JWAYcGCPNbRKdJEKe9eNpi): developer mode
// only, everywhere. Off, no reasoning text anywhere — the chip is the name alone, the where sheet has no bar, model info
// has no reasoning row. On, for a model with a level: the chip reads "<model> <level>" (web glyph-only), the where sheet
// pins a bar that pushes to the levels, and model info is read-only.
package ai.teemoon.app

import ai.teemoon.inference.ReasoningControl
import ai.teemoon.inference.ReasoningEffort
import ai.teemoon.inference.ReasoningLevel
import ai.teemoon.providers.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningUiTest {
    @After fun forget() = ReasoningEffort.forgetRefusals()

    private val fireworks = Provider(id = "fw", name = "fireworks", endpoint = "https://api.fireworks.ai/inference/v1/chat/completions", model = "glm-5p3")
    private val brave = Provider.braveAnswers
    private fun ui(p: Provider, control: ReasoningControl, developerMode: Boolean, stored: String? = null, offered: List<String> = emptyList()) =
        ReasoningUi.of(ReasoningLevel.Setup(p, control), developerMode, stored, offered, modelName = p.model)

    // Developer mode off: nothing, anywhere

    @Test fun offTheChipIsTheNameAlone() = assertNull(ui(fireworks, ReasoningControl.Effort(null), developerMode = false, stored = "medium").chipLevel)

    @Test fun offTheWhereSheetHasNoBar() = assertNull(ui(fireworks, ReasoningControl.Effort(null), developerMode = false, stored = "medium").bar)

    @Test fun offModelInfoHasNoReasoningRow() =
        assertNull(ReasoningUi.modelInfoRow(developerMode = false, offered = listOf("high", "medium", "low")))

    // Developer mode on

    @Test fun onTheChipCarriesTheLevelInForce() {
        assertEquals("low", ui(fireworks, ReasoningControl.Effort(null), developerMode = true).chipLevel)
        assertEquals("medium", ui(fireworks, ReasoningControl.Effort(null), developerMode = true, stored = "medium").chipLevel)
        assertEquals("off", ui(fireworks, ReasoningControl.ThinkingSwitch, developerMode = true).chipLevel)
    }

    @Test fun onTheBarNamesTheModelAndTheLevel() {
        val bar = ui(fireworks, ReasoningControl.Effort(null), developerMode = true, stored = "medium").bar!!
        assertEquals("reasoning", bar.title)
        assertEquals("glm-5p3", bar.model)
        assertEquals("medium", bar.value)
        assertEquals("low (default)", ui(fireworks, ReasoningControl.Effort(null), developerMode = true).bar!!.value)
    }

    @Test fun onModelInfoIsReadOnlyLevels() {
        assertEquals("high, medium, low", ReasoningUi.modelInfoRow(developerMode = true, offered = listOf("high", "medium", "low")))
        assertNull("no list, no row", ReasoningUi.modelInfoRow(developerMode = true, offered = emptyList()))
    }

    /** A model with no level (nothing would be sent): no chip level and no bar, developer mode or not. */
    @Test fun aModelWithNoLevelShowsNone() {
        val none = ui(brave, ReasoningControl.None, developerMode = true)
        assertNull(none.chipLevel); assertNull(none.bar)
    }

    /** After a refusal nothing is sent: the chip has no level, the bar says the model's default, the list's footer says why. */
    @Test fun aRefusalShowsTheModelsDefault() {
        ReasoningEffort.noteRefused(fireworks)
        val r = ui(fireworks, ReasoningControl.Effort(null), developerMode = true, stored = "medium")
        assertNull(r.chipLevel)
        assertEquals("model's default", r.bar!!.value)
        // iOS parity (pm 2026-11-13): nothing is sent, so no level reads as picked and none can be; the refusal leads
        assertEquals(listOf(ReasoningLevel.refusedFooter("medium"), ReasoningLevel.LEVELS_FOOTER), r.levelsFooter)
        assertTrue("every level greyed", r.levels.none { it.enabled })
        assertTrue("no checkmark", r.levels.none { it.picked })
    }

    /** With developer mode off model info names no reasoning at all, its capability chips included (the maintainer "Yes yes"). */
    @Test fun offModelInfoHidesTheReasoningChips() {
        val features = listOf("tools", "reasoning", "reasoning (required)", "vision")
        assertEquals(listOf("tools", "vision"), ReasoningUi.modelInfoFeatures(developerMode = false, features = features))
        assertEquals(features, ReasoningUi.modelInfoFeatures(developerMode = true, features = features))
        // OpenRouter's request parameters name reasoning too
        val params = listOf("temperature", "reasoning", "include_reasoning", "reasoning_effort", "top_p")
        assertEquals(listOf("temperature", "top_p"), ReasoningUi.modelInfoParameters(developerMode = false, parameters = params))
        assertEquals(params, ReasoningUi.modelInfoParameters(developerMode = true, parameters = params))
    }

    /** Short phones open the where sheet full for everyone, as font ≥ 1.5 does (iOS: height ≤ 667 pt). */
    @Test fun onAShortPhoneTheWhereSheetOpensFull() {
        assertEquals(listOf(SheetDetents.LARGE), sheetTops(Route.Where, whereFirstRun = false, fontScale = 1f, screenHeightDp = SHORT_PHONE_DP))
        assertEquals(listOf(SheetDetents.LARGE), sheetTops(Route.ReasoningLevels, whereFirstRun = false, fontScale = 1f, screenHeightDp = 600))
        assertEquals(listOf(SheetDetents.WHERE, SheetDetents.LARGE), sheetTops(Route.Where, whereFirstRun = false, fontScale = 1f, screenHeightDp = SHORT_PHONE_DP + 1))
        assertEquals("other sheets as before", listOf(SheetDetents.MEDIUM, SheetDetents.LARGE), sheetTops(Route.Threads, whereFirstRun = false, fontScale = 1f, screenHeightDp = 600))
    }

    /**
     * The bar is one line, "reasoning … <level> ›": the model is named right above it in the sheet (the maintainer 2026-11-13: "Is
     * naming the model in the reasoning pull down redundant", yes). At accessibility sizes the level goes under the title.
     */
    @Test fun theBarIsReasoningAndTheLevel() {
        val bar = ui(fireworks, ReasoningControl.Effort(null), developerMode = true).bar!!
        assertEquals(ReasoningUi.Bar.Layout(subtitle = null, value = "low (default)"), bar.trailing(oneLineFits = true))
        // The maintainer 2026-11-13 ("Do 1 and 3"): one line wherever it fits, at any size; the level goes under only when it can't
        assertEquals(ReasoningUi.Bar.Layout(subtitle = "low (default)", value = null), bar.trailing(oneLineFits = false))
    }

    /**
     * The where sheet opens at about two thirds of the screen (the maintainer 2026-11-13, "Ok make bigger"; iOS moves off .medium):
     * at half height 2-3 model rows showed under its header, at two thirds 4-5. Its levels page is the same sheet. Other
     * sheets keep half; full height at font >= 1.5 and on short phones stays.
     */
    @Test fun theWhereSheetOpensAtTwoThirds() {
        assertEquals(2f / 3f, 1f - SheetDetents.WHERE, 0.01f)
        for (r in listOf(Route.Where, Route.ReasoningLevels))
            assertEquals("$r", listOf(SheetDetents.WHERE, SheetDetents.LARGE), sheetTops(r, whereFirstRun = false, fontScale = 1f, screenHeightDp = 900))
        assertEquals("the first run stays taller", listOf(SheetDetents.FIRST_RUN, SheetDetents.LARGE), sheetTops(Route.Where, whereFirstRun = true, fontScale = 1f, screenHeightDp = 900))
        assertEquals("other sheets as before", listOf(SheetDetents.MEDIUM, SheetDetents.LARGE), sheetTops(Route.Settings, whereFirstRun = false, fontScale = 1f, screenHeightDp = 900))
    }

    /** At rest at two thirds the chat behind does not recede; it does at full height (design review of 31373b33). */
    @Test fun theChatRecedesOnlyAsTheWhereSheetRisesPastWhereItRests() {
        val where = sheetTops(Route.Where, whereFirstRun = false, fontScale = 1f, screenHeightDp = 900)
        assertEquals(0f, sheetLift(SheetDetents.WHERE, where), 0.001f)
        assertEquals(1f, sheetLift(SheetDetents.LARGE, where), 0.001f)
        val firstRun = sheetTops(Route.Where, whereFirstRun = true, fontScale = 1f, screenHeightDp = 900)
        assertEquals(0f, sheetLift(SheetDetents.FIRST_RUN, firstRun), 0.001f)
        assertEquals("a sheet at half as before", 0f, sheetLift(SheetDetents.MEDIUM, listOf(SheetDetents.MEDIUM, SheetDetents.LARGE)), 0.001f)
        assertEquals("a sheet that opens full recedes all the way", 1f, sheetLift(SheetDetents.LARGE, listOf(SheetDetents.LARGE)), 0.001f)
        assertEquals("a sheet resting under half starts at half, as before", 0f, sheetLift(0.6f, listOf(0.66f, SheetDetents.MEDIUM, SheetDetents.LARGE)), 0.001f)
    }

    /** The bar is the sheet's frame, not another row: its band is neither the sheet's ground nor the groups' fill. */
    @Test fun theBandIsItsOwnSurface() {
        for (p in listOf(TeemoonPalette.dark(AppTintColor.ORANGE).elevated(), TeemoonPalette.light(AppTintColor.ORANGE).elevated())) {
            val band = sheetBand(p)
            assertTrue("dark=${p.dark}: band $band vs ground ${p.groupedBackground} and card ${p.groupedCard}", band != p.groupedBackground && band != p.groupedCard)
        }
    }

    /** At accessibility sizes the where sheet opens at full height: at half, its header controls left about one model row. */
    @Test fun atAccessibilitySizesTheWhereSheetOpensFull() {
        assertEquals(listOf(SheetDetents.LARGE), sheetTops(Route.Where, whereFirstRun = false, fontScale = ComposerPanel.ACCESSIBILITY_SCALE))
        assertEquals(listOf(SheetDetents.LARGE), sheetTops(Route.Where, whereFirstRun = true, fontScale = 2f))
        assertEquals(listOf(SheetDetents.LARGE), sheetTops(Route.ReasoningLevels, whereFirstRun = false, fontScale = 2f))
        assertEquals("default size: two thirds, then full", listOf(SheetDetents.WHERE, SheetDetents.LARGE), sheetTops(Route.Where, whereFirstRun = false, fontScale = 1f))
        assertEquals(listOf(SheetDetents.FIRST_RUN, SheetDetents.LARGE), sheetTops(Route.Where, whereFirstRun = true, fontScale = 1f))
        assertEquals("other sheets as before", listOf(SheetDetents.MEDIUM, SheetDetents.LARGE), sheetTops(Route.Threads, whereFirstRun = false, fontScale = 2f))
        assertEquals(listOf(SheetDetents.LARGE), sheetTops(Route.Trust, whereFirstRun = false, fontScale = 1f))
    }

    @Test fun theLevelsListHasTheModelsLevelsAndItsFooter() {
        val r = ui(fireworks, ReasoningControl.OpenRouter, developerMode = true, stored = "xhigh", offered = listOf("xhigh", "high", "medium", "low"))
        assertEquals(listOf("xhigh", "high", "medium", "low (default)"), r.levels.map { it.label })
        assertEquals(listOf(true, false, false, false), r.levels.map { it.picked })
        assertEquals(listOf("higher levels think longer before answering, so replies take longer and can cost more."), r.levelsFooter)
        assertTrue(r.levels.all { it.enabled })
    }
}
