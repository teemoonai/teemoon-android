// The import error dialog's wording, as the maintainer approved it on pm's review page (2026-10-25 22:13Z): a title that says
// what happened ("import chats" was a bare label, the HIG review's point), the reason, and "ok" to acknowledge.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportErrorCopyTest {
    @Test fun aFileThatIsNotAnExport() {
        assertEquals("this file can't be imported", ImportCopy.failureTitle(ChatImportUi.Failed.Reason.WRONG_FILE))
        assertEquals("it isn't a chat export teemoon can read.", ImportCopy.failureMessage(ChatImportUi.Failed.Reason.WRONG_FILE))
        assertEquals("ok", ImportCopy.ACKNOWLEDGE)
    }

    @Test fun theSummaryKeepsDone() = assertEquals("done", ImportCopy.DONE)

    // pm 2026-10-25 (pending the maintainer): every failure's title says what happened.
    @Test fun theOtherFailuresSayWhatHappened() {
        assertEquals("couldn't open this file", ImportCopy.failureTitle(ChatImportUi.Failed.Reason.CANT_OPEN))
        assertEquals(null, ImportCopy.failureMessage(ChatImportUi.Failed.Reason.CANT_OPEN))
        assertEquals("the import stopped", ImportCopy.failureTitle(ChatImportUi.Failed.Reason.STOPPED))
        assertEquals("chats already imported are kept; import the same file again to finish.", ImportCopy.failureMessage(ChatImportUi.Failed.Reason.STOPPED))
        assertEquals("it isn't a chat export teemoon can read.", ImportCopy.failureMessage(ChatImportUi.Failed.Reason.WRONG_FILE))
    }

    // the maintainer's approved footer, on its own since v5 (2026-11-01): the import page names the export path, not a sheet.
    @Test fun theFooterIsShortAndTheExportPathIsOnThePage() {
        assertEquals("from near.ai private chat. it stays on this phone.", ImportCopy.FOOTER)
        assertEquals("in near.ai private chat: settings → chats → export chats.", ImportCopy.EXPORT_PATH)
    }
}
