// ports: teemoonTests/HomeCapabilityRefreshTests.swift @ a15f0cec; the recheck @ 77c928f6, unknown kind @ e793e6a3
//
// A model on the user's own Ollama machine that reads images got no "· images" marker: the where sheet's probe saved
// model ids only, and the catalogue store keys cloud servers only. The sheet now reads the machine's capabilities after
// its probe, at most once an hour. (iOS's first case, the alternative offered, is the app's ImageModelChoiceTest's
// aHomeModelThatReadsImagesIsOfferedOnceItsRowKnows: the choice lives in the app module here.)
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCapabilityRefreshTest {
    private fun box(models: List<String>) = Provider(id = "box", name = "box", endpoint = "http://box.local:11434/v1/chat/completions", model = models[0],
        requiresAPIKey = false, equippedModels = models)
    private fun row(id: String, vararg caps: ModelCapability) = KnownModel(id, id, "", "", capabilities = caps.toSet())

    @Test fun anOllamaModelThatReadsImagesIsAdopted() = runTest {
        var adopted: Pair<List<KnownModel>, String>? = null
        HomeCapabilityRefresh(list = { listOf(row("llama3"), row("llava", ModelCapability.VISION)) })
            .refresh(listOf(box(listOf("llama3", "llava"))), kind = { LocalServerKind.OLLAMA }, adopt = { m, id -> adopted = m to id })
        assertEquals("box", adopted?.second)
        assertEquals(setOf(ModelCapability.VISION), adopted?.first?.first { it.id == "llava" }?.capabilities)
    }

    @Test fun theMachineIsAskedAtMostOnceAnHour() = runTest {
        var now = 1_800_000_000_000L
        var calls = 0
        val refresh = HomeCapabilityRefresh(list = { calls++; listOf(row("llava", ModelCapability.VISION)) }, nowMs = { now })
        val home = box(listOf("llava"))
        repeat(3) { refresh.refresh(listOf(home), { LocalServerKind.OLLAMA }) { _, _ -> } }
        assertEquals(1, calls)
        now += HomeCapabilityRefresh.MAX_AGE_MS
        refresh.refresh(listOf(home), { LocalServerKind.OLLAMA }) { _, _ -> }
        assertEquals(2, calls)
    }

    @Test fun onlyTheUsersOwnOllamaMachinesAreAsked() = runTest {
        val asked = ArrayList<String>()
        val refresh = HomeCapabilityRefresh(list = { asked += it; null })
        refresh.refresh(listOf(box(listOf("m")), Provider.fireworks), { LocalServerKind.LM_STUDIO }) { _, _ -> }
        assertTrue("LM Studio reports no capabilities over this path", asked.isEmpty())
        refresh.refresh(listOf(Provider.fireworks), { LocalServerKind.OLLAMA }) { _, _ -> }
        assertTrue("a cloud server is never asked here", asked.isEmpty())
    }

    /** The hour is per address: a setup whose endpoint was edited is a different machine and is asked at once. */
    @Test fun anEditedEndpointIsAskedWithoutWaitingOutTheOldOnesHour() = runTest {
        val asked = ArrayList<String>()
        val refresh = HomeCapabilityRefresh(list = { asked += it; listOf(row("llava", ModelCapability.VISION)) }, nowMs = { 1_800_000_000_000L })
        val home = box(listOf("llava"))
        refresh.refresh(listOf(home), { LocalServerKind.OLLAMA }) { _, _ -> }
        refresh.refresh(listOf(home.copy(endpoint = "http://studio.local:11434/v1/chat/completions")), { LocalServerKind.OLLAMA }) { _, _ -> }
        assertEquals(2, asked.size)
        assertTrue(asked[1].contains("studio.local"))
    }

    // The recheck before a refusal (iOS 77c928f6 / 6218fbf5; the maintainer 2026-11-12): a home model whose ability was
    // never read (a setup saved before a15f0cec, a model switched on the same server, a failed /api/show at save) was
    // refused a photo it could read. Before refusing, its own machine is asked about that one model.

    @Test fun aRecheckThatFindsVisionSaysSoAndKeepsIt() = runTest {
        var adopted: Pair<List<KnownModel>, String>? = null
        val sees = HomeCapabilityRefresh().recheckBeforeRefusing(box(listOf("gemma4:e4b")), LocalServerKind.OLLAMA,
            show = { model, _ -> if (model == "gemma4:e4b") ModelCapabilities.VISION else null }, adopt = { m, id -> adopted = m to id })
        assertTrue(sees)
        assertEquals("box", adopted?.second)
        assertEquals(setOf(ModelCapability.VISION), adopted?.first?.single()?.capabilities)
    }

    @Test fun anUnreachableMachineLeavesTheRefusal() = runTest {
        var adopted = false
        assertFalse(HomeCapabilityRefresh().recheckBeforeRefusing(box(listOf("m")), LocalServerKind.OLLAMA, show = { _, _ -> null }, adopt = { _, _ -> adopted = true }))
        assertFalse("nothing kept", adopted)
    }

    @Test fun aCloudServerOrLmStudioIsNeverAsked() = runTest {
        var asked = 0
        val refresh = HomeCapabilityRefresh()
        assertFalse(refresh.recheckBeforeRefusing(Provider.fireworks, LocalServerKind.OLLAMA, show = { _, _ -> asked++; ModelCapabilities.VISION }, adopt = { _, _ -> }))
        assertFalse(refresh.recheckBeforeRefusing(box(listOf("m")), LocalServerKind.LM_STUDIO, show = { _, _ -> asked++; ModelCapabilities.VISION }, adopt = { _, _ -> }))
        assertEquals(0, asked)
    }

    @Test fun aMachineReadThisHourIsNotAskedAgainButAStaleOneIs() = runTest {
        var now = 1_800_000_000_000L
        var shown = 0
        val refresh = HomeCapabilityRefresh(list = { listOf(row("m")) }, nowMs = { now })
        val home = box(listOf("m"))
        refresh.refresh(listOf(home), { LocalServerKind.OLLAMA }) { _, _ -> }
        assertFalse("read this hour: its answer stands", refresh.recheckBeforeRefusing(home, LocalServerKind.OLLAMA, show = { _, _ -> shown++; ModelCapabilities.VISION }, adopt = { _, _ -> }))
        assertEquals(0, shown)
        now += HomeCapabilityRefresh.MAX_AGE_MS
        assertTrue("stale: asked", refresh.recheckBeforeRefusing(home, LocalServerKind.OLLAMA, show = { _, _ -> shown++; ModelCapabilities.VISION }, adopt = { _, _ -> }))
        assertEquals(1, shown)
    }

    /**
     * iOS d9d9db05 (red e793e6a3): on a server whose probe never ran the kind is unknown (null), and the first recheck
     * skipped it — the live fresh-setup run still refused. Unknown is asked; only a machine known not to be Ollama is
     * skipped, and a non-Ollama server just fails the /api/show, so the refusal stands.
     */
    @Test fun aHomeServerOfUnknownKindIsAskedToo() = runTest {
        var asked = 0
        assertTrue(HomeCapabilityRefresh().recheckBeforeRefusing(box(listOf("gemma4:e4b")), kind = null,
            show = { _, _ -> asked++; ModelCapabilities.VISION }, adopt = { _, _ -> }))
        assertEquals(1, asked)
    }
}
