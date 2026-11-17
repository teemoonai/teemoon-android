package ai.teemoon.app

// android-only: no Swift counterpart. pm 2026-10-26 (the maintainer asked for image timings): per image, wall time and memory of
// (1) attaching — a content URI through PickedImages into the isolated decoder (Wuffs), resized to 2048, re-encoded,
// thumbnailed, IPC included, the first one carrying the service's cold start; (2) importing conformance's images.json,
// each data: payload through the same isolated decoder, and in total; (3) the 768 px copy OnDeviceImage makes for the
// on-device model. Reported through sendStatus, not asserted.
//
// Memory: the app process's PSS is sampled every 50 ms during each step. The decoder runs in an isolated process the
// app cannot read, so the shell (UiAutomation) reads its VmHWM — the process's own peak since it started, which only
// rises — and its VmRSS after each image.

import ai.teemoon.app.imaging.DataUrl
import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.IsolatedImageDecoder
import ai.teemoon.app.imaging.OnDeviceImage
import ai.teemoon.app.imaging.PickedImages
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageTimingBenchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun log(s: String) {
        Log.w("teemoon", "[bench] $s")
        inst.sendStatus(2, android.os.Bundle().apply { putString("bench", s) })
    }

    private fun shell(cmd: String): String =
        inst.uiAutomation.executeShellCommand(cmd).let { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText() }

    private fun decoderStatus(): String {
        // the isolated process is named "<package>:decode:<service class>", which pidof does not match
        val pid = shell("pgrep -f ${ctx.packageName}:decode").trim().lines().firstOrNull()?.takeIf { it.isNotBlank() } ?: return "decoder not running"
        val status = shell("cat /proc/$pid/status").lines()
        fun mb(key: String) = status.firstOrNull { it.startsWith(key) }?.filter(Char::isDigit)?.toLongOrNull()?.div(1024)
        return "decoder pid $pid peak ${mb("VmHWM")}MB rss ${mb("VmRSS")}MB"
    }

    private fun <T> timed(block: () -> T): Triple<T, Double, Long> {
        var peak = 0L; var on = true
        val sampler = Thread { while (on) { peak = maxOf(peak, Debug.getPss() / 1024); Thread.sleep(50) } }.apply { start() }
        val t0 = System.nanoTime()
        try { val r = block(); return Triple(r, (System.nanoTime() - t0) / 1e6, maxOf(peak, Debug.getPss() / 1024)) } finally { on = false; sampler.join() }
    }

    /** A phone camera's photo: 4032×3024 with grain, so the JPEG is camera-sized (a flat picture compresses to nothing). */
    private fun cameraPhoto(seed: Int): ByteArray {
        val w = 4032; val h = 3024
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = Random(seed)
        Canvas(bmp).apply {
            drawColor(Color.rgb(90 + seed * 20, 140, 200 - seed * 20))
            val p = Paint().apply { isAntiAlias = true }
            repeat(400) { p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)); drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 20f + rnd.nextFloat() * 300f, p) }
        }
        val px = IntArray(w); for (y in 0 until h step 2) { bmp.getPixels(px, 0, w, 0, y, w, 1); for (x in 0 until w) px[x] = px[x] xor (rnd.nextInt(24) * 0x010101); bmp.setPixels(px, 0, w, 0, y, w, 1) }
        return java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    private fun summary(label: String, ms: List<Double>, sizes: List<Int>) {
        val sorted = ms.sorted(); val largest = sizes.indices.maxByOrNull { sizes[it] }!!
        log("SUMMARY $label: n=${ms.size} median ${"%.0f".format(sorted[sorted.size / 2])}ms worst ${"%.0f".format(sorted.last())}ms · largest input ${sizes[largest] / 1024}KB took ${"%.0f".format(ms[largest])}ms · total ${"%.0f".format(ms.sum())}ms")
    }

    @Test fun attachImportAndPrepare() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram ${totalRamMB()}MB · ${decoderStatus()}")
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val decoder = IsolatedImageDecoder(ctx)

        // (1) attach, 4 camera photos; the first decode binds and starts the isolated service
        val stored = ArrayList<ByteArray>(); val attachMs = ArrayList<Double>(); val photoSizes = ArrayList<Int>()
        for (i in 0 until 4) {
            val photo = cameraPhoto(i); photoSizes += photo.size
            val f = File(dir, "photo$i.jpg").apply { writeBytes(photo) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            val (out, ms, peak) = timed { runBlocking { PickedImages.read(ctx.contentResolver, uri, decoder) } }
            f.delete()
            val d = out as? ImageDecodeFrame.Decoded
            log("attach ${i + 1}${if (i == 0) " (service cold start)" else ""}: ${photo.size / 1024}KB 4032x3024 → ${d?.width}x${d?.height} ${d?.image?.size?.div(1024)}KB thumb ${d?.thumbnail?.size?.div(1024)}KB · ${"%.0f".format(ms)}ms · app pss peak ${peak}MB · ${decoderStatus()}")
            attachMs += ms; d?.let { stored += it.image }
        }
        summary("attach (incl. the cold start)", attachMs, photoSizes)
        summary("attach (warm)", attachMs.drop(1), photoSizes.drop(1))

        // (2) import: every data: payload in images.json, through the same isolated decoder
        val doc = Json.parseToJsonElement(javaClass.getResourceAsStream("/import/near/images/images.json")!!.reader().readText())
        val payloads = ArrayList<String>()
        fun walk(e: JsonElement) { when (e) {
            is JsonObject -> { (e["image_url"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("data:") }?.let { payloads += it }; e.values.forEach(::walk) }
            is JsonArray -> e.forEach(::walk); else -> {} } }
        walk(doc)
        val importMs = ArrayList<Double>(); val importSizes = ArrayList<Int>(); var decoded = 0
        val (_, totalMs, totalPeak) = timed {
            runBlocking {
                for (p in payloads) {
                    val (out, ms, _) = timed { runBlocking { (DataUrl.decode(p) as? DataUrl.Result.Bytes)?.bytes?.let { decoder.decode(it) } } }
                    importMs += ms; importSizes += p.length * 3 / 4
                    if (out is ImageDecodeFrame.Decoded) decoded++
                }
            }
        }
        log("import images.json: ${payloads.size} data: payloads, $decoded decoded, the rest refused as the corpus intends · total ${"%.0f".format(totalMs)}ms · app pss peak ${totalPeak}MB · ${decoderStatus()}")
        summary("import per image (decode + gate; the pending-file write left out)", importMs, importSizes)

        // (3) the on-device copy: 768 px on the long side, from what attach stored
        val prepMs = ArrayList<Double>()
        for ((i, s) in stored.withIndex()) {
            val (out, ms, peak) = timed { OnDeviceImage.prepare(s) }
            log("prepare 768 ${i + 1}: ${s.size / 1024}KB → ${out?.size?.div(1024)}KB · ${"%.0f".format(ms)}ms · app pss peak ${peak}MB")
            prepMs += ms
        }
        summary("prepare 768", prepMs, stored.map { it.size })
    }

    /** The phone's RAM, which Test Lab's catalogue does not say (the floor phones come in several sizes). */
    private fun totalRamMB(): Long = File("/proc/meminfo").readLines().first { it.startsWith("MemTotal") }.filter(Char::isDigit).toLong() / 1024
}
