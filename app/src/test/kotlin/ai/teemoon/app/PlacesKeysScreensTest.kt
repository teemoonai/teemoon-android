// Kotlin-self pins for PlaceManageRow's subtitle rule and the store-open classification (no Swift tests exist for PlacesKeysViews;
// ConversationStoreHardeningTests is iOS file-protection and not portable).
package ai.teemoon.app

import ai.teemoon.chat.ChatDatabase
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalServerKind
import ai.teemoon.providers.Provider
import android.database.sqlite.SQLiteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesKeysScreensTest {
    private val machine = Provider(id = "m", name = "ringzero gemma4:e2b-it-qat", endpoint = "https://ringzero.tailnet-name.ts.net:11434/v1", model = "gemma4:e4b", requiresAPIKey = false, equippedModels = listOf("gemma4:e4b", "qwen3.5:4b"))
    private val guarded = Provider(id = "g", name = "llama.cpp", endpoint = "http://192.168.1.50:8080/v1", model = "qwen3.5-7b", requiresAPIKey = true, equippedModels = listOf("qwen3.5-7b"))
    // `model` is set explicitly, not left to the preset: `equipped` is the union of equippedModels and the ACTIVE
    // model, so a fixture that lets the preset choose the active one silently gains a row when the preset moves —
    // which it did on 2026-09-22 when near.ai retired glm-5.2 and the default became glm-5.3-flash.
    private val near = Provider.nearAI.copy(id = "n", name = "near.ai glm 5.2", model = "z-ai/glm-5.2",
        equippedModels = listOf("z-ai/glm-5.2", "z-ai/glm-5.1", "deepseek/deepseek-v3.2"))

    @Test fun homeRowsNameTheMachineAndItsKindModelsAndKeyState() {
        assertEquals("ringzero", placeManageTitle(machine))
        assertEquals("ollama · 2 models", placeManageSubtitle(machine, LocalServerKind.OLLAMA, hasKey = false))
        assertEquals("openai-compatible · 1 model · needs key", placeManageSubtitle(guarded, LocalServerKind.UNKNOWN, hasKey = false))
        assertEquals("1 model · key set", placeManageSubtitle(guarded, null, hasKey = true))
        assertEquals("no models", placeManageSubtitle(machine.copy(equippedModels = emptyList(), model = ""), null, false))   // equipped falls back to the active model, so "no models" needs both empty
    }
    @Test fun cloudRowsNameTheHostModelsAndKeyState() {
        assertEquals("near.ai", placeManageTitle(near))
        assertEquals("cloud-api.near.ai · 3 models · key set", placeManageSubtitle(near, null, hasKey = true))
        assertEquals("cloud-api.near.ai · 3 models · needs key", placeManageSubtitle(near, null, hasKey = false))
    }
    @Test fun phoneRowsUseThePlaceCaption() { assertEquals("on this device", placeManageSubtitle(Provider.local(LocalModelCatalog.all[0]), null, false)) }
    @Test fun badgesAndDeleteCopy() {
        assertEquals("none", placeCountBadge(0)); assertEquals("3", placeCountBadge(3))
        assertTrue(deleteMessage(near).contains("api key")); assertFalse(deleteMessage(machine).contains("api key"))
    }
    @Test fun onlyAMigrationProblemIsAMigrationFailure() {
        assertTrue(ChatDatabase.isMigrationFailure(IllegalStateException("A migration from 1 to 2 was required but not found.")))
        assertTrue(ChatDatabase.isMigrationFailure(RuntimeException("wrapped", IllegalStateException("Migration didn't properly handle: threads"))))
        assertFalse(ChatDatabase.isMigrationFailure(SQLiteException("file is not a database")))
        assertFalse(ChatDatabase.isMigrationFailure(IllegalStateException("unrelated")))
    }
}
