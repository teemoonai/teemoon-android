// android-only: no Swift counterpart. iOS has one GPU stack and no vendor NPU to choose.
package ai.teemoon.ondevice

import java.io.File
import java.nio.file.Files

/**
 * The directory `Backend.NPU` is handed, which decides WHICH vendor's dispatch library LiteRT loads.
 *
 * LiteRT lists that directory — subdirectories too, in no guaranteed order — for every `libLiteRtDispatch*.so`, warns
 * "Multiple dispatch libraries found", and takes the FIRST (litert_dispatch.cc `GetSharedLibraryPath`, LiteRT v2.2.0 and v2.3.0; 2.3.0 also logs each candidate).
 * With the Tensor and the Qualcomm dispatcher side by side in `nativeLibraryDir`, a phone of either vendor could be
 * handed the other's — which is why the Qualcomm lab build (2026-10-07) had to park the Tensor one.
 *
 * So the Qualcomm dispatcher ships under a name the listing does not match ([QUALCOMM_SHIPPED]), and the Tensor
 * phone's directory stays `nativeLibraryDir` exactly as before. On a chip a Qualcomm bundle exists for, the engine is
 * handed a directory of its own holding SYMLINKS: the dispatcher under the name LiteRT looks for, and the QNN runtime
 * beside it. Links rather than copies: the code stays where the installer verified and extracted it, nothing is
 * duplicated (28 MB), and `nativeLibraryDir` moves on every update, so the links are made again on every launch. They
 * are not a security boundary — an app CAN load a library from its own data directory (measured, API 35, the
 * ef2cdc5 audit's runtime pass); what keeps a stranger's library out is that only this app can write here.
 */
object NpuDispatch {
    /** The Qualcomm dispatcher as it is packed in the APK: deliberately NOT `libLiteRtDispatch*`. */
    const val QUALCOMM_SHIPPED = "libteemoon_dispatch_qualcomm.so"

    /** The name LiteRT's listing matches, given to the link. */
    const val QUALCOMM_RUNTIME_NAME = "libLiteRtDispatch_Qualcomm.so"

    /** The QNN runtime for the Hexagon v79 (Snapdragon 8 Elite), linked beside the dispatcher. */
    val QNN_V79 = listOf("libQnnHtp.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so", "libQnnSystem.so")

    /** Chips the APK carries a Qualcomm runtime for, by `Build.SOC_MODEL`, lowercased. Matches the catalog's bundles. */
    val QUALCOMM_CHIPS = setOf("sm8750")

    /**
     * @param scratch an app-private directory for the links (`noBackupFilesDir`).
     * @return the directory to hand `Backend.NPU`. `nativeLibraryDir` itself everywhere but a supported Qualcomm chip.
     *   When the links cannot be made, an EMPTY directory — never `nativeLibraryDir`, whose Tensor dispatcher matches
     *   the listing on every phone and would be loaded on a Snapdragon (the audit of ef2cdc5). The NPU then fails with
     *   "No dispatch library found", the bundle is marked unusable, and the general file runs: loud in the log.
     */
    fun directory(nativeLibraryDir: String, socModel: String, scratch: File, log: (String) -> Unit = {}): String {
        if (socModel.trim().lowercase() !in QUALCOMM_CHIPS) return nativeLibraryDir
        val libs = File(nativeLibraryDir)
        val dir = File(scratch, "npu-dispatch/qualcomm")
        return runCatching {
            dir.deleteRecursively(); dir.mkdirs()
            val links = listOf(QUALCOMM_SHIPPED to QUALCOMM_RUNTIME_NAME) + QNN_V79.map { it to it }
            for ((shipped, linkName) in links) {
                val target = File(libs, shipped)
                check(target.isFile) { "$shipped is not in $nativeLibraryDir" }
                Files.createSymbolicLink(File(dir, linkName).toPath(), target.toPath())
            }
            dir.path
        }.getOrElse {
            log("[litert] qualcomm dispatch links not made, the NPU rung will find no dispatcher: ${it.message}")
            dir.deleteRecursively()   // the links made before the failure: nothing should outlive it
            File(scratch, "npu-dispatch/none").apply { deleteRecursively(); mkdirs() }.path
        }
    }
}
