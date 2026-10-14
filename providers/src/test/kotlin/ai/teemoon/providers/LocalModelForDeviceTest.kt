// Kotlin-self: the device catalog has no iOS counterpart (no NPU bundle exists for Apple silicon; docs/ANDROID_NATIVE.md).
package ai.teemoon.providers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * On a Tensor chip the E2B row IS the Tensor bundle — same id, same row, the file, size, checksum and name change.
 * Everything else is untouched, and a general file downloaded before the device catalog existed still runs.
 */
class LocalModelForDeviceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val e2b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E2B" }
    private val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }

    @Test fun onTensorTheE2BRowIsTheTensorBundleUnderTheSameId() {
        val t = e2b.forDevice("Tensor G5")
        assertEquals(e2b.id, t.id)
        assertEquals("Gemma 4 E2B · Tensor", t.displayName)
        assertEquals("gemma-4-E2B-it_Google_Tensor_G5.litertlm", t.fileName)
        assertEquals(2970, t.sizeMB)
        assertEquals("af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc", t.sha256)
        assertEquals("b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", t.revision)
        assertEquals("the general file is still known, for the fallback", e2b.fileName, t.generalFileName)
        val g6 = e2b.forDevice("Tensor G6")
        assertEquals("gemma-4-E2B-it_Google_Tensor_G6.litertlm", g6.fileName)
        assertEquals("f86c7c19c736e9307267946edef58b0a45494a9127036d54d90dd6b7617c95b7", g6.sha256)   // verified on a Pixel 11, 2026-10-14
        assertEquals("b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", g6.revision)
    }

    /** A Snapdragon 8 Elite gets its own bundle under the same id, and is NAMED for its chip — it once read "· Tensor". */
    @Test fun onASnapdragon8EliteTheE2BRowIsTheQualcommBundleNamedForItsChip() {
        val q = e2b.forDevice("SM8750")
        assertEquals(e2b.id, q.id)
        assertEquals("Gemma 4 E2B · Snapdragon", q.displayName)
        assertEquals("gemma 4 e2b · snapdragon", WhereProviderPresentation.modelLabel(Provider.local(q)))
        assertEquals("gemma-4-E2B-it_qualcomm_sm8750.litertlm", q.fileName)
        assertEquals("41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e", q.sha256)
        assertEquals("b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", q.revision)
    }

    @Test fun everyOtherChipAndEveryOtherModelIsUntouched() {
        assertEquals(e2b, e2b.forDevice("SM8850")); assertEquals(e2b, e2b.forDevice("")); assertEquals(e2b, e2b.forDevice("Apple A18"))
        assertEquals("Google publishes no Tensor build of E4B", e4b, e4b.forDevice("Tensor G5"))
        assertEquals(LocalModelCatalog.all, LocalModelCatalog.forDevice("SM8850"))
        assertEquals(listOf("Gemma 4 E2B · Tensor", "Gemma 4 E4B"), LocalModelCatalog.forDevice("Tensor G5").map { it.displayName })
    }

    /** The place is renamed when the Tensor file lands; the row and the chip follow the place, an old name does not. */
    @Test fun theLabelFollowsThePlaceOnceItRunsTheTensorFile() {
        assertEquals("gemma 4 e2b", WhereProviderPresentation.modelLabel(Provider.local(e2b)))
        assertEquals("gemma 4 e2b · tensor", WhereProviderPresentation.modelLabel(Provider.local(e2b.forDevice("Tensor G5"))))
        assertEquals("a stale name falls back to the catalog", "gemma 4 e2b", WhereProviderPresentation.modelLabel(Provider.local(e2b).copy(name = "my gemma")))
    }

    /**
     * The Tensor file refused to start on this phone (a chip it was never run on): from then on the phone runs the
     * general file, or nothing — never the Tensor file on the general ladder — and the get row offers the general
     * download. A delete of the weights clears the mark.
     */
    @Test fun aTensorFileThatRefusedIsNeverRunAgainAndTheGeneralOneTakesOver() {
        val s = LocalModelStorage(tmp.root, LocalModelCatalog.forDevice("Tensor G5"))
        val t = s.catalogEntry(e2b.id)!!
        File(s.directory(e2b.id), t.fileName).apply { parentFile?.mkdirs(); writeText("tensor") }
        assertTrue("before: the Tensor file runs on the NPU", s.ref(e2b.id, "Tensor G5")!!.requiresNpu)
        s.markNpuUnusable(e2b.id, "the npu backend loaded but produced nothing")
        assertTrue(s.npuUnusable(e2b.id))
        assertNull("no general file: nothing to run, not the Tensor file on the GPU", s.ref(e2b.id, "Tensor G5"))
        // the catalog resolves to the general form, so the get row offers "gemma 4 e2b · 2.4 gb"
        val again = LocalModelCatalog.forDevice("Tensor G5") { s.npuUnusable(it) }.first()
        assertEquals(e2b, again); assertFalse("the general file is not here yet", s.isInstalled(again))
        File(s.directory(e2b.id), e2b.fileName).writeText("general")
        val ref = s.ref(e2b.id, "Tensor G5")!!
        assertFalse(ref.requiresNpu); assertTrue(ref.bundleFile.endsWith(e2b.fileName))
        // a delete of the weights takes the mark with it: a fresh download may try the Tensor file again
        s.delete(e2b); assertFalse(s.npuUnusable(e2b.id))
    }

    @Test fun everyTensorBundleCarriesAChecksumSoTheDownloadIsVerified() {
        for (m in LocalModelCatalog.all) for ((soc, b) in m.npuBundles) assertTrue("${m.id} $soc has no sha256", b.sha256.length == 64)
    }

    @Test fun theDeviceCatalogRunsTheTensorFileWhenItIsThereAndTheGeneralFileWhenItIsNot() {
        val s = LocalModelStorage(tmp.root, LocalModelCatalog.forDevice("Tensor G5"))
        val t = s.catalogEntry(e2b.id)!!
        assertNull("nothing on disk", s.ref(e2b.id, "Tensor G5"))
        // the general file from before: runnable, on the general ladder, at the general size
        val general = File(s.directory(e2b.id), e2b.fileName).apply { parentFile?.mkdirs(); writeText("general") }
        val gRef = s.ref(e2b.id, "Tensor G5")!!
        assertEquals(general.path, gRef.bundleFile); assertFalse(gRef.requiresNpu); assertEquals(e2b.sizeMB, gRef.sizeMB)
        assertFalse("the get row still offers the Tensor download", s.isInstalled(t))
        // the Tensor file lands: it wins, NPU only, with its own window
        File(s.directory(e2b.id), t.fileName).writeText("tensor")
        val nRef = s.ref(e2b.id, "Tensor G5")!!
        assertTrue(nRef.requiresNpu); assertTrue(nRef.bundleFile.endsWith(t.fileName)); assertEquals(4096, nRef.contextTokens)
        assertTrue(s.isInstalled(t)); assertNotNull(s.file(t))
    }

    /**
     * THE STATIC TABLE IS THE WRONG QUESTION ON A DEVICE THAT HAS AN NPU BUNDLE.
     *
     * `LocalModelCatalog.model(id)` answers with the GENERAL entry whatever the chip is; only
     * `LocalModelStorage.catalogEntry` resolves through `forDevice`. Ask the static one and `isInstalled` looks for
     * a file that a Tensor phone never downloaded, so a working model reports "not downloaded — tap to resume" over
     * a 2.9 GB bundle on disk. That is what the Pixel 10 Pro showed on 2026-09-21; the where sheet took
     * `LocalModelCatalog::model` and now takes `localStorage::catalogEntry`.
     *
     * This asserts the DIFFERENCE between the two lookups, which is the thing a call site can get wrong — the test
     * above already covers what each one does on its own.
     */
    @Test fun onlyTheDeviceLookupSeesAnInstalledTensorBundle() {
        val s = LocalModelStorage(tmp.root, LocalModelCatalog.forDevice("Tensor G5"))
        val deviceEntry = s.catalogEntry(e2b.id)!!
        val staticEntry = LocalModelCatalog.model(e2b.id)!!
        assertNotEquals("the two lookups must differ on this chip, or this test proves nothing",
            staticEntry.fileName, deviceEntry.fileName)

        // Only the Tensor bundle is on disk — the state this phone was actually in.
        File(s.directory(e2b.id), deviceEntry.fileName).apply { parentFile?.mkdirs(); writeText("tensor") }

        assertTrue("the device lookup sees it", s.isInstalled(deviceEntry))
        assertFalse("the static lookup does not — this is the bug, pinned", s.isInstalled(staticEntry))
    }

    /**
     * THE DELETE DIALOG'S NUMBER. `delete` removes the model's DIRECTORY, so what it frees is everything in there
     * — and on a Tensor phone that is the general bundle AND the Tensor one, plus any marker or leftover `.part`.
     * The dialog quoted `LocalModel.sizeMB`, one bundle's catalog figure, and so promised to free 2.4 GB while
     * freeing more than twice that.
     *
     * Measured rather than reasoned about, because reasoning about which bundles are down there is exactly what
     * was wrong.
     */
    @Test fun deletingFreesEverythingInTheModelsDirectory() {
        val s = LocalModelStorage(tmp.root, LocalModelCatalog.forDevice("Tensor G5"))
        val tensor = s.catalogEntry(e2b.id)!!
        assertEquals("nothing installed frees nothing", 0L, s.installedBytes(e2b.id))

        val dir = s.directory(e2b.id).apply { mkdirs() }
        File(dir, e2b.fileName).writeBytes(ByteArray(3_000_000))        // the general bundle
        assertEquals(3_000_000L, s.installedBytes(e2b.id))

        File(dir, tensor.fileName).writeBytes(ByteArray(5_000_000))      // the Tensor bundle beside it
        File(dir, ".npu-unusable").writeText("x")                        // and the marker
        val total = s.installedBytes(e2b.id)
        assertEquals("both bundles and the marker count", 8_000_001L, total)
        // THE POINT: the measured figure is not the catalog's figure for either bundle on its own. Assert the
        // relationship rather than a magic number, so this still means something when the catalog sizes move.
        assertTrue("a single bundle's catalog size cannot be the answer",
            total != tensor.sizeMB.toLong() * 1024 * 1024 && total != e2b.sizeMB.toLong() * 1024 * 1024)

        // …and the label formats from the real figure, in the same shape sizeLabel uses.
        assertEquals("1.0 GB", byteSizeLabel(1024L * 1024 * 1024))
        assertEquals("512 MB", byteSizeLabel(512L * 1024 * 1024))
        assertEquals("0 MB", byteSizeLabel(0))
    }
}
