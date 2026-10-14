// ports: teemoon/Providers/LocalModelCatalog.swift @ v1.0.4 (LocalModelStorage, LocalModelDownloader, ProgressReportingDownload —
//        REBUILD over the app-private files dir and OkHttp; the catalog itself is LocalModelCatalog.kt)
package ai.teemoon.providers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine

/**
 * The same shape as [sizeLabel], from bytes actually on disk rather than a catalog figure — so a "frees 5.3 GB"
 * and a "2.4 GB download" can never be formatted differently while describing the same thing.
 */
fun byteSizeLabel(bytes: Long): String {
    val mb = (bytes / (1024.0 * 1024.0)).toInt()
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"
}

/**
 * Where weights live: the app-private files dir (never the cache dir, which
 * may be purged under disk pressure — losing a multi-gigabyte download
 * silently and leaving a provider pointing at nothing). Backup is already off
 * app-wide (allowBackup=false); the tree is namespaced by repo id so two
 * models sharing a file name cannot collide.
 */
class LocalModelStorage(private val baseDirectory: File, private val catalog: List<LocalModel> = LocalModelCatalog.all) {
    companion object { const val NPU_UNUSABLE = ".npu-unusable" }
    fun directory(repoID: String): File = File(File(baseDirectory, "litert"), repoID)

    /**
     * The last failure, on disk (LocalModelCatalog.failureFile, iOS faa1597). iOS needs it because a background relaunch
     * that meets a failure is suspended seconds later; Android needs it because DownloadService holds the process only
     * while bytes move — a download that fails in the background stops the service, the cached process can be killed,
     * and the row came back as "download" with no word of why.
     */
    fun failureFile(repoID: String): File = File(directory(repoID), "download.failed")
    fun failure(repoID: String): String? = runCatching { failureFile(repoID).readText() }.getOrNull()?.takeIf { it.isNotEmpty() }
    fun saveFailure(message: String, repoID: String) { runCatching { directory(repoID).mkdirs(); failureFile(repoID).writeText(message) } }
    fun clearFailure(repoID: String) { failureFile(repoID).delete() }
    /** Every catalog model's saved failure — what a new downloader starts from. */
    fun savedFailures(): Map<String, String> = catalog.mapNotNull { m -> failure(m.id)?.let { m.id to it } }.toMap()
    /** The catalog entry this storage runs for [repoID] — the device's resolution of it, when the catalog is a device catalog. */
    fun catalogEntry(repoID: String): LocalModel? = catalog.firstOrNull { it.id == repoID }
    fun file(model: LocalModel): File = File(directory(model.id), model.fileName)
    fun isInstalled(model: LocalModel): Boolean = file(model).isFile
    fun delete(model: LocalModel) { directory(model.id).deleteRecursively() }

    /**
     * What deleting this model would actually free: every byte in its directory, measured.
     *
     * [delete] removes the DIRECTORY, not the file — so on a Tensor phone that also holds the general bundle it
     * frees both, plus any `.npu-unusable` marker and any `.part` a stopped download left behind. The confirm
     * dialog quoted `LocalModel.sizeMB`, the catalog's declared size for ONE bundle, and so promised to free 2.4 GB
     * while freeing well over five. A number the user is about to act on has to be the real one — and it is
     * cheaper to measure it than to reason about which bundles are down there, which is the reasoning that was
     * wrong in the first place.
     *
     * Zero when nothing is installed, which is also the honest answer.
     */
    fun installedBytes(repoID: String): Long =
        directory(repoID).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * The NPU bundle for [repoID] failed to start on this phone (LiteRtLmEngineFactory.onNpuUnusable): a marker in the
     * model's directory, beside the file, so it survives restarts and dies with a delete. While it stands, [ref] runs
     * the general file (or nothing — never the NPU file on the general ladder, which is the disguised fallback), and
     * the device catalog resolves the model to its general form so the get row offers that download.
     */
    fun markNpuUnusable(repoID: String, reason: String) { directory(repoID).mkdirs(); File(directory(repoID), NPU_UNUSABLE).writeText(reason) }
    fun npuUnusable(repoID: String): Boolean = File(directory(repoID), NPU_UNUSABLE).isFile
    fun clearNpuUnusable(repoID: String) { File(directory(repoID), NPU_UNUSABLE).delete() }

