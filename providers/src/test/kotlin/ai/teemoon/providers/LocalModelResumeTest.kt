// ports: teemoon/Providers/LocalModelDownloadSession.swift @ v1.0.4 (the RESUME behaviour only —
//        iOS gets it from a background URLSession and opaque resume data, which has no Android
//        equivalent; here it is a ranged request against the .part file. See docs/ANDROID_NATIVE.md.)
package ai.teemoon.providers

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A 2.5 GB model download WILL be interrupted on a phone — it happened twice on
 * 2026-09-18. What these pin is that the interruption costs the remaining bytes
 * and not the whole file.
 */
class LocalModelResumeTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun storage() = LocalModelStorage(tmp.newFolder(), LocalModelCatalog.all)
    private val model = LocalModelCatalog.all.first()

    /**
     * THE BUG THIS FIXES. The partial used to be deleted in a `finally`, so any
     * interruption threw away every byte already on disk and the next attempt
     * started from zero.
     */
    @Test fun anInterruptedDownloadKeepsWhatItAlreadyHas() = runTest {
        val s = storage()
        val d = LocalModelDownloader(this, s, fetch = { _, into, _ ->
            into.writeBytes(ByteArray(1024) { 7 })          // some bytes land
            throw IOException("connection dropped")  // then the network goes
        })
        d.start(model)
        advanceUntilIdle()
        val part = File(s.directory(model.id), "${model.fileName}.part")
        assertTrue("the partial must survive an interruption", part.isFile)
        assertEquals(1024, part.length())
    }

    /**
     * The one case where the bytes are known-BAD rather than merely incomplete.
     * Resuming onto a failed checksum would re-verify the same corruption on
     * every attempt, so that partial is thrown away where every other failure
     * keeps its own.
     */
    @Test fun aChecksumFailureDiscardsThePartial() = runTest {
        val s = storage()
        val d = LocalModelDownloader(this, s, fetch = { _, into, _ ->
            into.writeBytes("tampered".toByteArray()); 200
        })
        d.start(model)
        advanceUntilIdle()
        val part = File(s.directory(model.id), "${model.fileName}.part")
        assertFalse("known-bad bytes must not be resumed onto", part.isFile)
    }

    /** A successful download leaves no partial behind. */
    @Test fun successCleansUp() = runTest {
        val payload = "the weights".toByteArray()
        val s = storage()
        val m = model.copy(sha256 = LocalModelDownloader.sha256OfFile(
            tmp.newFile().apply { writeBytes(payload) }))
        val d = LocalModelDownloader(this, s, fetch = { _, into, p -> into.writeBytes(payload); p(1.0); 200 })
        d.start(m)
        advanceUntilIdle()
        assertFalse(File(s.directory(m.id), "${m.fileName}.part").isFile)
        assertTrue(s.file(m).isFile)
    }

    // MARK: - the append/truncate decision

    /** 206 appends onto what is there; the caller's `alreadyHave` carries the progress base. */
    @Test fun aResumedBodyAppends() {
        val into = tmp.newFile().apply { writeBytes("AAAA".toByteArray()) }
        val seen = ArrayList<Double>()
        LocalModelDownloader.copyWithProgress(
            "BBBB".byteInputStream(), total = 8, into = into,
            append = true, alreadyHave = 4) { seen.add(it) }
        assertEquals("AAAABBBB", into.readText())
        assertEquals("progress counts the bytes already on disk", 1.0, seen.last(), 0.001)
    }

    /** 200 means the server ignored the range, so the file starts over. */
    @Test fun anIgnoredRangeTruncates() {
        val into = tmp.newFile().apply { writeBytes("AAAA".toByteArray()) }
        LocalModelDownloader.copyWithProgress(
            "BB".byteInputStream(), total = 2, into = into, append = false, alreadyHave = 0) {}
        assertEquals("BB", into.readText())
    }
}
