// Kotlin-self pins for the settings screens' pure parts (no Swift tests exist for SettingsView/SearchSettingsView/CreditsView).
package ai.teemoon.app

import ai.teemoon.chat.ChatDao
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.ThreadPreview
import ai.teemoon.inference.BraveWebSearchTool
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsScreensTest {
    /** The maintainer 2026-11-13 (card settings-chip-footer): the chip is in the message box since the one-panel composer, not above it. */
    @Test fun thePlacesFooterSaysWhereTheModelChipIs() =
        assertEquals("manage hosts and keys here. to switch model mid-chat, tap the model chip in the message box.", SettingsCopy.PLACES_FOOTER)

    /** The same approved phrase on the places & keys page (card settings-chip-footer, pm 2026-11-13). */
    @Test fun thePlacesPageSaysWhereTheModelChipIs() =
        assertEquals("to switch model mid-chat, tap the model chip in the message box — not here.", SettingsCopy.PLACES_PAGE_FOOTER)

    @Test fun placesSummaryCountsManagedSetups() { assertEquals("none", placesSummary(0)); assertEquals("1 setup", placesSummary(1)); assertEquals("3 setups", placesSummary(3)) }
    @Test fun everyKeyCheckOutcomeHasCopyAndAllButRejectedSayTheKeyIsSaved() {
        for (k in BraveWebSearchTool.KeyCheck.entries) {
            val msg = KeyCheckCopy.message(BraveWebSearchTool.KeyCheckResult(k, 503))
            assertTrue(msg.isNotEmpty())
            if (k != BraveWebSearchTool.KeyCheck.VALID && k != BraveWebSearchTool.KeyCheck.REJECTED) assertTrue(k.name, msg.contains("saved"))
        }
        assertEquals(KeyCheckTone.BAD, keyCheckTone(BraveWebSearchTool.KeyCheck.REJECTED)); assertEquals(KeyCheckTone.GOOD, keyCheckTone(BraveWebSearchTool.KeyCheck.VALID))
        assertEquals(KeyCheckTone.NEUTRAL, keyCheckTone(BraveWebSearchTool.KeyCheck.UNREACHABLE))
        assertTrue(KeyCheckCopy.message(BraveWebSearchTool.KeyCheckResult(BraveWebSearchTool.KeyCheck.BRAVE_UNAVAILABLE, 503)).contains("503"))
    }
    @Test fun creditsNameRealDependenciesWithHttpsLinks() {
        assertTrue(CREDITS.size >= 5)
        for (d in CREDITS) { assertTrue(d.url.startsWith("https://")); assertTrue(d.license.isNotEmpty()); assertTrue(d.role == d.role.lowercase()) }
        assertTrue(CREDITS.any { it.name.contains("dcap-qvl") }); assertTrue(CREDITS.any { it.name.contains("secp256k1") })
    }
    @Test fun deleteAllChatsClearsTheStoreAndTheIndex() = runTest {
        // The repository seam: a fake DAO records the sweep; the search service is told after.
        var swept = 0
        val dao = object : ChatDao {
            override fun observeThreads() = flowOf(emptyList<ThreadEntity>())
            override fun observeThreadsWithMessages() = observeThreads()
            override fun observeMessages(threadId: String) = flowOf(emptyList<MessageEntity>())
            override suspend fun messages(threadId: String) = emptyList<MessageEntity>()
            override suspend fun upsertThread(thread: ThreadEntity) {}
            override suspend fun upsertMessage(message: MessageEntity) {}
            override suspend fun updateContent(id: String, content: String) {}
            override suspend fun setTitle(id: String, title: String) {}
            override suspend fun deleteThread(id: String) {}
            override suspend fun deleteAllThreads() { swept++ }
            override suspend fun deleteAllMessages() { swept++ }
            override suspend fun deleteMessagesSince(threadId: String, sinceMs: Long) {}
            override suspend fun deleteMessages(ids: List<String>) {}
            override suspend fun rewriteSentBeforeConfirmed(from: String, to: String?): Int = 0
            override suspend fun setTimestamp(id: String, timestamp: Long) {}
            override suspend fun indexableMessageIds() = emptyList<String>()
            override suspend fun messagesByIds(ids: List<String>) = emptyList<MessageEntity>()
            override fun observePreviews() = flowOf(emptyList<ThreadPreview>())
            override suspend fun threadIdsWithContent(needle: String) = emptyList<String>()
            override suspend fun threadExists(id: String) = false
            override suspend fun insertAttachments(attachments: List<ai.teemoon.chat.AttachmentEntity>) {}
            override suspend fun attachmentsFor(messageIds: List<String>) = emptyList<ai.teemoon.chat.AttachmentEntity>()
            override fun observeAttachments(threadId: String) = kotlinx.coroutines.flow.flowOf(emptyList<ai.teemoon.chat.AttachmentEntity>())
            override suspend fun attachmentPaths() = emptyList<String>()
            override suspend fun setTitleIfNone(id: String, title: String) {}
            override suspend fun insertThread(thread: ThreadEntity) {}
            override suspend fun insertMessages(messages: List<MessageEntity>) {}
        }
        ChatRepository(dao).deleteAll()
        assertEquals(2, swept)
    }
}
