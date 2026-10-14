// ports: teemoon/Providers/OllamaDownloadCenter.swift @ v1.0.1
//
// App-level owner of in-flight Ollama model pulls, so a download survives
// leaving the download sheet AND survives the app being backgrounded. The pull
// itself runs on the SERVER (Ollama), so teemoon only streams progress — and the
// OS may suspend that stream when backgrounded, which surfaces as a dropped
// connection. Because Ollama's layers are resumable, the fix is to treat a
// dropped stream as transient and RE-ATTACH (re-issue the pull, which resumes),
// rather than failing. A global banner observes this so the user can keep using
// teemoon and get an in-app "ready" notice when it finishes.
//
// iOS is @MainActor; here every mutation runs on [scope], which the app must
// give a single-threaded (Main) dispatcher. The pull is an injected seam so the
// loop is testable without a server.
package ai.teemoon.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class OllamaDownloadCenter(
    private val scope: CoroutineScope,
    private val pull: (ref: String, baseUrl: String) -> Flow<OllamaPullProgress> = { r, b -> OllamaAdapter.pullModel(r, b) },
    private val reconnectDelayMs: Long = 2_000,
) {
    data class Download(
        /** normalized model ref (also the /api/pull target) */
        val id: String,
        val baseUrl: String,
        /** human phase text ("downloading…", "reconnecting…") */
        val status: String,
        /** 0…1, or null while indeterminate */
        val fraction: Double? = null,
        /** "1.2 / 4.7 gb" */
        val bytes: String? = null,
        val phase: Phase = Phase.Active,
    ) {
        sealed interface Phase {
            data object Active : Phase
            data object Reconnecting : Phase
            data object Done : Phase
            data class Failed(val message: String) : Phase
        }
        val isFinished: Boolean get() = phase == Phase.Done || phase is Phase.Failed
    }

    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val downloads: StateFlow<List<Download>> get() = _downloads
    private val tasks = HashMap<String, Job>()

    /** Active or reconnecting pulls (drive the progress banner). */
    val inProgress: List<Download>
        get() = _downloads.value.filter { it.phase == Download.Phase.Active || it.phase == Download.Phase.Reconnecting }

    fun download(ref: String): Download? = _downloads.value.firstOrNull { it.id == ref }
    fun isDownloading(ref: String): Boolean = tasks[OllamaAdapter.normalizePullRef(ref)] != null

    /** Start (or no-op if already running) a resilient server-side pull. */
    fun start(rawRef: String, baseUrl: String) {
        val ref = OllamaAdapter.normalizePullRef(rawRef)
        if (ref.isEmpty() || tasks[ref] != null) return
        upsert(Download(id = ref, baseUrl = baseUrl, status = "starting…", phase = Download.Phase.Active))
        tasks[ref] = scope.launch { run(ref, baseUrl) }
    }

    /** Cancel a pull (teemoon stops streaming; the server may keep the partial, which a later pull resumes). Removes it from the list. */
    fun cancel(rawRef: String) {
        val ref = OllamaAdapter.normalizePullRef(rawRef)
        tasks.remove(ref)?.cancel()
        _downloads.update { list -> list.filterNot { it.id == ref } }
    }

    /** Dismiss a finished (done/failed) banner entry. */
    fun dismiss(ref: String) { _downloads.update { list -> list.filterNot { it.id == ref && it.isFinished } } }

    // MARK: - Resilient pull loop

    private suspend fun run(ref: String, baseUrl: String) {
        var noProgressRetries = 0
        var lastCompleted = -1L        // max bytes seen — persists across re-attaches
        var lastError: String? = null
        while (currentCoroutineContext().isActive) {
            var succeeded = false
            var advanced = false
            try {
                pull(ref, baseUrl)
                    .takeWhile { p -> if (p.isSuccess) { succeeded = true; false } else true }
                    .collect { p ->
                        val c = p.completed
                        if (c != null && c > lastCompleted) {   // REAL byte progress
                            lastCompleted = c; advanced = true; noProgressRetries = 0
                        }
                        updateProgress(ref, p)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message   // e.g. backgrounding → connection lost
            }
            if (succeeded) { finish(ref); return }
            // Stream ended or errored without "success". The server keeps the
            // resumable blob, so re-attach — but only tolerate a bounded number of
            // attempts that make NO byte progress (a status line like "pulling
            // manifest" is NOT progress), so a pull that can't finalize fails with a
            // message instead of looping forever.
            if (!advanced) {
                noProgressRetries += 1
                if (noProgressRetries >= 8) {
                    markFailed(ref, friendlyPullError(lastError))
                    return
                }
            }
            markReconnecting(ref)
            delay(reconnectDelayMs)
        }
    }

    // MARK: - Mutations

    private fun upsert(d: Download) = _downloads.update { list ->
        if (list.any { it.id == d.id }) list.map { if (it.id == d.id) d else it } else list + d
    }
    private fun mutate(ref: String, body: (Download) -> Download) = _downloads.update { list -> list.map { if (it.id == ref) body(it) else it } }
    private fun updateProgress(ref: String, p: OllamaPullProgress) = mutate(ref) {
        it.copy(phase = Download.Phase.Active, status = p.status ?: "downloading…", fraction = p.fractionCompleted, bytes = byteLabel(p.completed, p.total))
    }
    private fun markReconnecting(ref: String) = mutate(ref) { it.copy(phase = Download.Phase.Reconnecting, status = "reconnecting…") }
    private fun markFailed(ref: String, msg: String) { tasks.remove(ref); mutate(ref) { it.copy(phase = Download.Phase.Failed(msg), status = "failed") } }
    private fun finish(ref: String) { tasks.remove(ref); mutate(ref) { it.copy(phase = Download.Phase.Done, status = "ready", fraction = 1.0) } }

    /**
     * What went wrong with a pull, in terms a user can act on.
     *
     * Ollama's own pull errors are not user-facing text. Measured against a live
     * server (2026-07-30):
     *   a misspelled name  → "pull model manifest: file does not exist"
     *   a pasted junk url  → "pull model manifest: file does not exist"
     *   a missing hf repo  → "pull model manifest: realm host \"huggingface.co\" does not match original host \"hf.co\""
     * The first says nothing about what to do and the second is actively
     * misleading — it reads as teemoon getting a hostname wrong.
     */
    sealed class PullFailure {
        /** No model by that name, anywhere. A typo, or a link that wasn't a model. */
        data object NoSuchModel : PullFailure()
        /** The HF repo doesn't exist, is gated, or isn't a GGUF repo. */
        data object HuggingFaceRepoUnavailable : PullFailure()
        /** The repo is there but the pull isn't allowed without accepting terms. */
        data object Gated : PullFailure()
        /** Reachable, refused, no detail. */
        data class SourceRefused(val code: String?) : PullFailure()
        /** Anything else, passed through: an unrecognised message is still evidence. */
        data class Other(val raw: String) : PullFailure()

        /** The line under "failed". */
        val message: String
            get() = when (this) {
                NoSuchModel -> "teemoon couldn't find that model. open ollama.com or hugging face, find the model, and copy the link from its page."
                HuggingFaceRepoUnavailable -> "hugging face doesn't have that repo, or it isn't a gguf repo. open the repo page and copy its link — ollama can only pull gguf."
                Gated -> "that repo is gated. accept its licence on hugging face first, then try again."
                is SourceRefused -> "the server couldn't fetch this model from its source${code?.let { " ($it)" } ?: ""}. the repo may be gated, split into multiple files, or not ollama-compatible."
                is Other -> raw
            }

        /**
         * The footer. Quant advice is only advice when the model EXISTS — telling
         * someone who mistyped a name to "append :Q4_K_M" sends them to fix the
         * wrong half of the string.
         */
        val hint: String?
            get() = when (this) {
                NoSuchModel -> "the link has to come from the model's own page — a search or a list page won't work."
                HuggingFaceRepoUnavailable, is SourceRefused -> "a specific quant can help — e.g. append `:Q4_K_M` to the ref — or pick a different model above."
                Gated, is Other -> null
            }
    }

    companion object {
        private val terseCode = Regex("^\\d{3}:?$")

        /**
         * Classifies a raw pull error. Matching is on the phrases Ollama actually
         * emits, lowercased, so a wording change degrades to [PullFailure.Other]
         * (the raw message) rather than to a confident wrong explanation.
         */
        fun classifyPullError(raw: String?): PullFailure {
            val r = raw.orEmpty().trim()
            val lower = r.lowercase()
            if (r.isEmpty() || terseCode.matches(r)) return PullFailure.SourceRefused(if (r.isEmpty()) null else r.replace(":", ""))
            // Ordered: "realm host" is also a manifest error, and it has more to say.
            if (lower.contains("realm host") || lower.contains("does not match original host")) return PullFailure.HuggingFaceRepoUnavailable
            if (lower.contains("file does not exist") || lower.contains("model not found") || lower.contains("manifest unknown") || lower.contains("404")) return PullFailure.NoSuchModel
            if (lower.contains("unauthorized") || lower.contains("403") || lower.contains("access denied") || lower.contains("gated")) return PullFailure.Gated
            return PullFailure.Other(r)
        }

        /** The user-facing message for a raw pull error. */
        fun friendlyPullError(raw: String?): String = classifyPullError(raw).message

        /** Preview/test seed — populate download state without starting real pulls. */
        fun seeded(scope: CoroutineScope, ds: List<Download>): OllamaDownloadCenter =
            OllamaDownloadCenter(scope).also { c -> c._downloads.value = ds }

        /** "1.2 / 4.7 gb" when a total is known, else null. */
        fun byteLabel(completed: Long?, total: Long?): String? {
            if (total == null || total <= 0) return null
            fun gb(b: Long) = String.format(Locale.US, "%.1f", b.toDouble() / 1_000_000_000)
            return if (completed == null) "${gb(total)} gb" else "${gb(completed)} / ${gb(total)} gb"
        }
    }
}
