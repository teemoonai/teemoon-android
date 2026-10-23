package ai.teemoon.app

// android-only: no Swift counterpart. The farm probes fetch their own bundles (a farm phone has none), from the same
// URL the app's downloader builds (LocalModelStorage) and against the same pinned checksum.

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object BundleFetch {
    /** Downloads `id/resolve/revision/fileName` to [file] unless it is there, verifying [sha256]. Returns [file]. */
    fun fetch(id: String, revision: String, fileName: String, sha256: String, file: File, log: (String) -> Unit): File {
        if (file.isFile) return file
        file.parentFile?.mkdirs()
        val url = "https://huggingface.co/$id/resolve/$revision/$fileName"
        val t0 = System.currentTimeMillis(); val part = File(file.path + ".part")
        if (staged(fileName, part, log)) return verified(fileName, sha256, part, file, t0, 0, log)
        // Resumable: a 3 GB fetch over a farm's network drops ("unexpected end of stream", Pixel 10 Pro, 2026-10-14).
        var attempt = 0; var stalls = 0
        while (true) {
            attempt++
            val have = if (part.isFile) part.length() else 0L
            val done = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                    val append = have > 0 && responseCode == 206
                    inputStream.use { input -> FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    } }
                }
            }
            if (done.isSuccess) break
            log("download attempt $attempt stopped at ${part.length()} bytes: ${done.exceptionOrNull()?.message}")
            // Give up on a STALL, not on a count: Test Lab's link to Hugging Face dropped every 20-30 MB on 2026-10-19,
            // and six resumed attempts covered 120 MB of a 3.1 GB bundle while every one of them made progress.
            if (part.length() > have) stalls = 0 else stalls++
            check(stalls < 6 && attempt < 400) { "the download kept failing" }
        }
        return verified(fileName, sha256, part, file, t0, attempt, log)
    }

    /**
     * A bundle pre-staged in /data/local/tmp (Test Lab's --other-files), so a throttled link to Hugging Face cannot time a
     * run out (Pixel 10 Pro, 2026-10-20 and -21: 52 MB of 2.6 GB in 43 min). The app cannot read that directory; the shell
     * can, so the file comes through UiAutomation's pipe. The checksum below still decides.
     */
    private fun staged(fileName: String, part: File, log: (String) -> Unit): Boolean {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val path = "/data/local/tmp/$fileName"
        fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd))
        if (shell("ls $path").use { it.readBytes().decodeToString() }.trim() != path) return false
        shell("cat $path").use { input -> FileOutputStream(part, false).use { input.copyTo(it, 1 shl 20) } }
        log("staged $fileName from $path: ${part.length()} bytes")
        return true
    }

    private fun verified(fileName: String, sha256: String, part: File, file: File, t0: Long, attempt: Int, log: (String) -> Unit): File {
        val md = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        log("downloaded $fileName: ${part.length()} bytes in ${System.currentTimeMillis() - t0} ms ($attempt attempt(s)), sha256 $sha")
        check(sha256.isEmpty() || sha == sha256) { "the bundle's checksum: expected $sha256, got $sha" }
        check(part.renameTo(file)) { "could not move the bundle into place" }
        return file
    }
}
