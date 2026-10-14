// ports: teemoonTests/OllamaAdapterTests.swift @ v1.0.1
// ports: teemoonTests/OllamaShortlistTests.swift @ v1.0.1 (refProblem + installedMatch suites)
package ai.teemoon.providers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OllamaAdapterTest {

    /**
     * A DERIVED LINK MUST BE A FACT, not a guess.
     * Ollama names are registry names, not repo paths — `gemma4:e2b` is not
     * `google/gemma4`.
     */
    @Test fun ollamaLinksAreDerivedOnlyWhereTheNameSaysSo() {
        assertEquals(
            "https://ollama.com/library/gemma4",
            OllamaAdapter.libraryURL("gemma4:e2b"),
        )
        assertEquals("https://ollama.com/library/qwen3", OllamaAdapter.libraryURL("qwen3"))
        assertNull(OllamaAdapter.libraryURL("user/custom:latest"))
        assertNull(OllamaAdapter.libraryURL("hf.co/org/repo:Q4_K_M"))

        assertEquals(
            "https://huggingface.co/unsloth/gemma-4-GGUF",
            OllamaAdapter.huggingFaceURL("hf.co/unsloth/gemma-4-GGUF:Q4_K_M"),
        )
        assertEquals(
            "https://huggingface.co/org/repo",
            OllamaAdapter.huggingFaceURL("huggingface.co/org/repo"),
        )
        assertNull(OllamaAdapter.huggingFaceURL("gemma4:e2b"))
        assertNull(OllamaAdapter.huggingFaceURL("llama3.2"))
    }

    @Test fun fromOllamaMapsTheWholeVocabulary() {
        assertEquals(
            ModelCapabilities.COMPLETION + ModelCapabilities.TOOLS +
                ModelCapabilities.VISION + ModelCapabilities.THINKING,
            ModelCapabilities.fromOllama(listOf("completion", "tools", "vision", "thinking")),
        )
        assertEquals(ModelCapabilities.COMPLETION, ModelCapabilities.fromOllama(listOf("completion")))
        assertEquals(ModelCapabilities.TOOLS, ModelCapabilities.fromOllama(listOf("TOOLS")))
        assertEquals(ModelCapabilities.NONE, ModelCapabilities.fromOllama(emptyList()))
    }

    @Test fun capabilitiesCodableRoundTrips() {
        val caps = ModelCapabilities.TOOLS + ModelCapabilities.VISION
        val data = Json.encodeToString(ModelCapabilities.serializer(), caps)
        assertEquals(caps, Json.decodeFromString(ModelCapabilities.serializer(), data))
    }

    @Test fun modelSupportsToolsUnknownIsOptimistic() {
        fun provider(caps: ModelCapabilities?) = Provider(
            id = "1", name = "p", endpoint = "http://x/v1", model = "m",
            modelCapabilities = caps,
        )
        assertTrue(provider(null).modelSupportsTools)
        assertFalse(provider(ModelCapabilities.NONE).modelSupportsTools)
        assertFalse(provider(ModelCapabilities.VISION).modelSupportsTools)
        assertTrue(provider(ModelCapabilities.TOOLS).modelSupportsTools)
        assertTrue(provider(ModelCapabilities.TOOLS + ModelCapabilities.VISION).modelSupportsTools)
    }

    @Test fun oldProviderJSONWithoutCapabilitiesDecodesToNil() {
        val json = """
        {"id":"${UUID.randomUUID()}","name":"legacy","endpoint":"http://127.0.0.1:8080/v1",
         "model":"qwen2.5-7b","requiresAPIKey":false,"supportsModelBrowsing":false,
         "extraParams":{},"hasBuiltInGrounding":false,"omitSystemPrompt":false}
        """.trimIndent()
        val p = Json.decodeFromString(Provider.serializer(), json)
        assertNull(p.modelCapabilities)
        assertTrue(p.modelSupportsTools)
        assertEquals("qwen2.5-7b", p.model)
    }

    @Test fun providerCapabilitiesSurviveEncodeDecode() {
        val p = Provider(
            id = "1", name = "o", endpoint = "http://127.0.0.1:11434/v1",
            model = "gemma3n:e4b", modelCapabilities = ModelCapabilities.NONE,
        )
        val round = Json.decodeFromString(
            Provider.serializer(),
            Json.encodeToString(Provider.serializer(), p),
        )
        assertEquals(ModelCapabilities.NONE, round.modelCapabilities)
    }

    @Test fun normalizePullRefHandlesAllInputShapes() {
        val cases = listOf(
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF" to
                "hf.co/bartowski/Qwen2.5-3B-Instruct-GGUF",
            "huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/tree/main" to
                "hf.co/bartowski/Qwen2.5-3B-Instruct-GGUF",
            "hf.co/bartowski/Qwen2.5-3B-Instruct-GGUF:Q4_K_M" to
                "hf.co/bartowski/Qwen2.5-3B-Instruct-GGUF:Q4_K_M",
            "ollama run hf.co/mlabonne/repo:Q8_0" to "hf.co/mlabonne/repo:Q8_0",
            "  ollama pull qwen3.5  " to "qwen3.5",
            "qwen3.5" to "qwen3.5",
            "gemma4:e4b" to "gemma4:e4b",
            "https://ollama.com/library/qwen3.5" to "qwen3.5",
            "https://ollama.com/library/gemma4:26b" to "gemma4:26b",
            "ollama.com/library/gemma4" to "gemma4",
            "https://ollama.com/library/gemma4/tags" to "gemma4",
            "https://www.ollama.com/library/qwen3.5?variant=4b" to "qwen3.5",
            "https://ollama.com/some-user/their-model" to "some-user/their-model",
            "https://huggingface.co/bartowski/repo-GGUF?library=true#files" to
                "hf.co/bartowski/repo-GGUF",
        )
        for ((input, expected) in cases) {
            assertEquals("normalize($input)", expected, OllamaAdapter.normalizePullRef(input))
        }
    }

    @Test fun emptyTagsIsConnectedNotBroken() {
        val result = OllamaAdapter.interpretTags(200, """{"models":[]}""".toByteArray())
        assertTrue(result is EndpointModelCatalog.ProbeResult.Connected)
        assertTrue((result as EndpointModelCatalog.ProbeResult.Connected).models.isEmpty())
    }

    @Test fun rootUrlStripsTheV1Path() {
        assertEquals(
            "http://100.100.0.12:11434",
            OllamaAdapter.rootUrl("http://100.100.0.12:11434/v1"),
        )
    }

    // liveOllamaCapabilitiesGateGemmaNotQwen — not portable (needs Ollama on :11434)
}