    /** An NPU bundle sits beside the general one in the same repo directory; the file names differ, so they cannot collide. */
    fun npuFile(model: LocalModel, bundle: NpuBundle): File = File(directory(model.id), bundle.fileName)
    fun isInstalled(model: LocalModel, bundle: NpuBundle): Boolean = npuFile(model, bundle).isFile

    /**
     * Everything needed to run this model, or null if it isn't installed.
     *
     * PREFERS the NPU bundle when this chip has one AND it is downloaded: it is the only bundle that can reach the
     * accelerator, and it can reach nothing else. [socModel] is `Build.SOC_MODEL`; empty means "no NPU bundle", which
     * is what every caller that does not care should pass.
     */
    fun ref(repoID: String, socModel: String = ""): LocalRef? {
        val model = catalog.firstOrNull { it.id == repoID } ?: return null
        val generalRef = { val general = File(directory(repoID), model.generalFileName)
            if (general.isFile) LocalRef(repoID, directory(repoID).path, LocalModelCatalog.all.firstOrNull { it.id == repoID }?.sizeMB ?: model.sizeMB, general.path) else null }
        // The NPU file refused once on this phone: the general file or nothing. Never the NPU file on the general ladder.
        if (npuUnusable(repoID)) return generalRef()
        LocalModelCatalog.npuBundle(model, socModel)?.takeIf { isInstalled(model, it) }?.let { bundle ->
            return LocalRef(repoID, directory(repoID).path, bundle.sizeMB, npuFile(model, bundle).path, requiresNpu = true, contextTokens = bundle.maxTokens)
        }
        if (isInstalled(model) && model.fileName == model.generalFileName) return LocalRef(repoID, directory(repoID).path, model.sizeMB, file(model).path)
        // A device catalog names the NPU file; a phone that downloaded the general one before that keeps running it.
        return generalRef()
    }

    /**
     * @param requiresNpu this bundle runs on the NPU and NOWHERE else, so the engine must not walk a ladder looking
     *   for something friendlier — there is nothing friendlier, and every other rung fails slowly and confusingly.
     */
    data class LocalRef(val id: String, val directory: String, val sizeMB: Int, val bundleFile: String, val requiresNpu: Boolean = false,
        /** The bundle's own context ceiling, or null for the general bundle, which takes whatever the transport asks for. */
        val contextTokens: Int? = null)

    /**
     * Deletes downloaded bundles for models the catalog no longer lists. A
     * retired model's bundle stays invisible otherwise — the only screen that
     * could delete it iterates the catalog. Touches only the `litert` tree,
     * and only ids not in the catalog RIGHT NOW. Returns MB freed.
     */
    fun reclaimUncatalogedBundles(log: (String) -> Unit = {}): Int {
        val root = File(baseDirectory, "litert"); val known = catalog.map { it.id }.toSet(); var freedMB = 0
        // NOTE: an NPU bundle lives under its model's own id, so a catalogued model keeps both files. Only a model the
        // catalog has dropped entirely is reclaimed, which is the same rule as before.
        for (org in root.listFiles() ?: return 0) for (name in org.listFiles() ?: continue) {
            val id = "${org.name}/${name.name}"; if (id in known) continue
            val bytes = name.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (name.deleteRecursively()) { freedMB += (bytes / (1024 * 1024)).toInt(); log("[local] reclaimed retired model $id") } else log("[local] could not reclaim $id")
        }
        return freedMB
    }
}

/**
 * Downloads model bundles, one at a time, with progress. Serial by design:
 * two concurrent multi-gigabyte downloads on a phone compete for bandwidth
 * and disk and finish later than if they had queued.
 */
