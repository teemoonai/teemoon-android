// The reasoning design's surfaces (the maintainer 2026-11-13, card composer-reasoning-chip; the approved preview
// JWAYcGCPNbRKdJEKe9eNpi), as Paparazzi frames: the composer's chip with the level, the where sheet's pinned bar at half
// and full height, the levels page (and after a refusal), and model info read-only — with developer mode off, model info
// draws no reasoning row, pixel for pixel the page of a model with no levels. The frames go to build/reasoning-chip-card
// for the card; ReasoningUiTest holds what each surface shows, on and off.
package ai.teemoon.app

import ai.teemoon.inference.ReasoningControl
import ai.teemoon.inference.ReasoningEffort
import ai.teemoon.inference.ReasoningLevel
import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereGetPolicy
import ai.teemoon.providers.WhereLocality
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.resources.Density
import java.awt.image.BufferedImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private val fireworks = Provider.fireworks.copy(model = "accounts/fireworks/models/glm-5p3-flash",
    equippedModels = listOf("accounts/fireworks/models/glm-5p3", "accounts/fireworks/models/glm-5p3-flash", "accounts/fireworks/models/deepseek-v4p1-flash",
        "accounts/fireworks/models/kimi-k3", "accounts/fireworks/models/qwen3p6-235b", "accounts/fireworks/models/gpt-oss-120b"))

/** The app's one name for a model (the where rows, the chip and the bar all draw this); the fixture typed its own once. */
private val shownName = ai.teemoon.providers.WhereProviderPresentation.modelLabel(fireworks)

private fun ui(stored: String? = null, control: ReasoningControl = ReasoningControl.Effort(null), offered: List<String> = emptyList()) =
    ReasoningUi.of(ReasoningLevel.Setup(fireworks, control), developerMode = true, stored = stored, offered = offered, modelName = shownName)

