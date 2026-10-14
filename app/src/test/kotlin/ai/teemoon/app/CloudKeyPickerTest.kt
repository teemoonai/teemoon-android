// ports: teemoon/Views/Settings/CloudKeyProviderPickerView.swift @ v1.0.4
package ai.teemoon.app

import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import ai.teemoon.providers.WhereProviderPresentation
import ai.teemoon.providers.presetDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyPickerTest {

    @Test fun everyOfferedPresetBecomesARow() {
        val rows = CloudKeyPicker.rows(Provider.presets)
        assertEquals(Provider.presets.size, rows.size)
        assertEquals(Provider.presets.map { it.name }, rows.map { it.presetName })
    }

    /** Titles are lowercase, like every other title in this app. */
    @Test fun titlesAreLowercase() {
        for (row in CloudKeyPicker.rows(Provider.presets)) assertEquals(row.title.lowercase(), row.title)
    }

    /**
     * Every shipped preset has a caption. A row with no second line next to rows that have one reads as a provider
     * about which nothing is known, which is the opposite of the message — these are the vetted ones.
     */
    @Test fun everyPresetHasACaption() {
        for (row in CloudKeyPicker.rows(Provider.presets)) assertNotNull("${row.presetName} has no caption", row.caption)
    }

    /**
     * The picker's caption is the SHORT one, not the add form's footer. Pinned as a length bound rather than as
     * exact strings: the row caps at two lines, and `presetDescription` runs past that on every preset.
     */
    @Test fun captionsAreShortEnoughForATwoLineRow() {
        for (row in CloudKeyPicker.rows(Provider.presets)) {
            assertTrue("${row.presetName}: ${row.caption!!.length} chars is a paragraph, not a caption", row.caption.length <= 64)
        }
        // …and it really is a different string from the form's, or this test passes by accident.
        val nearAI = Provider.presets.first { it.id == Provider.nearAI.id }
        assertTrue(WhereProviderPresentation.presetCaption(nearAI) != nearAI.presetDescription)
    }

    /**
     * The e2ee tag comes off the preset's own capabilities. The UI never composes trust language (§9.5), so a new
     * claim on this row would have to be a new capability first.
     */
    @Test fun theE2eeTagTracksTheCapability() {
        for (row in CloudKeyPicker.rows(Provider.presets)) {
            val preset = Provider.presets.first { it.name == row.presetName }
            assertEquals(row.presetName, ProviderCapability.END_TO_END_ENCRYPTION in preset.capabilities, row.showsE2EE)
        }
        // near.ai is the one that carries it — if that stops being true the tag is not tracking anything.
        assertTrue(CloudKeyPicker.rows(Provider.presets).any { it.showsE2EE })
    }

    /** No presets left to offer is a STATE, not an error: the copy says where to go instead. */
    @Test fun theEmptyListHasSomewhereToSend() {
        assertTrue(CloudKeyPicker.rows(emptyList()).isEmpty())
        assertTrue(CloudKeyPicker.NOTHING_LEFT.contains("places & keys"))
    }

    /**
     * The form pops back here the same way on save and on cancel, so the picker decides by comparing ids.
     * First appearance (`before` empty) is not an add — without that guard the picker would dismiss itself
     * before the user had picked anything, since every configured provider looks "new" against an empty set.
     */
    @Test fun aSavedSetupDismissesThePickerAndACancelDoesNot() {
        assertTrue("a new id appeared", CloudKeyPicker.dismissAfterReturn(setOf("a"), setOf("a", "b")))
        assertFalse("cancelled: same ids", CloudKeyPicker.dismissAfterReturn(setOf("a"), setOf("a")))
        assertFalse("first appearance", CloudKeyPicker.dismissAfterReturn(emptySet(), setOf("a", "b")))
        assertFalse("first appearance, nothing configured", CloudKeyPicker.dismissAfterReturn(emptySet(), emptySet()))
    }

    /** A DELETE while the form was up is not an add, and must not dismiss the picker. */
    @Test fun losingAProviderIsNotAnAdd() {
        assertFalse(CloudKeyPicker.dismissAfterReturn(setOf("a", "b"), setOf("a")))
    }
}
