// ports: teemoonTests/LocalModelCatalogTests.swift @ v1.0.1 (catalogIsNotEmptyAndIdsAreUnique, catalogIsOrderedSmallestFirst,
//        fileChecksumIsComputedInChunks, theBundlePathIsNamespacedByRepoID, deletingAModelRemovesTheBundle, reclaim spares current ones;
//        the two HuggingFace-API tests and the source-inspection test are live/iOS-meta), plus Kotlin-self downloader pins
package ai.teemoon.providers

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LocalModelStorageTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun storage() = LocalModelStorage(tmp.root)
    private val model = LocalModelCatalog.all.first()

    @Test fun catalogIsNotEmptyAndIdsAreUnique() { assertTrue(LocalModelCatalog.all.isNotEmpty()); assertEquals(LocalModelCatalog.all.size, LocalModelCatalog.all.map { it.id }.toSet().size) }
    @Test fun catalogIsOrderedSmallestFirst() { val sizes = LocalModelCatalog.all.map { it.sizeMB }; assertEquals(sizes.sorted(), sizes) }
    @Test fun sizeLabelReadsInGB() { assertEquals("2.4 GB", model.sizeLabel); assertEquals("474 MB", model.copy(sizeMB = 474).sizeLabel) }
    @Test fun theBundlePathIsNamespacedByRepoID() {
        val file = storage().file(model)
        assertEquals(model.fileName, file.name)
        assertTrue("two models sharing a file name would collide", file.path.removePrefix(tmp.root.path).contains(model.id))
    }
    @Test fun deletingAModelRemovesTheBundle() {
        val s = storage(); val file = s.file(model); file.parentFile?.mkdirs(); file.writeText("not a real bundle")
        assertTrue(s.isInstalled(model)); assertNotNull(s.ref(model.id))
        s.delete(model); assertFalse("the bundle survived deletion — the disk was never freed", file.exists()); assertNull(s.ref(model.id))
    }
    @Test fun reclaimingUncatalogedBundlesSparesTheCurrentOnes() {
        val s = storage(); val current = s.file(model); current.parentFile?.mkdirs(); current.writeText("current")
        val retired = File(s.directory("mlx-community/old"), "model.bin"); retired.parentFile?.mkdirs(); retired.writeBytes(ByteArray(2048))
        s.reclaimUncatalogedBundles()
        assertFalse("retired weights were not reclaimed", retired.exists()); assertTrue("reclaiming deleted a CURRENT model", current.exists())
    }
    @Test fun fileChecksumIsComputedInChunks() {
        val file = tmp.newFile("blob.bin"); val payload = ByteArray((1 shl 20) + 12_345) { 0xAB.toByte() }; file.writeBytes(payload)
        val expected = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        assertEquals("chunked hash disagrees with hashing the whole file at once", expected, LocalModelDownloader.sha256OfFile(file))
    }
    @Test fun aGoodDownloadIsVerifiedThenInstalled_andReported() = runTest {
        val payload = "weights".toByteArray(); val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val m = model.copy(sha256 = sha); val s = storage(); var installed: LocalModel? = null; val urls = ArrayList<String>()
        val d = LocalModelDownloader(this, s, fetch = { url, into, onProgress -> urls += url; into.writeBytes(payload); onProgress(1.0); 200 })
        d.onInstalled = { installed = it }
        d.start(m); advanceUntilIdle()
        assertEquals("https://huggingface.co/${m.id}/resolve/${m.revision}/${m.fileName}", urls.single())
        assertTrue(s.isInstalled(m)); assertEquals(m, installed); assertNull(d.failure(m.id)); assertFalse(d.isDownloading(m.id))
    }
    @Test fun aChecksumMismatchIsDiscardedBeforeTheMoveAndSaidPlainly() = runTest {
        val s = storage(); val d = LocalModelDownloader(this, s, fetch = { _, into, _ -> into.writeBytes("tampered".toByteArray()); 200 })
        var installed = false; d.onInstalled = { installed = true }
        d.start(model); advanceUntilIdle()
        assertFalse(s.isInstalled(model)); assertFalse(installed)
        assertEquals("The downloaded model failed its integrity check and was discarded.", d.failure(model.id))
        // the saved reason (download.failed) stays; the bytes do not
        assertTrue("no .part file left behind", s.directory(model.id).listFiles().orEmpty().none { it.name.endsWith(".part") || it.name == model.fileName })
    }
    @Test fun anHttpFailureIsAMessageAndTheRetryClearsIt() = runTest {
        val d = LocalModelDownloader(this, storage(), fetch = { _, _, _ -> 404 })
        d.start(model); advanceUntilIdle()
        assertEquals("download failed (HTTP 404)", d.failure(model.id))
        d.clearFailure(model.id); assertNull(d.failure(model.id))
    }
    /**
     * reconnectRecordsAFailureDeliveredBeforeAdoption's relaunch half (LocalModelDownloaderTests @ v1.0.4): the reason
     * outlives the process that recorded it — a second downloader over the same storage still says why — and a retry
     * or a clear removes it from disk too.
     */
    @Test fun aFailureSurvivesANewDownloaderAndTheRetryDeletesIt() = runTest {
        val s = storage()
        LocalModelDownloader(this, s, fetch = { _, _, _ -> 404 }).apply { start(model) }; advanceUntilIdle()
        assertTrue(s.failureFile(model.id).isFile)
        val relaunched = LocalModelDownloader(this, s, fetch = { _, _, _ -> 404 })
        assertEquals("the reason did not survive a relaunch", "download failed (HTTP 404)", relaunched.failure(model.id))
        relaunched.clearFailure(model.id)
        assertFalse(s.failureFile(model.id).exists()); assertNull(LocalModelDownloader(this, s).failure(model.id))
        val third = LocalModelDownloader(this, s, fetch = { _, _, _ -> 404 })
        s.saveFailure("old", model.id); third.start(model)
        assertFalse("start() clears the saved reason", s.failureFile(model.id).exists() && s.failure(model.id) == "old")
        advanceUntilIdle()
    }
    @Test fun messagesNameTheCommonFailures() {
        assertEquals("No internet connection.", LocalModelDownloader.message(UnknownHostException("huggingface.co")))
        assertEquals("The download timed out. Try again.", LocalModelDownloader.message(SocketTimeoutException()))
        assertEquals("Not enough free space on this device.", LocalModelDownloader.message(IOException("write failed: ENOSPC")))
    }

    // MARK: per-SoC NPU bundles

    private val gemma = LocalModelCatalog.all.first()

    /** The key is the lowercased Build.SOC_MODEL, because that is what Google's own index uses. */
    @Test fun anNpuBundleIsFoundByTheChipsName() {
        assertNotNull(LocalModelCatalog.npuBundle(gemma, "Tensor G5"))
        assertNotNull(LocalModelCatalog.npuBundle(gemma, "tensor g6"))
        // the Snapdragon 8 Elite from 2026-10-14, when the APK began to carry its dispatcher and QNN runtime; the
        // 8 Elite Gen 5 has no bundle, so it stays on its GPU
        assertNotNull(LocalModelCatalog.npuBundle(gemma, "SM8750"))
        assertNull(LocalModelCatalog.npuBundle(gemma, "SM8850"))
        assertNull(LocalModelCatalog.npuBundle(gemma, ""))
    }

    /**
     * An NPU bundle carries no CPU or GPU graph, so a ref that names one must ALSO say it needs the NPU — otherwise
     * the engine walks a ladder whose every other rung fails on a tensor lookup rather than on the accelerator.
     */
    @Test fun anInstalledNpuBundleWinsAndIsMarkedAsNeedingTheNpu() {
        val dir = tmp.newFolder(); val storage = LocalModelStorage(dir)
        val bundle = LocalModelCatalog.npuBundle(gemma, "tensor g5")!!
        storage.file(gemma).apply { parentFile?.mkdirs() }.writeText("general")
        // with only the general bundle, the ref is the ordinary one whatever the chip
        assertEquals(false, storage.ref(gemma.id, "tensor g5")!!.requiresNpu)
        storage.npuFile(gemma, bundle).writeText("npu")
        val ref = storage.ref(gemma.id, "tensor g5")!!
        assertEquals(true, ref.requiresNpu)
        assertEquals(bundle.fileName, File(ref.bundleFile).name)
        assertEquals(bundle.sizeMB, ref.sizeMB)
        // a chip with no bundle of its own is unaffected by one sitting on disk for another chip
        assertEquals(false, storage.ref(gemma.id, "SM8750")!!.requiresNpu)
    }

    /** The two bundles live side by side under one model id, so installing one must not hide the other. */
    @Test fun theTwoBundlesCoexist() {
        val dir = tmp.newFolder(); val storage = LocalModelStorage(dir)
        val bundle = LocalModelCatalog.npuBundle(gemma, "tensor g5")!!
        storage.npuFile(gemma, bundle).apply { parentFile?.mkdirs() }.writeText("npu")
        assertEquals(false, storage.isInstalled(gemma))          // the general one is still absent
        assertEquals(true, storage.isInstalled(gemma, bundle))
        assertNull(storage.ref(gemma.id))                        // and without a chip name there is nothing to run
    }
}
