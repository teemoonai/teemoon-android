// android-only: the WIFI_ONLY half of CellularDownloadGate on this side. iOS gets it from URLSession
// (`allowsExpensiveNetworkAccess = false` pauses and resumes the task by itself); Android has no equivalent, so the
// downloader parks the job and resumes from the partial. Until 2026-09-24 the network choice was dropped entirely —
// "wait for wi-fi" started the same unrestricted download as "download now".
package ai.teemoon.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.security.MessageDigest

class LocalModelWifiOnlyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val payload = "first-half|second-half".toByteArray()
    private val model = LocalModelCatalog.all.first().copy(sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) })

    /** On mobile data a wi-fi-only job does not touch the network; wi-fi coming back starts it. */
    @Test fun aWifiOnlyJobWaitsOutMobileDataBeforeItStarts() = runTest {
        val mobile = MutableStateFlow(true); var calls = 0
        val s = LocalModelStorage(tmp.root)
        val d = LocalModelDownloader(this, s, fetch = { _, into, _ -> calls++; into.writeBytes(payload); 200 }, onMobileData = mobile)
        d.start(model, network = DownloadNetwork.WIFI_ONLY); runCurrent()
        assertEquals("no bytes on mobile data", 0, calls)
        assertTrue("the row says it is waiting", d.jobs.value[model.id]?.parked == true)
        mobile.value = false; advanceUntilIdle()
        assertEquals(1, calls); assertTrue(s.isInstalled(model)); assertFalse(d.isDownloading(model.id))
    }

    /** Mid-transfer: the move to mobile data stops the transfer, the partial survives, and wi-fi resumes FROM it. */
    @Test fun aWifiOnlyJobParksMidTransferAndResumesFromItsPartial() = runTest {
        val mobile = MutableStateFlow(false); val sizesSeen = ArrayList<Long>()
        val s = LocalModelStorage(tmp.root)
        val d = LocalModelDownloader(this, s, onMobileData = mobile, fetch = { _, into, _ ->
            sizesSeen += if (into.isFile) into.length() else 0L
            val half = payload.size / 2
            if (!into.isFile || into.length() == 0L) { into.writeBytes(payload.copyOfRange(0, half)); delay(10_000) }   // stalls after half
            into.appendBytes(payload.copyOfRange(into.length().toInt(), payload.size)); 200
        })
        d.start(model, network = DownloadNetwork.WIFI_ONLY); runCurrent()
        mobile.value = true; runCurrent()
        assertTrue("parked on mobile data", d.jobs.value[model.id]?.parked == true)
        mobile.value = false; advanceUntilIdle()
        assertEquals("the second attempt started from the half already on disk", listOf(0L, (payload.size / 2).toLong()), sizesSeen)
        assertTrue("the resumed file verifies", s.isInstalled(model))
    }

    /** Tapping a parked row re-asks (iOS 3b1c494): "download now" lifts the running job to ANY and it proceeds on mobile data. */
    @Test fun aParkedJobAnsweredDownloadNowProceedsOnMobileData() = runTest {
        val mobile = MutableStateFlow(true); var calls = 0
        val s = LocalModelStorage(tmp.root)
        val d = LocalModelDownloader(this, s, fetch = { _, into, _ -> calls++; into.writeBytes(payload); 200 }, onMobileData = mobile)
        d.start(model, network = DownloadNetwork.WIFI_ONLY); runCurrent()
        assertTrue(d.jobs.value[model.id]?.parked == true); assertEquals(0, calls)
        d.start(model, network = DownloadNetwork.ANY); advanceUntilIdle()
        assertEquals("the same job, now allowed", 1, calls); assertTrue(s.isInstalled(model))
    }

    /** "download now" is the user spending the data: ANY never parks. */
    @Test fun downloadNowIgnoresMobileData() = runTest {
        val s = LocalModelStorage(tmp.root)
        val d = LocalModelDownloader(this, s, fetch = { _, into, _ -> into.writeBytes(payload); 200 }, onMobileData = MutableStateFlow(true))
        d.start(model, network = DownloadNetwork.ANY); advanceUntilIdle()
        assertTrue(s.isInstalled(model))
    }

    /** The copy asks between chunks: a cancel stopped only the UI while a blocking read ran the transfer to the end. */
    @Test fun theCopyStopsWhenAskedToBetweenChunks() {
        var chunks = 0
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { chunks++; return len }
        }
        try {
            LocalModelDownloader.copyWithProgress(endless, -1, tmp.newFile("part"), onProgress = {}, keepGoing = { chunks < 3 })
            fail("an endless stream must stop when asked")
        } catch (_: kotlinx.coroutines.CancellationException) {}
        assertEquals(3, chunks)
    }
}
