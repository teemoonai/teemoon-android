package ai.teemoon.providers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeServerProbeRefreshTest {
    private val machine = Provider(id = "mac", name = "mac", endpoint = "http://192.168.1.20:11434/v1/chat/completions", model = "qwen3", requiresAPIKey = false)

    /** Every refresh probes (WhereSheetView.task, on each presentation): warm/cold is re-read, not frozen at the first open. */
    @Test fun everyRefreshProbesAgainSoWarmthIsReread() = runTest {
        var calls = 0; var warm = setOf("qwen3")
        val probe = HomeServerProbe(probe = { _, _, _ -> calls++; HomeServerProbe.Info(LocalServerKind.OLLAMA, listOf("qwen3"), warm) })
        probe.refresh(listOf(machine)) { "" }
        assertEquals(setOf("qwen3"), probe.info(machine)?.warm)
        warm = emptySet()
        probe.refresh(listOf(machine)) { "" }
        assertEquals("the second open did not probe", 2, calls)
        assertEquals("the unloaded model still reads warm", emptySet<String>(), probe.info(machine)?.warm)
    }

    /** A preview's planted answer is never overwritten by a live probe. */
    @Test fun aPreviewNeverProbes() = runTest {
        val preview = HomeServerProbe.previewing(mapOf("mac" to HomeServerProbe.Info(LocalServerKind.OLLAMA, listOf("qwen3"), setOf("qwen3"))))
        preview.refresh(listOf(machine)) { "" }
        assertEquals(setOf("qwen3"), preview.info(machine)?.warm)
    }

    /**
     * A model deleted while a probe was on the wire stays deleted: the probe read the server before the delete, so its
     * answer still lists the model, and syncHomeEquipped would write the row the user just removed straight back.
     */
    @Test fun aModelDeletedDuringAProbeDoesNotComeBackWithItsAnswer() = runTest {
        val answer = CompletableDeferred<HomeServerProbe.Info>()
        val probe = HomeServerProbe(probe = { _, _, _ -> answer.await() })
        probe.seed(listOf(machine), { listOf("qwen3", "llava") }, { LocalServerKind.OLLAMA })
        val job = launch { probe.refresh(listOf(machine)) { "" } }
        runCurrent()
        probe.forget("llava", machine)
        answer.complete(HomeServerProbe.Info(LocalServerKind.OLLAMA, listOf("qwen3", "llava"), setOf("llava")))
        job.join()
        assertEquals(listOf("qwen3"), probe.info(machine)?.models)
        assertEquals(emptySet<String>(), probe.info(machine)?.warm)
    }

    /** Only the probe the delete overlapped drops the model: one started after it reports what the server says. */
    @Test fun aLaterProbeReportsTheModelIfTheServerHasItAgain() = runTest {
        val probe = HomeServerProbe(probe = { _, _, _ -> HomeServerProbe.Info(LocalServerKind.OLLAMA, listOf("qwen3", "llava")) })
        probe.seed(listOf(machine), { listOf("qwen3", "llava") }, { LocalServerKind.OLLAMA })
        probe.forget("llava", machine)
        probe.refresh(listOf(machine)) { "" }
        assertEquals(listOf("qwen3", "llava"), probe.info(machine)?.models)
    }
}
