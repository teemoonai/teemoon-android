// android-only: the import redesign's (f), an export opened from Files or shared to teemoon (pm 2026-10-30).
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncomingExportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun onlyAJsonContentURIIsAnExport() {
        for (action in listOf(IncomingExport.ACTION_VIEW, IncomingExport.ACTION_SEND)) {
            assertTrue(IncomingExport.accepts(action, "application/json", "content"))
            assertTrue(IncomingExport.accepts(action, "Application/JSON; charset=utf-8", "content"))
        }
        assertFalse("octet-stream is the picker's fallback, not an intent's", IncomingExport.accepts(IncomingExport.ACTION_VIEW, "application/octet-stream", "content"))
        assertFalse("no file: URIs", IncomingExport.accepts(IncomingExport.ACTION_VIEW, "application/json", "file"))
        assertFalse("no web links", IncomingExport.accepts(IncomingExport.ACTION_VIEW, "application/json", "https"))
        assertFalse(IncomingExport.accepts(IncomingExport.ACTION_VIEW, null, "content"))
        assertFalse(IncomingExport.accepts("android.intent.action.MAIN", "application/json", "content"))
    }

    @Test fun theCopyIsTheFileAndAnAcceptedArrivalReplacesAnEarlierOne() {
        val cache = tmp.newFolder("cache")
        val first = IncomingExport.copy({ "[1]".byteInputStream() }, cache)
        val second = IncomingExport.copy({ "[{\"id\":\"x\"}]".byteInputStream() }, cache)
        assertEquals("[{\"id\":\"x\"}]", second.readText())
        assertTrue(IncomingExport.receive(second, busy = false, cacheDir = cache))
        assertFalse("an earlier arrival's copy goes", first.exists())
        assertEquals(listOf(second.name), java.io.File(cache, IncomingExport.DIR).list()!!.toList())
        IncomingExport.discard(cache)
        assertEquals("a declined file is not kept", emptyList<String>(), java.io.File(cache, IncomingExport.DIR).list()!!.toList())
    }

    /**
     * A second "open with" or share while the first file is being checked or imported is dropped, as iOS's onOpenURL
     * drops it while ChatImportModel.isBusy — and it no longer deletes the file the running import still reads: the
     * confirm reads it a second time to write.
     */
    @Test fun aSecondArrivalDuringACheckLeavesTheFileBeingChecked() {
        val cache = tmp.newFolder("cache")
        val first = IncomingExport.copy({ "[1]".byteInputStream() }, cache)
        assertTrue(IncomingExport.receive(first, busy = false, cacheDir = cache))
        val second = IncomingExport.copy({ "[2]".byteInputStream() }, cache)
        assertTrue("the file being checked was deleted by the next arrival", first.exists())
        assertEquals("[1]", first.readText())
        assertFalse("an arrival while busy is taken", IncomingExport.receive(second, busy = true, cacheDir = cache))
        assertTrue(first.exists())
        assertFalse("the dropped arrival's copy is kept", second.exists())
    }

    /** Two arrivals in the same millisecond are two files. */
    @Test fun eachArrivalHasItsOwnCopy() {
        val cache = tmp.newFolder("cache")
        val a = IncomingExport.copy({ "[1]".byteInputStream() }, cache)
        val b = IncomingExport.copy({ "[2]".byteInputStream() }, cache)
        assertFalse(a == b)
        assertEquals("[1]", a.readText())
    }
}