class LocalModelDownloader(
    private val scope: CoroutineScope,
    private val storage: LocalModelStorage,
    /** The transfer: writes the body to [into] reporting progress; returns the HTTP status. OkHttp in production. */
    private val fetch: suspend (url: String, into: File, onProgress: (Double) -> Unit) -> Int = ::liveFetch,
    private val log: (String) -> Unit = {},
    /**
     * Whether the phone is on mobile data now (NetworkPathObserver: the cellular transport or the metered flag). A
     * WIFI_ONLY job parks while it is true — before it starts and mid-transfer — and resumes from its partial when
     * it goes false: iOS's URLSession `allowsExpensiveNetworkAccess = false`, which Android has no equivalent of.
     */
    private val onMobileData: StateFlow<Boolean> = MutableStateFlow(false),
) {
    /** [parked]: a WIFI_ONLY job waiting out mobile data — CellularDownloadGate.waitingLabel says so on its row. */
    data class Job(val id: String, val fraction: Double, val model: LocalModel,
                   val network: DownloadNetwork = DownloadNetwork.ANY, val parked: Boolean = false)
    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    /** Active + queued downloads, keyed by repo id. */
    val jobs: StateFlow<Map<String, Job>> get() = _jobs
    // Seeded from disk: a failure recorded by a process that has since died still names itself on the row.
    private val _failures = MutableStateFlow(storage.savedFailures())
    private fun recordFailure(id: String, message: String) { _failures.value = _failures.value + (id to message); storage.saveFailure(message, id) }
    private fun forgetFailure(id: String) { _failures.value = _failures.value - id; storage.clearFailure(id) }
    /** Last failure per repo id, cleared when a retry starts. */
    val failures: StateFlow<Map<String, String>> get() = _failures
    private val tasks = HashMap<String, kotlinx.coroutines.Job>()
    /** Called when a download has landed AND verified — the app registers the model as a provider so it shows up runnable. */
    var onInstalled: ((LocalModel) -> Unit)? = null

    fun isDownloading(repoID: String) = _jobs.value.containsKey(repoID)
    fun progress(repoID: String): Double? = _jobs.value[repoID]?.fraction
    fun failure(repoID: String): String? = _failures.value[repoID]

    /**
     * @param npu the chip's NPU bundle to fetch instead of the general one. The job is still keyed by the model id,
     *   because a user downloading "Gemma 4 E2B" does not care which file it is, and two downloads of one model at
     *   once is exactly what the serial design exists to prevent.
     */
    @Synchronized fun start(model: LocalModel, npu: NpuBundle? = null, network: DownloadNetwork = DownloadNetwork.ANY) {
        if (tasks.containsKey(model.id)) {
            // Already running — the one change a second start may make is the allowance: a parked wi-fi-only job whose
            // row was tapped and answered "download now" (iOS 3b1c494, "tapping re-asks"). The parked wait wakes on
            // it and the transfer resumes from its partial.
            _jobs.value[model.id]?.takeIf { it.network != network }?.let { j -> _jobs.value = _jobs.value + (model.id to j.copy(network = network)) }
            return
        }
        forgetFailure(model.id)
        _jobs.value = _jobs.value + (model.id to Job(model.id, 0.0, model, network))
        tasks[model.id] = scope.launch {
            try {
                val progress: (Double) -> Unit = { f -> _jobs.value[model.id]?.let { j -> _jobs.value = _jobs.value + (model.id to j.copy(fraction = f)) } }
                // The job's CURRENT allowance: a tap on a parked row can lift it to ANY while this runs.
                fun allowance() = _jobs.value[model.id]?.network ?: network
                while (true) {
                    if (allowance() == DownloadNetwork.WIFI_ONLY && onMobileData.value) {
                        setParked(model.id, true); log("[local] ${model.id}: waiting for wi-fi")
                        combine(onMobileData, _jobs) { mobile, jobs -> !mobile || jobs[model.id]?.network == DownloadNetwork.ANY }.first { it }
                        setParked(model.id, false)
                    }
                    // Mid-transfer, a WIFI_ONLY job that finds itself on mobile data stops the transfer (the partial
                    // survives) and goes back to waiting; everything else runs to the end.
                    val parkedMidway = coroutineScope {
                        val transfer = async { downloadBundle(model, npu, progress) }
                        val watch = if (allowance() == DownloadNetwork.WIFI_ONLY) launch { onMobileData.first { it }; if (allowance() == DownloadNetwork.WIFI_ONLY) transfer.cancel() } else null
                        try { transfer.await(); false }
                        catch (e: kotlinx.coroutines.CancellationException) { if (transfer.isCancelled && isActive) true else throw e }
                        finally { watch?.cancel() }
                    }
                    if (!parkedMidway) break
                }
                // Trust the file, not the callback: a "finished" download that left nothing behind is a failure.
                val landed = if (npu != null) storage.isInstalled(model, npu) else storage.isInstalled(model)
                if (!landed) recordFailure(model.id, "The download finished but the model file is missing. Try again.")
                else onInstalled?.invoke(model)   // only after the checksum passed and the file is on disk
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancelled: nothing to report
            } catch (e: Exception) {
                log("[local] download failed for ${model.id}: $e")
                recordFailure(model.id, message(e))
            } finally { finish(model.id) }
        }
    }

    private suspend fun downloadBundle(model: LocalModel, npu: NpuBundle?, onProgress: (Double) -> Unit) {
        val destination = if (npu != null) storage.npuFile(model, npu) else storage.file(model)
        val fileName = npu?.fileName ?: model.fileName
        val revision = npu?.revision ?: model.revision
        // An NPU bundle we have not yet pinned a checksum for is downloaded UNVERIFIED, and says so. Integrity is
        // not optional here so much as not yet available: Google's own index publishes no digest for these files.
        val expectedSha = shaOverride[model.id] ?: npu?.sha256 ?: model.sha256
        destination.parentFile?.mkdirs()
        val url = "${hostOverride ?: "https://huggingface.co"}/${model.id}/resolve/$revision/$fileName"
        val temp = File(destination.parentFile, "$fileName.part")
        try {
            val status = fetch(url, temp, onProgress)
            if (status >= 400) throw LocalDownloadError("download failed (HTTP $status)")
            // Verify BEFORE the file is moved into place. Once it is at the destination it is indistinguishable from
            // a good download, and the next launch will happily memory-map it.
            if (expectedSha.isEmpty()) log("[local] ${model.id}: no checksum pinned for $fileName — downloaded unverified")
            else {
                val actual = sha256OfFile(temp)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    log("[local] checksum mismatch for ${model.id}: expected $expectedSha, got $actual")
                    throw LocalDownloadError("The downloaded model failed its integrity check and was discarded.", discardPartial = true)
                }
            }
            destination.delete()
            if (!temp.renameTo(destination)) { temp.copyTo(destination, overwrite = true) }
            temp.delete()
            onProgress(1.0)
        } catch (e: Throwable) {
            // THE PARTIAL SURVIVES. A `finally { temp.delete() }` here is what made
            // resume impossible: every interruption — a cancel, a dropped
            // connection, the process dying — threw away the bytes already on
            // disk, so the next attempt started a 2.5 GB pull from zero. It
            // happened twice on 2026-09-18.
            //
            // The one case the partial must NOT survive is a checksum mismatch:
            // those bytes are known-bad and resuming onto them would re-verify
            // the same corruption forever.
            if (e is LocalDownloadError && e.discardPartial) temp.delete()
            throw e
        }
    }

    private fun setParked(id: String, parked: Boolean) { _jobs.value[id]?.let { j -> _jobs.value = _jobs.value + (id to j.copy(parked = parked)) } }

    @Synchronized fun cancel(repoID: String) { tasks[repoID]?.cancel(); finish(repoID) }
    /** Drops a remembered failure: an error from a download the user no longer wants is stale. */
    fun clearFailure(repoID: String) = forgetFailure(repoID)
    @Synchronized private fun finish(repoID: String) { tasks.remove(repoID); _jobs.value = _jobs.value - repoID }

    companion object {
        /**
         * Debug-only (journeys): where model files are fetched from instead of Hugging Face — the fake server on
         * localhost. Only UiTestSeeding sets it, only on a debuggable build; null everywhere else.
         */
        @Volatile var hostOverride: String? = null

        /**
         * Debug-only (journeys), beside [hostOverride]: the checksum a model id's file must match when the fake host
         * serves it. A real pin cannot be matched by bytes a test can serve, so without this no journey could walk a
         * download that FINISHES — from tap to "use" (G1). Only the instrumentation sets it; empty everywhere else.
         */
        @Volatile var shaOverride: Map<String, String> = emptyMap()

        /** SHA-256 of a file in 1 MB chunks from the stream — never readBytes(): hashing gigabytes into memory right before a load gated on that same memory would cause the very failure it exists to prevent. */
        fun sha256OfFile(file: File, chunkSize: Int = 1 shl 20): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buf = ByteArray(chunkSize); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun message(e: Exception): String = when (e) {
            is LocalDownloadError -> e.message ?: "download failed"
            is UnknownHostException -> "No internet connection."
            is SocketTimeoutException -> "The download timed out. Try again."
            else -> if ((e.message ?: "").contains("ENOSPC") || (e.message ?: "").contains("No space left")) "Not enough free space on this device." else (e.message ?: e.toString())
        }

        private val client by lazy { TeemoonHttp.builder().readTimeout(60, TimeUnit.SECONDS).build() }   // lazy: see EndpointDirectory.liveClient

        /**
         * Streams the body to disk, RESUMING from whatever `into` already holds.
         *
         * iOS gets this from a background URLSession and opaque resume data;
         * Android has no equivalent, so the same behaviour is a ranged request
         * against the .part file (docs/ANDROID_NATIVE.md). The server decides:
         * 206 means it honoured the range and we append, 200 means it ignored it
         * and we start over, 416 means our partial is at or past the file's
         * length — stale or complete — and we start over too.
         *
         * A resumed file whose REMOTE CONTENT CHANGED underneath us produces a
         * mixed file. That is caught, not prevented: the checksum is verified
         * before the file is moved into place, which is the rule that already
         * existed for exactly this class of problem.
         */
        suspend fun liveFetch(url: String, into: File, onProgress: (Double) -> Unit): Int = withContext(Dispatchers.IO) {
            val have = if (into.isFile) into.length() else 0L
            val request = Request.Builder().url(url).get()
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416) {                 // stale or complete partial: start over
                    into.delete()
                    return@withContext liveFetch(url, into, onProgress)
                }
                if (response.code >= 400) return@withContext response.code
                val body = response.body
                val resumed = response.code == 206
                // On 206 Content-Length is what REMAINS, so the total is the whole file.
                val total = if (body.contentLength() < 0) -1L
                            else body.contentLength() + (if (resumed) have else 0L)
                // A cancelled coroutine does not stop a blocking read: the copy asks between chunks. Without it, a
                // cancel (or a wi-fi-only job parking on mobile data) left the transfer running to the end.
                val job = coroutineContext[kotlinx.coroutines.Job]
                copyWithProgress(body.byteStream(), total, into,
                    append = resumed, alreadyHave = if (resumed) have else 0L, onProgress = onProgress, keepGoing = { job?.isActive != false })
                response.code
            }
        }

        fun copyWithProgress(
            input: InputStream, total: Long, into: File,
            append: Boolean = false, alreadyHave: Long = 0L,
            keepGoing: () -> Boolean = { true },
            onProgress: (Double) -> Unit,
        ) {
            FileOutputStream(into, append).use { out ->
                val buf = ByteArray(1 shl 20); var written = alreadyHave
                while (true) {
                    if (!keepGoing()) throw kotlinx.coroutines.CancellationException("download stopped")
                    val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); written += n; if (total > 0) onProgress(written.toDouble() / total)
                }
            }
        }
    }
}

/**
 * [discardPartial] is set when the bytes on disk are known-bad rather than
 * merely incomplete — a checksum mismatch. Resuming onto those would re-verify
 * the same corruption on every attempt, so that one case throws the partial
 * away where every other failure keeps it.
 */
class LocalDownloadError(message: String, val discardPartial: Boolean = false) : Exception(message)
