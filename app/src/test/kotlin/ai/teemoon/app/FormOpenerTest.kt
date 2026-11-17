package ai.teemoon.app

// the maintainer on the Pixel, 2026-11-06: settings › cloud keys › edit, then cancel, showed the Where sheet at half height,
// and the push juddered. The form's parent was Where for every opener but Trust, so cancel landed there — and sheet A
// switched from cloud keys to Where underneath the push. iOS presents AddEditProviderView as a sheet over the screen
// that asked for it (PlacesKeysViews.swift `.sheet(item: $editing)`), so dismissing it returns there.

import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.WhereLocality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormOpenerTest {
    private val cloudKeys = Route.PlacesManage(WhereLocality.CLOUD)
    private val homeMachines = Route.PlacesManage(WhereLocality.HOME)

    @Test fun cancelFromSettingsCloudKeysReturnsToCloudKeys() {
        assertEquals(cloudKeys, FormOpener.parent(cloudKeys))
        // and cloud keys stays under the form while it is up: no sheet swaps in behind the push
        assertEquals(cloudKeys, FormOpener.sheetA(Route.EditPlace("p"), cloudKeys))
    }

    @Test fun homeMachinesEditAndAddReturnToHomeMachines() {
        assertEquals(homeMachines, FormOpener.parent(homeMachines))
        assertEquals(homeMachines, FormOpener.sheetA(Route.AddPlace(null, AddEditProviderModel.CustomStart.COMPUTER), homeMachines))
    }

    /** "add cloud key" (cloud keys › the provider picker › the form) returns to cloud keys, outside first run (pm 2026-11-06). */
    @Test fun addCloudKeyReturnsToCloudKeysOutsideFirstRun() {
        assertEquals(cloudKeys, FormOpener.fromCloudKeyPicker(firstRunPush = false))
        assertEquals(cloudKeys, FormOpener.parent(FormOpener.fromCloudKeyPicker(firstRunPush = false)))
        assertEquals(cloudKeys, FormOpener.sheetA(Route.AddPlace("near.ai", null), FormOpener.fromCloudKeyPicker(firstRunPush = false)))
        // first run's own push keeps its back stack (the picker, then the where sheet)
        assertNull(FormOpener.fromCloudKeyPicker(firstRunPush = true))
    }

    @Test fun trustAndWhereKeepTheirParents() {
        assertEquals(Route.Trust, FormOpener.parent(Route.Trust))
        // a form Where opened has no opener recorded: Where, as before
        assertEquals(Route.Where, FormOpener.parent(null))
        assertEquals(Route.Where, FormOpener.sheetA(Route.EditPlace("p"), null))
    }

    /**
     * The emulator import run (2026-11-06): the import page opened inside the settings sheet at half height, where
     * "choose file…" sat below the fold. v9's design: the import is its own full-height modal sheet (iOS presents it so),
     * over settings › chats.
     */
    @Test fun theImportIsItsOwnFullHeightSheetOverChatsSettings() {
        assertEquals(Route.NearImport, FormOpener.sheetB(Route.NearImport))
        assertEquals(Route.ChatsSettings, FormOpener.sheetA(Route.NearImport, null))
        assertNull(FormOpener.sheetB(Route.ChatsSettings))
        assertEquals(Route.EditPlace("p"), FormOpener.sheetB(Route.EditPlace("p")))
        assertNull(FormOpener.sheetB(Route.Where))
    }

    @Test fun sheetAOutsideTheFormIsUnchanged() {
        assertNull(FormOpener.sheetA(null, cloudKeys))
        assertNull(FormOpener.sheetA(Route.Chat("t"), cloudKeys))
        assertEquals(Route.Where, FormOpener.sheetA(Route.Browse("p"), cloudKeys))
        assertEquals(Route.Where, FormOpener.sheetA(Route.Download("p"), cloudKeys))
        assertEquals(cloudKeys, FormOpener.sheetA(cloudKeys, null))
    }

    /** The opener is saved state, like the route: a process death mid-edit still cancels to cloud keys. */
    @Test fun theOpenerSurvivesASaveAndRestore() {
        assertEquals(cloudKeys, RouteCodec.decode(RouteCodec.encode(cloudKeys)))
        assertEquals(Route.Trust, RouteCodec.decode(RouteCodec.encode(Route.Trust)))
    }
}
