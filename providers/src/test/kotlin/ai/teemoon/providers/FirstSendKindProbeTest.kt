// ports: teemoonTests/ReasoningEffortTests.swift @ 5f015a9 (anUnclassifiedServerIsAskedOnceAndAnOllamaAnswerCounts,
//        aNoIsRememberedAndACloudHostIsNeverAsked)
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstSendKindProbeTest {
    private fun provider(endpoint: String) = Provider(id = endpoint, name = "p", endpoint = endpoint, model = "m")

    /** A self-hosted server nothing classified is asked once, on the first send (the maintainer: "Include"). */
    @Test fun anUnclassifiedServerIsAskedOnceAndAnOllamaAnswerCounts() = runTest {
        // A store that keeps nothing: the launch's yes still counts.
        val probe = FirstSendKindProbe(storedKind = { null }, isOllama = { asked++; true }, recordOllama = {})
        val p = provider("http://192.168.1.21:11434/v1/chat/completions")
        probe.beforeSend(p); probe.beforeSend(p)
        assertEquals(1, asked)
        assertEquals(LocalServerKind.OLLAMA, probe.kind(p))
    }

    @Test fun aNoIsRememberedAndACloudHostIsNeverAsked() = runTest {
        val probe = FirstSendKindProbe(storedKind = { null }, isOllama = { asked++; false }, recordOllama = { error("not Ollama") })
        val lmStudio = provider("http://192.168.1.22:1234/v1/chat/completions")
        probe.beforeSend(lmStudio); probe.beforeSend(lmStudio)
        assertEquals("a no is not asked again this launch", 1, asked)
        assertNull(probe.kind(lmStudio))
        probe.beforeSend(provider("https://api.fireworks.ai/inference/v1/chat/completions"))
        assertEquals("a cloud host is never probed", 1, asked)
    }

    /**
     * Android-only (the audit, 3917.8): the probe never blocks or alters a send. A probe that throws is a "no" for the
     * launch — beforeSend returns normally and records nothing — and a stop still propagates.
     */
    @Test fun aFailingProbeNeverStopsTheSend() = runTest {
        var recorded = false
        val probe = FirstSendKindProbe(storedKind = { null }, isOllama = { asked++; throw IllegalArgumentException("bad url") }, recordOllama = { recorded = true })
        val p = provider("http://192.168.1.23:11434/v1/chat/completions")
        probe.beforeSend(p); probe.beforeSend(p)
        assertEquals("asked once, then remembered as no", 1, asked)
        assertNull(probe.kind(p)); assertEquals(false, recorded)
        val stopping = FirstSendKindProbe(storedKind = { null }, isOllama = { throw kotlinx.coroutines.CancellationException("stopped") }, recordOllama = {})
        val stopped = runCatching { stopping.beforeSend(provider("http://192.168.1.24:11434/v1/chat/completions")) }.exceptionOrNull()
        assertTrue("a stop propagates", stopped is kotlinx.coroutines.CancellationException)
    }

    private var asked = 0
}
