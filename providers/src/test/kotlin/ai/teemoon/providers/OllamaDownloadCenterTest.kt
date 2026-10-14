// ports: teemoonTests/OllamaShortlistTests.swift @ v1.0.1 (the "Ollama pull failures" suite: the three classifier
//        tests; the two refProblem tests there are OllamaAdapterTest's). The resilient loop has no iOS unit tests
//        — the loop tests below are android-only, over the injected pull seam.
package ai.teemoon.providers

import ai.teemoon.providers.OllamaDownloadCenter.Download.Phase
import ai.teemoon.providers.OllamaDownloadCenter.PullFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlinx.coroutines.flow.FlowCollector

@OptIn(ExperimentalCoroutinesApi::class)
class OllamaDownloadCenterTest {

    // MARK: - Pull failures (iOS)

    /** A misspelled name and a pasted junk url produce the SAME Ollama error, and it explains nothing. */
    @Test fun aMisspelledNameSaysSoInsteadOfQuotingAManifest() {
        val failure = OllamaDownloadCenter.classifyPullError("pull model manifest: file does not exist")
        assertEquals(PullFailure.NoSuchModel, failure)
        assertFalse(failure.message.contains("manifest")); assertTrue(failure.message.contains("copy the link"))
        assertFalse(failure.hint?.contains("Q4_K_M") == true)   // nothing about a quant helps a ref that doesn't exist
        assertTrue(failure.hint?.contains("own page") == true)
        assertFalse(failure.message.contains("qwen3.5")); assertFalse(failure.hint?.contains("qwen3.5") == true)
    }

    /** Ollama answers a missing hugging face repo with a realm-host mismatch, which reads as teemoon getting a hostname wrong. */
    @Test fun theRealmHostMessageBecomesSomethingTrue() {
        val failure = OllamaDownloadCenter.classifyPullError("pull model manifest: realm host \"huggingface.co\" does not match original host \"hf.co\"")
        assertEquals(PullFailure.HuggingFaceRepoUnavailable, failure)
        assertFalse(failure.message.contains("realm")); assertTrue(failure.message.contains("gguf"))
        assertTrue(failure.hint?.contains("Q4_K_M") == true)   // here the quant advice IS advice
    }

    @Test fun gatedAndTerseAndUnknownKeepTheirOwnMeanings() {
        assertEquals(PullFailure.Gated, OllamaDownloadCenter.classifyPullError("unauthorized: access denied"))
        assertEquals(PullFailure.SourceRefused("400"), OllamaDownloadCenter.classifyPullError("400:"))
        assertEquals(PullFailure.SourceRefused(null), OllamaDownloadCenter.classifyPullError(""))
        // An unrecognised message is passed through rather than explained away.
        val weird = "something nobody has seen before"
        assertEquals(PullFailure.Other(weird), OllamaDownloadCenter.classifyPullError(weird))
        assertEquals(weird, OllamaDownloadCenter.friendlyPullError(weird))
    }

    // MARK: - The resilient loop (android-only, over the injected pull seam)

    private fun progress(completed: Long, total: Long = 1_000) = OllamaPullProgress(status = "pulling abc", completed = completed, total = total)
    private val success = OllamaPullProgress(status = "success")
    private val base = "http://box:11434/v1"

    /** A scripted server: attempt n runs script[n]; the last entry repeats. */
    private class Scripted(private val attempts: List<suspend FlowCollector<OllamaPullProgress>.() -> Unit>) {
        var calls = 0
        val pull: (String, String) -> Flow<OllamaPullProgress> = { _, _ ->
            val body = attempts[minOf(calls, attempts.lastIndex)]; calls++
            flow { body() }
        }
    }

    @Test fun successFinishesWithReady() = runTest {
        val server = Scripted(listOf({ emit(progress(500)); emit(success) }))
        val c = OllamaDownloadCenter(this, server.pull)
        c.start("gemma4:e4b", base); advanceUntilIdle()
        val d = c.download("gemma4:e4b")!!
        assertEquals(Phase.Done, d.phase); assertEquals("ready", d.status); assertEquals(1.0, d.fraction!!, 0.0)
        assertFalse(c.isDownloading("gemma4:e4b")); assertTrue(c.inProgress.isEmpty()); assertEquals(1, server.calls)
    }

    /** Backgrounding drops the stream; the server keeps the resumable blob, so the center re-attaches and the pull resumes. */
    @Test fun droppedStreamReattachesAndResumes() = runTest {
        val server = Scripted(listOf({ emit(progress(100)); throw IOException("The network connection was lost.") }, { emit(progress(900)); emit(success) }))
        val c = OllamaDownloadCenter(this, server.pull)
        c.start("gemma4:e4b", base); advanceUntilIdle()
        assertEquals(Phase.Done, c.download("gemma4:e4b")!!.phase); assertEquals(2, server.calls)
    }

