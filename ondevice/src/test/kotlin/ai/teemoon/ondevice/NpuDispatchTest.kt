package ai.teemoon.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * LiteRT takes the FIRST `libLiteRtDispatch*.so` its directory listing turns up, so each phone must be handed a
 * directory holding exactly one vendor's — the Tensor one in `nativeLibraryDir`, the Qualcomm one through links.
 */
class NpuDispatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun libs(name: String = "lib"): File = tmp.newFolder(name).apply {
        for (name in listOf("libLiteRt.so", "libLiteRtDispatch_GoogleTensor.so", NpuDispatch.QUALCOMM_SHIPPED) + NpuDispatch.QNN_V79)
            File(this, name).writeText(name)
    }

    /** What LiteRT's listing would match in [dir], recursively (litert/core/dynamic_loading.cc). */
    private fun dispatchers(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile && it.name.startsWith("libLiteRtDispatch") && it.extension == "so" }.map { it.name }.toList()

    @Test fun theShippedLibraryDirectoryOffersOnlyTheTensorDispatcher() {
        assertEquals("the Qualcomm dispatcher must not match the listing where it is packed",
            listOf("libLiteRtDispatch_GoogleTensor.so"), dispatchers(libs()))
    }

    @Test fun aTensorPhoneIsHandedTheLibraryDirectoryUnchanged() {
        val lib = libs()
        assertEquals(lib.path, NpuDispatch.directory(lib.path, "Tensor G5", tmp.newFolder("scratch")))
    }

    @Test fun aSnapdragon8EliteIsHandedOnlyTheQualcommDispatcherAndItsRuntime() {
        val lib = libs()
        val dir = File(NpuDispatch.directory(lib.path, "SM8750", tmp.newFolder("scratch")))
        assertEquals(listOf("libLiteRtDispatch_Qualcomm.so"), dispatchers(dir))
        for (name in NpuDispatch.QNN_V79) assertTrue("$name is not linked", File(dir, name).isFile)
        // links, not copies: the code stays where the installer extracted it, and follows it across updates
        val link = File(dir, NpuDispatch.QUALCOMM_RUNTIME_NAME).toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(File(lib, NpuDispatch.QUALCOMM_SHIPPED).toPath(), Files.readSymbolicLink(link))
    }

    /** An update moves nativeLibraryDir: the links follow it rather than pointing at the old install. */
    @Test fun theLinksAreRemadeForANewLibraryDirectory() {
        val scratch = tmp.newFolder("scratch")
        NpuDispatch.directory(libs().path, "sm8750", scratch)
        val moved = libs("lib2")
        val dir = File(NpuDispatch.directory(moved.path, "sm8750", scratch))
        assertEquals(File(moved, NpuDispatch.QUALCOMM_SHIPPED).toPath(), Files.readSymbolicLink(File(dir, NpuDispatch.QUALCOMM_RUNTIME_NAME).toPath()))
    }

    /** A chip without a Qualcomm runtime in the APK (the S26's SM8850) is never handed the links. */
    @Test fun anUnsupportedSnapdragonKeepsTheLibraryDirectory() {
        val lib = libs()
        assertEquals(lib.path, NpuDispatch.directory(lib.path, "SM8850", tmp.newFolder("scratch")))
    }

    /**
     * A missing runtime file is said in the log, and the engine finds NO dispatcher rather than a wrong one: handing back
     * nativeLibraryDir would offer the Tensor dispatcher to a Snapdragon (the audit of ef2cdc5).
     */
    @Test fun aMissingRuntimeFileFallsBackToNoDispatcherAtAll() {
        val lib = libs().also { File(it, "libQnnHtpV79Skel.so").delete() }
        val scratch = tmp.newFolder("scratch")
        // a dispatcher left in the fallback directory by anything earlier must not survive into it
        File(scratch, "npu-dispatch/none").apply { mkdirs() }.resolve("libLiteRtDispatch_Stale.so").writeText("stale")
        var said = ""
        val dir = File(NpuDispatch.directory(lib.path, "sm8750", scratch) { said = it })
        assertTrue(said, said.contains("libQnnHtpV79Skel.so"))
        assertTrue("the fallback must not be nativeLibraryDir", dir.path != lib.path)
        assertEquals("the fallback offers a dispatcher", emptyList<String>(), dispatchers(dir))
        assertTrue("the partial links outlived the failure", !File(scratch, "npu-dispatch/qualcomm").exists())
    }
}