class ModelCapabilityVocabularyTest {

    @Test fun gemma4sRealCapabilityListMapsCompletely() {
        val caps = ModelCapabilities.fromOllama(
            listOf("completion", "vision", "audio", "tools", "thinking"),
        )
        assertTrue(caps.contains(ModelCapabilities.COMPLETION))
        assertTrue(caps.contains(ModelCapabilities.VISION))
        assertTrue(caps.contains(ModelCapabilities.AUDIO))
        assertTrue(caps.contains(ModelCapabilities.TOOLS))
        assertTrue(caps.contains(ModelCapabilities.THINKING))
        assertFalse(caps.contains(ModelCapabilities.EMBEDDING))
    }

    @Test fun qwen35RealCapabilityList() {
        val caps = ModelCapabilities.fromOllama(listOf("completion", "vision", "tools", "thinking"))
        assertFalse(caps.contains(ModelCapabilities.AUDIO))
        assertTrue(caps.contains(ModelCapabilities.THINKING))
    }

    @Test fun anEmbeddingModelIsNotAChatModel() {
        val caps = ModelCapabilities.fromOllama(listOf("embedding"))
        assertTrue(caps.contains(ModelCapabilities.EMBEDDING))
        assertFalse(caps.contains(ModelCapabilities.COMPLETION))
    }

    @Test fun aWordOllamaAddsLaterIsIgnoredRatherThanFatal() {
        assertEquals(ModelCapabilities.TOOLS, ModelCapabilities.fromOllama(listOf("tools", "teleportation")))
    }

    @Test fun rawValuesAreStableBecauseTheyArePersisted() {
        assertEquals(1 shl 0, ModelCapabilities.TOOLS.rawValue)
        assertEquals(1 shl 1, ModelCapabilities.VISION.rawValue)
        assertEquals(1 shl 2, ModelCapabilities.UPLOADS.rawValue)
        assertEquals(1 shl 3, ModelCapabilities.AUDIO.rawValue)
        assertEquals(1 shl 4, ModelCapabilities.THINKING.rawValue)
        assertEquals(1 shl 5, ModelCapabilities.COMPLETION.rawValue)
        assertEquals(1 shl 6, ModelCapabilities.EMBEDDING.rawValue)
        assertEquals(1 shl 7, ModelCapabilities.INSERT.rawValue)
    }

    @Test fun capabilitiesStoredBeforeTheNewBitsStillDecode() {
        val legacy = Json.decodeFromString(ModelCapabilities.serializer(), "3")
        assertEquals(ModelCapabilities.TOOLS + ModelCapabilities.VISION, legacy)
        assertFalse(legacy.contains(ModelCapabilities.AUDIO))
        assertEquals(
            "3",
            Json.encodeToString(
                ModelCapabilities.serializer(),
                ModelCapabilities.TOOLS + ModelCapabilities.VISION,
            ),
        )
    }
}

