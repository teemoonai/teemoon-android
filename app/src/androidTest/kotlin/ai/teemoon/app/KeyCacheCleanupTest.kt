package ai.teemoon.app

// android-only: a server's saved model lists go with its key. The lists are scoped by a hash of the key, and
// LiveCatalogStore.forget had no caller, so removing a provider (or replacing its key) left the old scope on disk
// (the local audit, an INFO since 3910, 2026-10-13).

import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.Provider
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyCacheCleanupTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    private fun state(): AppState {
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).close()
        return assertNotNull((ctx.applicationContext as TeemoonApplication).state).let { (ctx.applicationContext as TeemoonApplication).state!! }
    }

    private val rows = listOf(KnownModel(id = "accounts/t/models/one", displayName = "one", vendor = "v", price = "$1.00/$2.00"))

    @Test fun removingAProviderDropsItsSavedLists() {
        val app = state()
        val p = Provider.fireworks.copy(id = "key-cache-test")
        ins.runOnMainSync { app.addProvider(p, "fw-test-key-0000000000") }
        try {
            app.catalogStore.record(rows, p, "fw-test-key-0000000000")
            assertEquals(1, app.catalogStore.count(p, "fw-test-key-0000000000"))
            ins.runOnMainSync { app.remove(p.id) }
            assertNull("the removed provider's list is still saved", app.catalogStore.count(p, "fw-test-key-0000000000"))
        } finally { ins.runOnMainSync { app.remove(p.id) } }
    }

    /** Through the add/edit screen's own store (providerStore.setCredential), the path the UI saves a key by. */
    @Test fun replacingAKeyOnTheEditScreenDropsTheOldAccountsLists() {
        val app = state()
        val p = Provider.fireworks.copy(id = "key-cache-test")
        ins.runOnMainSync { app.providerStore.addProvider(p); app.providerStore.setCredential("fw-test-key-1111111111", p.id) }
        try {
            app.catalogStore.record(rows, p, "fw-test-key-1111111111")
            assertEquals(1, app.catalogStore.count(p, "fw-test-key-1111111111"))
            ins.runOnMainSync { app.providerStore.setCredential("fw-test-key-2222222222", p.id) }
            assertNull("the old key's list is still saved", app.catalogStore.count(p, "fw-test-key-1111111111"))
        } finally { ins.runOnMainSync { app.remove(p.id) } }
    }

    /**
     * One save that changes the endpoint AND the key: the screen saves the provider first (updateProvider, the new
     * endpoint) and the key second, so the key-change cleanup ran against the NEW endpoint and the old one's list stayed
     * (the local audit of 15df68e, 2026-10-13). AddEditProviderModel.save's order, through its store.
     */
    @Test fun movingAProviderToAnotherEndpointDropsTheOldEndpointsLists() {
        val app = state()
        val p = Provider.fireworks.copy(id = "key-cache-test")
        ins.runOnMainSync { app.providerStore.addProvider(p); app.providerStore.setCredential("fw-test-key-4444444444", p.id) }
        try {
            app.catalogStore.record(rows, p, "fw-test-key-4444444444")
            assertEquals(1, app.catalogStore.count(p, "fw-test-key-4444444444"))
            val moved = p.copy(endpoint = "https://gateway.example.com/inference/v1/chat/completions")
            ins.runOnMainSync { app.providerStore.updateProvider(moved); app.providerStore.setCredential("gw-test-key-5555555555", p.id) }
            assertNull("the old endpoint's list is still saved", app.catalogStore.count(p, "fw-test-key-4444444444"))
        } finally { ins.runOnMainSync { app.remove(p.id) } }
    }

    @Test fun clearingAKeyOnTheEditScreenDropsItsLists() {
        val app = state()
        val p = Provider.fireworks.copy(id = "key-cache-test")
        ins.runOnMainSync { app.providerStore.addProvider(p); app.providerStore.setCredential("fw-test-key-3333333333", p.id) }
        try {
            app.catalogStore.record(rows, p, "fw-test-key-3333333333")
            ins.runOnMainSync { app.providerStore.setCredential("", p.id) }
            assertNull("the cleared key's list is still saved", app.catalogStore.count(p, "fw-test-key-3333333333"))
        } finally { ins.runOnMainSync { app.remove(p.id) } }
    }
}
