// ports: teemoonTests/ChatImportTextTests.swift + ImportPreviewTests.swift @ cdea210c (the summary's and the confirm's
// lines, the progress line, the page's state walk), plus
// Android's own: every failure's reason, and an imported answer's sources in the app's own encoding.
package ai.teemoon.app

import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.imports.ImportStore
import ai.teemoon.chat.imports.ImportSummary
import ai.teemoon.inference.GroundingSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class ChatImportRunnerTest {
    private class Store : ImportStore {
        val threads = LinkedHashMap<String, ThreadEntity>(); val messages = ArrayList<MessageEntity>()
        var failAfter = Int.MAX_VALUE
        override suspend fun hasThread(id: String) = id in threads
        override suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>) {
            if (threads.size >= failAfter) throw IOException("disk full")
            threads[thread.id] = thread; this.messages += messages
        }
    }

    private fun fixture(): InputStream = javaClass.getResourceAsStream("/import/near/near-export-v1.json")!!

    @Test fun theFixtureFinishesWithItsCountsAndProgress() = runTest {
        val seen = ArrayList<Int>()
        val ui = ChatImportRunner.run({ fixture() }, Store(), progress = { seen += it })
        assertEquals(ChatImportUi.Finished(ImportSummary(6, 25, 0, 4)), ui)
        // the progress is CHATS saved, once each (the Safari-style page's "4 of 6 chats", pm 2026-11-01): skips move nothing
        assertEquals((1..6).toList(), seen)
    }

    /** The real encoder: what the import stores is what the sources sheet decodes, snippet "" and all. */
    @Test fun anImportedAnswersSourcesDecodeAsGroundingSources() = runTest {
        val store = Store()
        ChatImportRunner.run({ fixture() }, store)
        val answer = store.messages.single { it.id == "near:conv_lisbon:msg_a1" }
        assertEquals(listOf(GroundingSource("https://www.visitlisboa.com/en/itineraries", "www.visitlisboa.com", "Lisbon itineraries", ""),
            GroundingSource("https://en.wikipedia.org/wiki/Bel%C3%A9m_Tower", "en.wikipedia.org", "Belém Tower", "")), GroundingSource.decodeList(answer.sourcesJson))
        assertEquals(null, store.messages.single { it.id == "near:conv_lisbon:msg_a3" }.sourcesJson)
    }

    @Test fun eachFailureHasItsReason() = runTest {
        assertEquals(ChatImportUi.Failed(ChatImportUi.Failed.Reason.WRONG_FILE), ChatImportRunner.run({ ByteArrayInputStream("{}".toByteArray()) }, Store()))
        assertEquals(ChatImportUi.Failed(ChatImportUi.Failed.Reason.CANT_OPEN), ChatImportRunner.run({ throw FileNotFoundException("gone") }, Store()))
        assertEquals(ChatImportUi.Failed(ChatImportUi.Failed.Reason.CANT_OPEN), ChatImportRunner.run({ throw SecurityException("no permission") }, Store()))
        // Stopped part way: the whole chats before the stop stay, and the same file finishes the job.
        val store = Store().apply { failAfter = 2 }
        assertEquals(ChatImportUi.Failed(ChatImportUi.Failed.Reason.STOPPED), ChatImportRunner.run({ fixture() }, store))
        assertEquals(2, store.threads.size)
        store.failAfter = Int.MAX_VALUE
        assertEquals(ChatImportUi.Finished(ImportSummary(4, 13, 2, 4)), ChatImportRunner.run({ fixture() }, store))
    }

    /** The done page: the body says what came in; small lines only for what is missing (v2 placeholders). */
    @Test fun theDonePageSaysWhatCameInAndOnlyWhatIsMissing() {
        assertEquals("teemoon imported 6 chats, 25 messages and 1 image.", ImportCopy.doneBody(ImportSummary(6, 25, 1, 4, imagesImported = 1)))
        assertEquals("teemoon imported 1 chat and 1 message.", ImportCopy.doneBody(ImportSummary(1, 1)))
        assertEquals(listOf("4 items couldn't be read and were skipped."), ImportCopy.doneNotes(ImportSummary(6, 25, 1, 4)))
        assertEquals(listOf("1 image couldn't be imported.", "1 item couldn't be read and was skipped.", ImportCopy.MAY_BE_INCOMPLETE),
            ImportCopy.doneNotes(ImportSummary(1, 1, 0, 1, mayBeIncomplete = true, imagesImported = 0, imagesSkipped = 1)))
        assertEquals("already here is not missing", emptyList<String>(), ImportCopy.doneNotes(ImportSummary(2, 3, 5, 0)))
    }

    @Test fun theCopyIsLowercaseAsTheHouseWritesIt() {
        val s = ImportSummary(2, 3, 2, 2, true, 1, 1)
        val all = listOfNotNull(ImportCopy.ROW, ImportCopy.FOOTER, ImportCopy.progress(0, 2), ImportCopy.PAGE_TITLE, ImportCopy.CANCEL, ImportCopy.BODY, ImportCopy.EXPORT_PATH,
            ImportCopy.CHOOSE_FILE, ImportCopy.CHOOSE_ANOTHER, ImportCopy.IMPORT, ImportCopy.NOTHING_NEW_TITLE, ImportCopy.NOTHING_NEW_BODY, ImportCopy.EMPTY_TITLE, ImportCopy.EMPTY_BODY, ImportCopy.IMPORTING_TITLE, ImportCopy.IMPORTING_BODY,
            ImportCopy.TITLE, ImportCopy.CANT_OPEN_TITLE, ImportCopy.STOPPED_TITLE, ImportCopy.DONE,
            ImportCopy.MAY_BE_INCOMPLETE, ImportCopy.WRONG_FILE_TITLE, ImportCopy.WRONG_FILE, ImportCopy.ACKNOWLEDGE, ImportCopy.STOPPED,
            ImportCopy.cardLine(s), ImportCopy.cardNote(s), ImportCopy.doneBody(s)) + ImportCopy.doneNotes(s)
        for (line in all) assertEquals(line, line.lowercase().replace(" kb", " KB").replace(" mb", " MB"))
        assertTrue(ImportCopy.FOOTER.contains("near.ai"))
    }

    /** The file card says what the dry run found, before anything is written; "nothing new" says why (v2 placeholders). */
    @Test fun theFileCardSaysWhatWillComeInAndWhatWillBeSkipped() {
        val s = ImportSummary(chatsImported = 6, messagesImported = 25, chatsAlreadyHere = 1, itemsSkipped = 4, mayBeIncomplete = false, imagesImported = 1)
        assertEquals("6 chats, 25 messages and 1 image", ImportCopy.cardLine(s))
        assertEquals("1 chat already here stays as it is. 4 items can't be read and will be skipped.", ImportCopy.cardNote(s))
        assertEquals("2 chats already here stay as they are.", ImportCopy.cardNote(ImportSummary(1, 1, 2)))
        assertEquals(null, ImportCopy.cardNote(ImportSummary(1, 1)))
        // nothing new (v7): the card names what the file holds; the page's title and body say nothing is new
        assertEquals("3 chats", ImportCopy.cardLine(ImportSummary(chatsAlreadyHere = 3)))
        assertEquals("1 chat", ImportCopy.cardLine(ImportSummary(chatsAlreadyHere = 1)))
        assertTrue(ImportCopy.nothingNew(ImportSummary(chatsAlreadyHere = 3)))
        assertTrue("an empty file is its own result (v8), not 'nothing new'", !ImportCopy.nothingNew(ImportSummary()) && ImportCopy.empty(ImportSummary()))
        assertTrue(!ImportCopy.empty(ImportSummary(chatsAlreadyHere = 3)) && !ImportCopy.empty(ImportSummary(1, 1)))
        assertTrue(!ImportCopy.nothingNew(ImportSummary(1, 1, 2)))
        assertEquals("nothing new: the card line says it all", null, ImportCopy.cardNote(ImportSummary(chatsAlreadyHere = 3)))
        assertEquals("nothing new: no skipped note either, nothing is imported (iOS 478b3bc3)", null, ImportCopy.cardNote(ImportSummary(chatsAlreadyHere = 3, itemsSkipped = 1)))
        assertEquals("an empty file's card has no count line: the body says it", null, ImportCopy.cardLine(ImportSummary()))
        assertEquals("3 of 7 chats", ImportCopy.progress(3, 7))
        assertEquals("0 of 1 chat", ImportCopy.progress(0, 1))
    }

    /** "oct 15 · 24 KB": the year only when it isn't this one; a part the provider didn't give is left out. */
    @Test fun theFileCardsDateAndSize() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val oct15 = 1_792_022_400_000L        // 2026-10-15 00:00 UTC
        val now = 1_793_577_600_000L          // 2026-11-02
        assertEquals("oct 15 · 24 KB", ImportCopy.fileMeta(ImportFile("x.json", 24_300, oct15), now, utc))
        assertEquals("oct 15, 2025", ImportCopy.fileMeta(ImportFile(modified = oct15 - 365L * 86_400_000), now, utc))
        assertEquals("1.4 MB", ImportCopy.fileMeta(ImportFile(bytes = 1_420_000), now, utc))
        assertEquals("812 bytes", ImportCopy.size(812)); assertEquals("1 KB", ImportCopy.size(999 + 1)); assertEquals("1 byte", ImportCopy.size(1))
        assertEquals(null, ImportCopy.fileMeta(ImportFile("x.json"), now, utc))
    }

    @Test fun progressIsAFractionOfTheChatsToImport() {
        assertEquals(0f, ImportCopy.fraction(0, 0)); assertEquals(0.25f, ImportCopy.fraction(3, 12)); assertEquals("never past the end", 1f, ImportCopy.fraction(20, 12))
    }

    /** The page's steps: reading → confirm (nothing written) → importing → finished with what confirm promised. */
    @Test fun thePageGoesFromConfirmToFinishedWithTheCountsItPromised() = runTest {
        val store = Store()
        val runner = ChatImportRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val about = ImportFile("private-chat-export.json", 24_300, 1_792_022_400_000L)
        runner.check({ fixture() }, store, about = about)
        val confirm = runner.state.value as ChatImportUi.Confirm
        assertEquals("the card names the file the provider described", about, confirm.file)
        assertTrue("confirm wrote chats", store.threads.isEmpty() && store.messages.isEmpty())
        runner.confirm()
        assertEquals(ChatImportUi.Finished(confirm.preview), runner.state.value)
        runner.dismiss()
        assertEquals(ChatImportUi.Idle, runner.state.value)
        // the same file again: the confirm says everything is already here, and offers no import
        runner.check({ fixture() }, store)
        assertTrue("nothing new", ImportCopy.nothingNew((runner.state.value as ChatImportUi.Confirm).preview))
        // and "import" does nothing then: the button is greyed, and confirm() refuses as well
        runner.confirm()
        assertTrue(runner.state.value is ChatImportUi.Confirm)
    }

    /**
     * "done" closes the whole flow, back to what opened it (iOS NearImportView: done → dismissResult, then dismiss;
     * the maintainer on the Pixel 2026-11-07, "shouldnt this pop the stack instead?"): after an import that wrote chats,
     * and on "nothing new" (chatsImported == 0, iOS isNothingNew). It used to reset the page and stay on it.
     */
    @Test fun doneClosesTheFlowBackToWhatOpenedIt() = runTest {
        val store = Store()
        val runner = ChatImportRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        runner.opened(ChatImportRunner.Opener.SETTINGS)
        runner.check({ fixture() }, store); runner.confirm()
        assertTrue(runner.state.value is ChatImportUi.Finished)
        assertEquals("done after an import", ChatImportRunner.Opener.SETTINGS, runner.done())
        assertEquals(ChatImportUi.Idle, runner.state.value)
        // the same file again, arriving by share: nothing new — its "done" closes the flow too, back to the chat
        runner.opened(ChatImportRunner.Opener.ARRIVAL)
        runner.check({ fixture() }, store)
        assertTrue("nothing new", ImportCopy.nothingNew((runner.state.value as ChatImportUi.Confirm).preview))
        assertEquals("done on nothing new", ChatImportRunner.Opener.ARRIVAL, runner.done())
        assertEquals(ChatImportUi.Idle, runner.state.value)
    }

    /**
     * The finished page has one prominent "done" at the bottom, and nothing in the bar (the maintainer 2026-11-07, "match
     * safari"): "show chats" and the bar's "done" are gone. "done" closes the flow back to its opener. Nothing new and an
     * empty file keep their bottom "done"; choosing and confirming keep "cancel" in the bar.
     */
    @Test fun theFinishedPageHasOneDoneAtTheBottom() {
        val wrote = ImportSummary(2, 5)
        assertEquals(ImportActions.Bottom.DONE, ImportActions.bottom(ChatImportUi.Finished(wrote)))
        assertEquals(ImportActions.Bar.NONE, ImportActions.bar(ChatImportUi.Finished(wrote)))
        assertEquals("skipped only", ImportActions.Bottom.DONE, ImportActions.bottom(ChatImportUi.Finished(ImportSummary(chatsAlreadyHere = 2))))
        val nothingNew = ChatImportUi.Confirm(ImportSummary(chatsAlreadyHere = 3))
        assertEquals(ImportActions.Bottom.DONE, ImportActions.bottom(nothingNew)); assertEquals(ImportActions.Bar.NONE, ImportActions.bar(nothingNew))
        val ready = ChatImportUi.Confirm(wrote)
        assertEquals(ImportActions.Bottom.IMPORT, ImportActions.bottom(ready)); assertEquals(ImportActions.Bar.CANCEL, ImportActions.bar(ready))
        assertEquals(ImportActions.Bottom.IMPORT_GREYED, ImportActions.bottom(ChatImportUi.Idle)); assertEquals(ImportActions.Bar.CANCEL, ImportActions.bar(ChatImportUi.Idle))
        assertEquals(ImportActions.Bottom.PROGRESS, ImportActions.bottom(ChatImportUi.Importing(0, 2))); assertEquals(ImportActions.Bar.NONE, ImportActions.bar(ChatImportUi.Importing(0, 2)))
    }

    /**
     * "cancel" and system back leave the import back to what opened it, as iOS's sheet does: Settings › chats, or the chat
     * a shared or opened file arrived over (the maintainer's Pixel, 5824bd84: cancel from an arrival left a blank screen —
     * it went to settings › chats, which the arrival never opened). At every step: start, file chosen, nothing new, an
     * error. Mid-import it still leaves (the import runs on), to the same place.
     */
    @Test fun cancelLeavesToWhatOpenedItAtEveryStep() = runTest {
        val dir = kotlin.io.path.createTempDirectory("import-cancel").toFile()
        val store = Store()
        val runner = ChatImportRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        for (from in ChatImportRunner.Opener.entries) {
            runner.opened(from)
            assertEquals("$from at the start", from, runner.cancel(dir)); assertEquals(ChatImportUi.Idle, runner.state.value)
            runner.opened(from); runner.check({ fixture() }, Store())
            assertTrue(runner.state.value is ChatImportUi.Confirm)
            assertEquals("$from with a file chosen", from, runner.cancel(dir)); assertEquals(ChatImportUi.Idle, runner.state.value)
            runner.opened(from); runner.check({ "not json".byteInputStream() }, store)
            assertTrue(runner.state.value is ChatImportUi.Failed)
            assertEquals("$from after an error", from, runner.cancel(dir)); assertEquals(ChatImportUi.Idle, runner.state.value)
        }
        runner.opened(ChatImportRunner.Opener.SETTINGS); runner.check({ fixture() }, store); runner.confirm(); runner.done()
        runner.opened(ChatImportRunner.Opener.ARRIVAL); runner.check({ fixture() }, store)
        assertTrue("nothing new", ImportCopy.nothingNew((runner.state.value as ChatImportUi.Confirm).preview))
        assertEquals("back on nothing new", ChatImportRunner.Opener.ARRIVAL, runner.cancel(dir))
        dir.deleteRecursively()
    }

    /** The delete offer (pm 2026-11-01, iOS 478b3bc3): only after an import that wrote chats, only for a file we may delete. */
    @Test fun theDeleteOfferNeedsChatsWrittenAndADeletableFile() = runTest {
        assertTrue(ChatImportRunner.offersDelete(ImportSummary(2, 5), deletable = true))
        assertTrue("nothing new written", !ChatImportRunner.offersDelete(ImportSummary(chatsAlreadyHere = 3), deletable = true))
        assertTrue("not ours to delete", !ChatImportRunner.offersDelete(ImportSummary(2, 5), deletable = false))
        val flag = android.provider.DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        assertTrue(ImportFileInfo.deletable(flag, writeGranted = true))
        assertTrue("read-only grant", !ImportFileInfo.deletable(flag, writeGranted = false))
        assertTrue("the provider doesn't delete", !ImportFileInfo.deletable(0, writeGranted = true))
        assertTrue("no flags column", !ImportFileInfo.deletable(null, writeGranted = true))

        val runner = ChatImportRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val store = Store()
        var deleted = 0
        // no delete given (a share or open-with arrival): no offer, even when chats were written and the page closes
        runner.check({ fixture() }, store); runner.confirm()
        assertTrue(runner.state.value is ChatImportUi.Finished)
        runner.close()
        assertEquals(null, runner.exportDelete.value)
        // given one, but nothing new written: no offer
        runner.check({ fixture() }, store, delete = { deleted++; true }); runner.confirm()
        runner.close()
        assertEquals(null, runner.exportDelete.value)
        // a fresh store: chats written. Nothing is asked over "import finished" (v9, D4); closing the page asks, naming
        // the file. "not now" clears it and deletes nothing.
        val about = ImportFile("private-chat-export.json")
        runner.check({ fixture() }, Store(), about = about, delete = { deleted++; true }); runner.confirm()
        assertTrue(runner.state.value is ChatImportUi.Finished)
        assertEquals("never over import finished", null, runner.exportDelete.value)
        runner.close()
        assertEquals(ChatImportUi.Idle, runner.state.value)
        assertEquals(ExportDelete.Offer("private-chat-export.json"), runner.exportDelete.value)
        runner.keepExport()
        assertEquals(null, runner.exportDelete.value); assertEquals(0, deleted)
        runner.deleteExport()
        assertEquals("no offer, no delete", 0, deleted)
        // "delete" deletes once and the offer goes; leaving by back asks as "done" does
        runner.check({ fixture() }, Store(), about = about, delete = { deleted++; true }); runner.confirm()
        runner.leave(kotlin.io.path.createTempDirectory("leave").toFile())
        assertTrue(runner.exportDelete.value is ExportDelete.Offer)
        runner.deleteExport()
        assertEquals(1, deleted); assertEquals(null, runner.exportDelete.value)
        // a delete that didn't happen, or threw, is reported, never swallowed
        for (failing in listOf({ false }, { throw SecurityException("revoked") })) {
            runner.check({ fixture() }, Store(), about = about, delete = failing); runner.confirm(); runner.close()
            runner.deleteExport()
            assertEquals(ExportDelete.Failed, runner.exportDelete.value)
            runner.dismissDeleteFailure()
            assertEquals(null, runner.exportDelete.value)
        }
        // a flow that ends without closing (a new file arriving) asks nothing
        runner.check({ fixture() }, Store(), about = about, delete = { true }); runner.confirm()
        runner.dismiss()
        assertEquals(null, runner.exportDelete.value)
        // v9's words (D5, D6)
        assertEquals("delete \u201Cprivate-chat-export.json\u201D", ImportCopy.deleteButton("private-chat-export.json"))
        assertEquals("private-chat-export.json holds your chats unencrypted. teemoon has its own copy now. this can't be undone.",
            ImportCopy.deleteMessage("private-chat-export.json"))
    }

    /**
     * Leaving the page (back button or gesture, pm 2026-11-01): mid-import, the import carries on to its end and the
     * arrival's copy stays; at the confirm, the flow ends — nothing written, the copy deleted.
     */
    @Test fun leavingMidImportFinishesItAndLeavingTheConfirmEndsTheFlow() = runTest {
        val cache = kotlin.io.path.createTempDirectory("leave").toFile()
        IncomingExport.copy({ fixture() }, cache)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : ImportStore by Store() {
            val inner = Store()
            override suspend fun hasThread(id: String) = inner.hasThread(id)
            override suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>) { gate.await(); inner.insertChat(thread, messages) }
        }
        val runner = ChatImportRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        runner.check({ fixture() }, store)
        val confirm = runner.state.value as ChatImportUi.Confirm
        runner.confirm()
        assertTrue(runner.state.value is ChatImportUi.Importing)
        runner.leave(cache)
        assertTrue("leaving stopped the import", runner.state.value is ChatImportUi.Importing)
        assertEquals("the copy went mid-import", 1, java.io.File(cache, IncomingExport.DIR).list()!!.size)
        gate.complete(Unit)
        assertEquals("the import did not finish after the page was left", ChatImportUi.Finished(confirm.preview), runner.state.value)
        assertEquals(6, store.inner.threads.size)

        runner.dismiss(); IncomingExport.copy({ fixture() }, cache)
        runner.check({ fixture() }, Store())
        runner.leave(cache)
        assertEquals(ChatImportUi.Idle, runner.state.value)
        assertEquals("a confirm left behind kept the arrival's copy", 0, java.io.File(cache, IncomingExport.DIR).list()!!.size)
        cache.deleteRecursively()
    }
}