class OllamaRefValidationTest {

    @Test fun modelPagesAndBareRefsAreAccepted() {
        for (good in listOf(
            "https://ollama.com/library/qwen3.5",
            "https://ollama.com/library/gemma4:26b",
            "https://ollama.com/library/gemma4/tags",
            "https://ollama.com/some-user/their-model",
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF",
            "https://huggingface.co/bartowski/repo/tree/main",
            "hf.co/bartowski/repo:Q4_K_M",
            "qwen3.5", "gemma4:26b", "gemma4:e4b",
            "registry.internal.example/team/model",
            "192.168.1.50:5000/team/model",
            "library/gemma4@sha256:0123456789abcdef",
            "  ollama pull qwen3.5  ",
        )) {
            assertNull("rejected $good", OllamaAdapter.refProblem(good))
        }
    }

    @Test fun aTagIsNotMistakenForALink() {
        assertNull(OllamaAdapter.refProblem("gemma4:26b"))
        assertNull(OllamaAdapter.refProblem("qwen3.5:4b"))
    }

    @Test fun linksFromOtherSitesAreRefused() {
        assertTrue(
            OllamaAdapter.refProblem("https://github.com/ggerganov/llama.cpp")
                ?.contains("ollama.com or hugging face") == true,
        )
        assertNotNull(OllamaAdapter.refProblem("https://example.com/model.gguf"))
    }

    @Test fun searchAndListingPagesAreRefused() {
        assertTrue(
            OllamaAdapter.refProblem("https://ollama.com/search?q=gemma")
                ?.contains("search page") == true,
        )
        assertNotNull(OllamaAdapter.refProblem("https://ollama.com/library"))
        assertTrue(
            OllamaAdapter.refProblem("https://huggingface.co/models?library=gguf&sort=trending")
                ?.contains("model list") == true,
        )
        assertNotNull(OllamaAdapter.refProblem("https://huggingface.co/datasets/foo"))
        assertNotNull(OllamaAdapter.refProblem("https://huggingface.co/bartowski"))
    }

    @Test fun onlyHTTPSchemesAreAccepted() {
        for (bad in listOf(
            "file:///etc/passwd", "javascript:alert(1)",
            "data:text/plain;base64,AAAA", "ftp://example.com/model",
        )) {
            assertTrue("allowed $bad", OllamaAdapter.refProblem(bad)?.contains("http") == true)
        }
    }

    @Test fun emptyIsNotAnError() {
        assertNull(OllamaAdapter.refProblem(""))
        assertNull(OllamaAdapter.refProblem("   \n "))
    }
}

class OllamaInstalledMatchTest {

    private val installed = listOf(
        "gemma4:26b", "qwen3.5:4b", "gemma4:latest",
        "hf.co/bartowski/Repo-GGUF:Q4_K_M",
    )

    @Test fun exactTagsMatch() {
        assertEquals("gemma4:26b", OllamaAdapter.installedMatch("gemma4:26b", installed))
        assertEquals("qwen3.5:4b", OllamaAdapter.installedMatch("qwen3.5:4b", installed))
    }

    @Test fun aBareNameMeansLatest() {
        assertEquals("gemma4:latest", OllamaAdapter.installedMatch("gemma4", installed))
        assertEquals("gemma4:latest", OllamaAdapter.installedMatch("gemma4:latest", installed))
        assertNull(OllamaAdapter.installedMatch("gemma4:31b", installed))
    }

    @Test fun linksAreMatchedThroughNormalization() {
        assertEquals(
            "gemma4:26b",
            OllamaAdapter.installedMatch("https://ollama.com/library/gemma4:26b", installed),
        )
        assertEquals(
            "gemma4:latest",
            OllamaAdapter.installedMatch("https://ollama.com/library/gemma4", installed),
        )
        assertEquals(
            "hf.co/bartowski/Repo-GGUF:Q4_K_M",
            OllamaAdapter.installedMatch(
                "https://huggingface.co/bartowski/Repo-GGUF:Q4_K_M", installed,
            ),
        )
    }

    @Test fun caseIsIgnoredBecauseServersDisagreeAboutIt() {
        assertEquals("gemma4:26b", OllamaAdapter.installedMatch("GEMMA4:26B", installed))
    }

    @Test fun aPortIsNotATag() {
        val withPort = listOf("registry:5000/team/model:latest")
        assertEquals(
            "registry:5000/team/model:latest",
            OllamaAdapter.installedMatch("registry:5000/team/model", withPort),
        )
    }

    @Test fun nothingInstalledMatchesNothing() {
        assertNull(OllamaAdapter.installedMatch("gemma4:26b", emptyList()))
        assertNull(OllamaAdapter.installedMatch("", installed))
        assertNull(OllamaAdapter.installedMatch("something-else", installed))
    }
}
