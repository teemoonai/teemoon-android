// ports: android-only — the Android producer for docs/RENDER_RECORD.md.
//        Paparazzi, not a device: the record must be producible inside
//        `./gradlew test` or it will not be produced.
package ai.teemoon.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.sp
import java.io.File

class RenderRecordTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 360, screenHeight = 800,
            density = com.android.resources.Density.MEDIUM))

    private fun record(screen: String, content: @androidx.compose.runtime.Composable () -> Unit): RenderRecorder {
        val r = RenderRecorder(screen)
        paparazzi.snapshot { TeemoonTheme(dark = true) { CompositionLocalProvider(LocalRenderRecorder provides r) { content() } } }
        return r
    }

    /**
     * Written under build/, never committed. The Android half is REGENERATED
     * every run on purpose: only the iOS half is stored, so the two-frozen-
     * things-agree failure that killed ParityCaptureUITests cannot happen here.
     */
    private fun emit(r: RenderRecorder) {
        val dir = File("build/renderRecord").apply { mkdirs() }
        File(dir, "${r.screen}.json").writeText(r.toJson())
    }

    /** The chip this whole layer exists for — 24pt on iOS, a 32dp default here. */
    @Test fun emitTranscriptChip() {
        emit(record("chat.transcriptChip") {
            TranscriptChip("searching the web", TeemoonColors.label, TeemoonColors.groupedCard) {}
        })
    }

    @Test fun emitAlert() {
        emit(record("chat.alert.noProvider") {
            IosAlert("no place configured", "pick a place in settings first.", listOf("ok" to {}), onDismiss = {})
        })
    }

    /** 1.1's image prompts in 1.0.6's card (pm 2026-10-31): the second-download offer, and the refusal note with no title. */
    @Test fun emitImageDownloadOffer() {
        emit(record("chat.alert.imageDownload") {
            IosAlert(ai.teemoon.app.imaging.ImageCopy.OFFER_DOWNLOAD_TITLE, ai.teemoon.app.imaging.ImageCopy.OFFER_DOWNLOAD_BODY,
                listOf(ai.teemoon.app.imaging.ImageCopy.OFFER_DOWNLOAD to {}, ai.teemoon.app.imaging.ImageCopy.OFFER_NOT_NOW to {}), onDismiss = {})
        })
    }

    /** An export opened or shared to teemoon, held until the tap (IncomingExport; pm 2026-11-01). */
    @Test fun emitHeldExport() {
        emit(record("chats.alert.heldExport") { IosAlert("", ImportCopy.FOOTER, listOf(ImportCopy.ROW to {}, "cancel" to {}), onDismiss = {}) })
    }

    @Test fun emitImageRefusal() {
        emit(record("chat.alert.imageRefusal") { IosAlert(ai.teemoon.app.imaging.CantSeeRoute.alertTitle("glm-5p3"), ai.teemoon.app.imaging.CantSeeRoute.alertMessage(hasText = true), ai.teemoon.app.imaging.CantSeeRoute.alertButtons(hasText = true).map { it to {} }, onDismiss = {}) })
    }

    /** A choice of three in 1.0.6's alert (the maintainer 2026-10-31, "Revert all to 1.0.6"): stacked pills, the unencrypted send red. */
    @Test fun emitThreeChoiceAlert() {
        val choices = E2EESendChoice.choices(hardFailure = false, provenanceOnly = false)
        emit(record("chat.alert.e2ee") {
            IosAlert("end-to-end encryption unavailable", E2EESendChoice.message(hardFailure = false, provenanceOnly = false, cause = null), choices.map { it.label to {} }, onDismiss = {},
                destructive = choices.filter { it.isDestructive }.map { it.label }.toSet())
        })
    }

    /**
     * The values are RESOLVED, which is the whole point. A caller that overrides
     * the style must show up as the overridden size — a source-reading tool sees
     * the default and nothing else, which is why it could never have caught the
     * chip that drew 32dp against iOS's 24pt.
     */
    @Test fun aRowCarriesWhatWasActuallyDrawn() {
        val r = record("test.sectionLabel") { SectionLabel("places & keys") }
        val json = r.toJson()
        assertTrue("the text is the join key, normalised", json.contains("\"text\": \"places & keys\""))
        assertTrue("size is resolved, not declared", json.contains("\"fontSize\""))
        assertTrue("colour is a token, never a hex", json.contains("\"color\": \"secondaryLabel\""))
        assertTrue("padding is resolved", json.contains("\"leading\": 32"))
    }

    /** Byte-stable across runs, or a diff is noise rather than a finding. */
    @Test fun theRecordIsStableAcrossRuns() {
        val a = record("test.stable") { SectionLabel("one"); GroupFooter("two") }
        val b = record("test.stable") { SectionLabel("one"); GroupFooter("two") }
        assertEquals(a.toJson(), b.toJson())
    }

    /** Sorted by key: composition order is not a contract. */
    @Test fun orderIsTheKeysNotTheComposition() {
        val a = record("test.order") { SectionLabel("zulu"); SectionLabel("alpha") }
        val b = record("test.order") { SectionLabel("alpha"); SectionLabel("zulu") }
        assertEquals(a.toJson(), b.toJson())
        assertTrue(a.toJson().indexOf("alpha") < a.toJson().indexOf("zulu"))
    }

    /**
     * An element with neither text nor a glyph is DROPPED. A positional key would
     * silently pair two elements that were never the same one — the failure the
     * text join exists to remove.
     */
    @Test fun anUnjoinableElementIsNotRecorded() {
        val r = RenderRecorder("test.drop")
        r.row(kind = "divider")
        r.row(kind = "spacer", text = "   ")
        assertEquals(0, r.toJson().split("{\"kind\"").size - 1)
    }

    /**
     * A duplicate key is a HARD ERROR, not a merge. (screen, kind, text) is not
     * unique on a real screen — an `Other` labelled "save" around a `Button`
     * labelled "save", a caption repeated down a settings list — and merging,
     * dropping or last-wins all resolve that silently, which is the green the
     * text join was chosen to avoid. Raised by the teemoon-ios session.
     */
    @Test fun twoDifferentElementsUnderOneKeyStopTheProducer() {
        val r = RenderRecorder("test.collision")
        r.row(kind = "label", text = "save", fontSize = 13.0.sp)
        val e = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            r.row(kind = "label", text = "save", fontSize = 17.0.sp)
        }
        assertTrue("the message must name the clash", e.message!!.contains("share the key"))
    }

    /** A recomposition offering the identical row is not a collision. */
    @Test fun anIdenticalRepeatIsIdempotent() {
        val r = RenderRecorder("test.repeat")
        r.row(kind = "label", text = "save", fontSize = 13.0.sp)
        r.row(kind = "label", text = "save", fontSize = 13.0.sp)
        assertEquals(1, r.toJson().split("{\"kind\"").size - 1)
    }
}
