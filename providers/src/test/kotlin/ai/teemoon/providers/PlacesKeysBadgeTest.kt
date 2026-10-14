// ports: teemoonTests/PlacesKeysBadgeTests.swift @ v1.0.4
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesKeysBadgeTest {

    /**
     * THE BUG: it counted records. A cloud setup saved without a key is a row
     * that cannot send, and "1" told a tester she had a key when she had none.
     */
    @Test fun theBadgeCountsKeysNotRecords() {
        assertEquals("none", PlacesKeysBadge.cloud(keyed = 0, unkeyed = 0))
        assertEquals("2", PlacesKeysBadge.cloud(keyed = 2, unkeyed = 0))
        assertEquals("1 needs key", PlacesKeysBadge.cloud(keyed = 0, unkeyed = 1))
        assertEquals("3 need keys", PlacesKeysBadge.cloud(keyed = 0, unkeyed = 3))
        assertEquals("1 · 1 needs key", PlacesKeysBadge.cloud(keyed = 1, unkeyed = 1))
        assertEquals("2 · 3 need keys", PlacesKeysBadge.cloud(keyed = 2, unkeyed = 3))
    }

    // MARK: - LocalWarmUpPolicy

    /**
     * A selection is the earliest honest signal a model is about to be used, so
     * the load overlaps the user typing instead of landing on the first token.
     * Cloud and home providers have nothing to warm.
     */
    @Test fun onlyAnInstalledOnDeviceModelWarmsUp() {
        val ref = LocalModelStorage.LocalRef(
            id = "gemma", directory = "/tmp/x", sizeMB = 100, bundleFile = "gemma.litertlm")
        val installed: (String) -> LocalModelStorage.LocalRef? = { if (it == "gemma") ref else null }
        val local = Provider.local(LocalModelCatalog.all[0]).copy(localModelID = "gemma")
        assertEquals(ref, LocalWarmUpPolicy.target(local, installed))
        assertNull("a cloud provider has nothing to warm",
            LocalWarmUpPolicy.target(Provider.nearAI, installed))
        assertNull(LocalWarmUpPolicy.target(null, installed))
        assertNull("an on-device model whose bundle is not on disk is not warmed",
            LocalWarmUpPolicy.target(local.copy(localModelID = "absent"), installed))
    }
}
