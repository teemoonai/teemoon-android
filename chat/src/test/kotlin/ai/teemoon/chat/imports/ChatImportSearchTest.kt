// ports: teemoonTests/ImportBehaviorTests.swift @ abe841b — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
// spec: docs/IMPORT_NEAR.md §7 test 9 (teemoon-ios, draft 7): imported messages are findable. Android's writer hands
// each one to the index as a sent message is (ChatRepository.insertChat), and the launch reconcile backs that up.
package ai.teemoon.chat.imports

import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.ListChatDao
import ai.teemoon.chat.search.ChatSearchGranularity.MESSAGES
import ai.teemoon.chat.search.ChatSearchService
import ai.teemoon.chat.search.SearchTestSupport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ChatImportSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun everyImportedMessageIsFindable() = runTest {
        val index = SearchTestSupport.index(File(tmp.root, "import.sqlite"))
        val service = ChatSearchService(this, index)
        val dao = ListChatDao()
        val summary = ChatImport.run(ByteArrayInputStream(ImportTestSupport.full), ChatImportWriter(ChatRepository(dao, service), ImportTestSupport.encode))
        service.drainPendingWork()
        assertEquals(25, summary.messagesImported)
        assertEquals(25, index.indexedMessageIDs().size)
        assertEquals(listOf("near:conv_lisbon"), index.search("pastéis", MESSAGES, 10).map { it.threadID }.distinct())
    }
}