abstract class ReasoningDesignCase(private val widthDp: Int, private val fontScale: Float = 1f, private val dark: Boolean = true) {
    private val frames = mutableListOf<BufferedImage>()
    private val capture = object : SnapshotHandler {
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int) = object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) { frames += image }
            override fun close() = Unit
        }
        override fun close() = Unit
    }
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = widthDp * 2, screenHeight = 1700, density = Density.XHIGH, fontScale = fontScale),
        snapshotHandler = capture)

    @Before @After fun forget() { ReasoningEffort.forgetRefusals() }

    private fun render(name: String, content: @Composable () -> Unit): BufferedImage {
        paparazzi.snapshot { TeemoonTheme(dark = dark) { content() } }
        return frames.last().also { img ->
            java.io.File("build/reasoning-chip-card").apply { mkdirs() }.let { d -> javax.imageio.ImageIO.write(img, "png", java.io.File(d, "${javaClass.simpleName}-$name.png")) }
        }
    }
    private fun BufferedImage.pixels() = getRGB(0, 0, width, height, null, 0, width).toList()

    @Composable private fun Chat(level: String?) = ChatScreen(focusOnEmpty = false, vm = remember { edgesVm(short = true) }, title = "is it private?", hasProvider = true,
        onOpenSettings = {}, webSearchState = WebSearchChipState.OFF, whereModel = shownName, wherePlace = "fireworks", whereLocality = WhereLocality.CLOUD,
        onOpenWhere = {}, pickImages = { _, _ -> emptyList() }, whereLevel = level)

    /**
     * The chip: the name alone with developer mode off; "<model> <level>" on, web its glyph. At accessibility sizes the
     * chips are glyphs already (no name), so the level is spoken with the chip's label, not drawn.
     */
    @Test fun theComposerChip() {
        val off = render("chip-developer-off") { Chat(level = null) }
        val on = render("chip-low") { Chat(level = ui().chipLevel) }
        if (ComposerPanel.iconOnlyChips(fontScale)) assertEquals("icon-only chips draw no level", off.pixels(), on.pixels())
        else assertNotEquals("the level shows on the chip", off.pixels(), on.pixels())
        render("chip-medium") { Chat(level = ui(stored = "medium").chipLevel) }
    }

    /** On the sheet's own (elevated) palette, as MainActivity presents it, so the band is the one the phone draws. */
    @Composable private fun Where(heightDp: Int, bar: ReasoningUi.Bar?) = androidx.compose.runtime.CompositionLocalProvider(LocalTeemoonPalette provides LocalTeemoonPalette.current.elevated()) { Surface(Modifier.height(heightDp.dp)) {
        WhereSheet(providers = listOf(fireworks), selectedId = fireworks.id, problem = null, onSelect = {}, onBack = {}, hasKey = { true },
            policyFor = { f -> WhereGetPolicy(f, listOf(fireworks), true, emptyMap(), { "k" }, { "" }) }, onRefreshHome = {}, reasoningBar = bar)
    } }

    /** The bar is pinned to the sheet's bottom at the half detent and at full height; the list scrolls under it. */
    @Test fun theWhereSheetsBar() {
        val half = (850 * (1 - SheetDetents.MEDIUM)).toInt()
        val none = render("where-half-developer-off") { Where(half, bar = null) }
        val bar = render("where-half") { Where(half, bar = ui().bar) }
        assertNotEquals("the bar shows", none.pixels(), bar.pixels())
        render("where-full") { Where((850 * (1 - SheetDetents.LARGE)).toInt(), bar = ui(stored = "medium").bar) }
        // where it opens now (the maintainer 2026-11-13, "Ok make bigger"): two thirds of the screen
        render("where-two-thirds") { Where((850 * (1 - SheetDetents.WHERE)).toInt(), bar = ui().bar) }
    }

    @Test fun theLevelsPage() {
        render("levels") { ReasoningLevelsPage(ui(stored = "medium"), onPick = {}, onBack = {}) }
        render("levels-openrouter") { ReasoningLevelsPage(ui(stored = "xhigh", control = ReasoningControl.OpenRouter, offered = listOf("xhigh", "high", "medium", "low", "minimal", "none")), onPick = {}, onBack = {}) }
        render("levels-switch") { ReasoningLevelsPage(ui(control = ReasoningControl.ThinkingSwitch), onPick = {}, onBack = {}) }
        ReasoningEffort.noteRefused(fireworks)
        render("levels-refused") { ReasoningLevelsPage(ui(stored = "medium"), onPick = {}, onBack = {}) }
        render("where-half-refused") { Where((850 * (1 - SheetDetents.MEDIUM)).toInt(), bar = ui(stored = "medium").bar) }
    }

    private fun model(efforts: List<String>) = KnownModel(fireworks.model, "glm-5.3-flash", "z-ai", "\$0.55/\$2.19", contextWindow = "200K", knowledgeCutoff = "2026-03", reasoningEfforts = efforts)

    /** Model info is read-only: developer mode on lists the levels; off, no reasoning row at all — the page of a model with none. */
    @Test fun modelInfoIsReadOnly() {
        val efforts = listOf("high", "medium", "low")
        val noLevels = render("model-info-no-levels") { ModelDetailScreen(model(emptyList()), confidentiality = null, onCopyID = {}, onBack = {}, developerMode = true) }
        val off = render("model-info-developer-off") { ModelDetailScreen(model(efforts), confidentiality = null, onCopyID = {}, onBack = {}, developerMode = false) }
        assertEquals("developer mode off draws no reasoning row", noLevels.pixels(), off.pixels())
        val on = render("model-info-developer-on") { ModelDetailScreen(model(efforts), confidentiality = null, onCopyID = {}, onBack = {}, developerMode = true) }
        assertNotEquals("developer mode on lists the levels", off.pixels(), on.pixels())
    }
}

class ReasoningDesignTest : ReasoningDesignCase(412)
class ReasoningDesignSmallestWidthTest : ReasoningDesignCase(360)
class ReasoningDesignLightTest : ReasoningDesignCase(412, dark = false)
class ReasoningDesignLargestTextTest : ReasoningDesignCase(412, fontScale = 2f)
class ReasoningDesignSmallestLargestTextTest : ReasoningDesignCase(360, fontScale = 2f)