    /** A pull that can't finalize fails with a message instead of looping forever: eight attempts with no byte progress. */
    @Test fun noProgressRetriesAreBoundedThenFail() = runTest {
        val server = Scripted(listOf({ emit(OllamaPullProgress(status = "pulling manifest")); throw IOException("connection reset") }))
        val c = OllamaDownloadCenter(this, server.pull)
        c.start("gemma4:e4b", base); advanceUntilIdle()
        val d = c.download("gemma4:e4b")!!
        assertEquals(Phase.Failed("connection reset"), d.phase); assertEquals("failed", d.status)
        assertEquals(8, server.calls); assertFalse(c.isDownloading("gemma4:e4b"))
    }

    /** Real byte progress resets the retry budget, so a slow-but-moving pull outlives eight drops. */
    @Test fun progressResetsTheRetryBudget() = runTest {
        var n = 0L
        val server = Scripted((1..20).map<Int, suspend FlowCollector<OllamaPullProgress>.() -> Unit> { { n += 1; emit(progress(n)); throw IOException("dropped") } } + listOf({ emit(success) }))
        val c = OllamaDownloadCenter(this, server.pull)
        c.start("gemma4:e4b", base); advanceUntilIdle()
        assertEquals(Phase.Done, c.download("gemma4:e4b")!!.phase); assertEquals(21, server.calls)
    }

    /** An in-band Ollama error surfaces through the classifier, not as raw text. */
    @Test fun anInBandErrorFailsWithTheFriendlyMessage() = runTest {
        val server = Scripted(listOf({ throw OllamaPullException(200, "pull model manifest: file does not exist") }))
        val c = OllamaDownloadCenter(this, server.pull)
        c.start("nope:1b", base); advanceUntilIdle()
        val phase = c.download("nope:1b")!!.phase as Phase.Failed
        assertTrue(phase.message.contains("couldn't find that model"))
    }

    @Test fun cancelRemovesTheEntryAndStopsStreaming() = runTest {
        val c = OllamaDownloadCenter(this, { _, _ -> flow { awaitCancellation() } })
        c.start("gemma4:e4b", base)
        assertTrue(c.isDownloading("gemma4:e4b"))
        c.cancel("gemma4:e4b"); advanceUntilIdle()
        assertFalse(c.isDownloading("gemma4:e4b")); assertNull(c.download("gemma4:e4b"))
    }

    @Test fun startIsIdempotentWhileRunningAndNormalizesTheRef() = runTest {
        val server = Scripted(listOf({ awaitCancellation() }))
        val c = OllamaDownloadCenter(this, server.pull)
        c.start("gemma4:e4b", base); c.start("https://ollama.com/library/gemma4:e4b", base); advanceUntilIdle()
        assertEquals(1, c.downloads.value.size); assertEquals(1, server.calls)
        c.cancel("gemma4:e4b"); advanceUntilIdle()
    }

    @Test fun dismissOnlyRemovesFinishedEntries() = runTest {
        val c = OllamaDownloadCenter.seeded(this, listOf(
            OllamaDownloadCenter.Download("a", base, "downloading…"),
            OllamaDownloadCenter.Download("b", base, "ready", phase = Phase.Done)))
        c.dismiss("a"); c.dismiss("b")
        assertEquals(listOf("a"), c.downloads.value.map { it.id })
    }

    @Test fun byteLabelAndFraction() {
        assertNull(OllamaDownloadCenter.byteLabel(null, null)); assertNull(OllamaDownloadCenter.byteLabel(10, 0))
        assertEquals("4.7 gb", OllamaDownloadCenter.byteLabel(null, 4_700_000_000))
        assertEquals("1.2 / 4.7 gb", OllamaDownloadCenter.byteLabel(1_200_000_000, 4_700_000_000))
        assertNull(OllamaPullProgress(status = "pulling manifest").fractionCompleted)
        assertEquals(1.0, OllamaPullProgress(completed = 20, total = 10).fractionCompleted!!, 0.0)
    }

    /** The NDJSON line parser: CR stripped, blanks and junk skipped, in-band errors thrown. */
    @Test fun pullLineParsing() {
        assertNotNull(OllamaAdapter.parsePullLine("{\"status\":\"pulling manifest\"}\r"))
        assertNull(OllamaAdapter.parsePullLine("")); assertNull(OllamaAdapter.parsePullLine("not json"))
        val p = OllamaAdapter.parsePullLine("{\"status\":\"pulling abc\",\"digest\":\"sha256:abc\",\"total\":100,\"completed\":50,\"extra\":1}")!!
        assertEquals(0.5, p.fractionCompleted!!, 0.0)
        val e = runCatching { OllamaAdapter.parsePullLine("{\"error\":\"pull model manifest: file does not exist\"}") }.exceptionOrNull() as OllamaPullException
        assertEquals("pull model manifest: file does not exist", e.rawMessage)
    }
}
