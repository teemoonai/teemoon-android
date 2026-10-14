// ports: teemoonTests/ModelArtifactTests.swift @ v1.0.4 (+ hfCachePathNamesTheRepoNotTheSnapshot d1ac2be, realGLM53FlashComposeNamesTheModel 3936c43)
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing the pinned model artifact out of the model-layer compose YAML, and the FP8→AWQ drift detection that is the payoff. */
class ModelArtifactTest {

    /** The real near.ai shape: launch flags as a YAML list, served alias's quant tag contradicting the pinned path's. */
    private val listFormYAML = """
    services:
      model-sg-glm51-awq-tp4-r1:
        image: glm51-sgl-awq-tp4-patched:local
        command:
          - python3
          - -m
          - sglang.launch_server
          - --model-path
          - QuantTrio/GLM-5.1-AWQ
          - --revision
          - 8f60817aa28023f2607850d1a1e51d21aa34817a
          - --served-model-name
          - zai-org/GLM-5.1-FP8
          - --tp
          - "4"
    """.trimIndent()

    @Test fun parsesListForm() {
        val a = ModelArtifact.parse(listFormYAML)!!
        assertEquals("QuantTrio/GLM-5.1-AWQ", a.modelPath)
        assertEquals("8f60817aa28023f2607850d1a1e51d21aa34817a", a.revision)
        assertEquals("zai-org/GLM-5.1-FP8", a.servedName)
        assertEquals("AWQ", a.quant)
    }

    @Test fun derivesCleanBaseName() {
        // vendor prefix and quant suffix stripped
        assertEquals("GLM-5.1", ModelArtifact.parse(listFormYAML)!!.baseModelName)
    }

    @Test fun detectsQuantDrift() {
        val a = ModelArtifact.parse(listFormYAML)!!
        assertEquals("FP8", a.servedQuant)
        assertTrue(a.quantDrift)
        assertTrue(a.driftNote!!.contains("QuantTrio/GLM-5.1-AWQ"))
    }

    @Test fun inlineCommandForm() {
        val yaml = """
        services:
          model:
            command: python3 -m sglang.launch_server --model-path org/Model-AWQ --served-model-name org/Model-AWQ
        """.trimIndent()
        val a = ModelArtifact.parse(yaml)!!
        assertEquals("org/Model-AWQ", a.modelPath)
        assertEquals("AWQ", a.quant)
    }

    @Test fun equalsFlagForm() {
        val a = ModelArtifact.parse("command: [--model-path=vendor/Foo-GPTQ, --served-model-name=vendor/Foo-GPTQ]")!!
        assertEquals("vendor/Foo-GPTQ", a.modelPath)
        assertEquals("GPTQ", a.quant)
    }

    /** When the served alias matches the real quantization, there is no drift. */
    @Test fun noDriftWhenAligned() {
        val a = ModelArtifact.parse("command:\n  - --model-path\n  - vendor/Model-AWQ\n  - --served-model-name\n  - vendor/Model-AWQ")!!
        assertEquals(false, a.quantDrift)
        assertNull(a.driftNote)
    }

    @Test fun noModelPathReturnsNil() {
        assertNull(ModelArtifact.parse("services:\n  x:\n    image: foo"))
    }

    // A near.ai COMBINED node: one compose runs three models, each its own vLLM
    // server with its own --model-path/--served-model-name. `parse` alone took
    // the first (DeepSeek) and mis-branded Qwen/Gemma requests.
    private val combinedYAML = """
    services:
      dsv4:
        command:
          - --model-path
          - deepseek-ai/DeepSeek-V4-Flash
          - --served-model-name
          - deepseek-ai/DeepSeek-V4-Flash
      qwen36:
        command:
          - --model-path
          - Qwen/Qwen3.6-27B
          - --served-model-name
          - Qwen/Qwen3.6-27B-FP8
      gemma4:
        command: ["--model-path", "google/gemma-4-31B", "--served-model-name", "google/gemma-4-31B-it"]
    """.trimIndent()

    @Test fun parseAllFindsEveryServer() {
        val all = ModelArtifact.parseAll(combinedYAML)
        assertEquals(3, all.size)
        assertEquals(listOf("deepseek-ai/DeepSeek-V4-Flash", "Qwen/Qwen3.6-27B-FP8", "google/gemma-4-31B-it"), all.map { it.servedName })
    }

    @Test fun selectsRequestedServerNotFirst() {
        // Qwen request must return the Qwen server, not the first (DeepSeek).
        val q = ModelArtifact.parse(combinedYAML, servingModel = "Qwen/Qwen3.6-27B-FP8")!!
        assertEquals("Qwen/Qwen3.6-27B-FP8", q.servedName)
        assertEquals("Qwen/Qwen3.6-27B", q.modelPath)
        assertEquals("google/gemma-4-31B", ModelArtifact.parse(combinedYAML, servingModel = "google/gemma-4-31B-it")!!.modelPath)
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", ModelArtifact.parse(combinedYAML, servingModel = "deepseek-ai/DeepSeek-V4-Flash")!!.modelPath)
    }

    @Test fun selectionToleratesAliasByVendorAndFallsBack() {
        // Alias spelling still selects the right server (same vendor).
        assertEquals("Qwen/Qwen3.6-27B", ModelArtifact.parse(combinedYAML, servingModel = "qwen/qwen3.6-27b")!!.modelPath)
        // A model not in the compose falls back to the first server (never worse
        // than the old parse — the reused-node guard drops it downstream).
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", ModelArtifact.parse(combinedYAML, servingModel = "meta/llama-9")!!.modelPath)
        // Single-model GLM-5.1: requantized path, served as the near.ai id.
        val glm = ModelArtifact.parse("command: [--model-path, QuantTrio/GLM-5.1-AWQ, --served-model-name, zai-org/GLM-5.1-FP8]", servingModel = "zai-org/GLM-5.1-FP8")!!
        assertEquals("zai-org/GLM-5.1-FP8", glm.servedName)
    }

    @Test fun detectsPositionalVllmServeForm() {
        // Newer vLLM: `vllm serve <repo>` positional (gemma-4 uses this), no
        // --model-path. The block-scalar / multi-line shape from the real compose.
        val yaml = """
        command:
          - >
            exec vllm serve RedHatAI/gemma-4-31B-it-FP8-block
            --revision 9a9994e657
            --served-model-name google/gemma-4-31B-it
        """.trimIndent()
        val g = ModelArtifact.parse(yaml, servingModel = "google/gemma-4-31B-it")!!
        assertEquals("RedHatAI/gemma-4-31B-it-FP8-block", g.modelPath)
        assertEquals("google/gemma-4-31B-it", g.servedName)
        assertEquals("9a9994e657", g.revision)
    }

    @Test fun combinedComposeMixingBothFormsFindsGemma() {
        // The live failure: DeepSeek/Qwen use --model-path, Gemma uses `vllm serve`
        // positional in the SAME combined compose. All must be found, and a Gemma
        // request must select Gemma (not fall back to the first).
        val combined = """
        services:
          ds:
            command: [--model-path, deepseek-ai/DeepSeek-V4-Flash, --served-model-name, deepseek-ai/DeepSeek-V4-Flash]
          gm:
            command:
              - >
                exec vllm serve google/gemma-4-31B
                --served-model-name google/gemma-4-31B-it
        """.trimIndent()
        assertEquals(2, ModelArtifact.parseAll(combined).size)
        val g = ModelArtifact.parse(combined, servingModel = "google/gemma-4-31B-it")!!
        assertEquals("google/gemma-4-31B", g.modelPath)
        assertEquals("google/gemma-4-31B-it", g.servedName)
    }

    /** Boundary matching: FP8 must not be found inside FP16, and the tag must sit on a non-alphanumeric boundary. */
    @Test fun quantTagBoundaries() {
        assertEquals("FP16", ModelArtifact.quantTag("org/Model-FP16"))
        assertEquals("FP8", ModelArtifact.quantTag("org/Model-FP8"))
        assertEquals("AWQ", ModelArtifact.quantTag("org/GLM-5.1-AWQ"))
        assertEquals("W4A16", ModelArtifact.quantTag("org/Model-W4A16"))
        assertNull(ModelArtifact.quantTag("org/PlainModel"))
    }

    /** Android-only: the session's parse seam now yields the artifact, and the reused-node vendor guard still drops a mismatch. */
    @Test fun sessionSeamParsesTheRequestedServer() {
        val a = ConfidentialSession.Services().parseArtifact(combinedYAML, "Qwen/Qwen3.6-27B-FP8")!!
        assertEquals("Qwen/Qwen3.6-27B", a.modelPath)
        // The pinned path carries no quant tag, so there is nothing to compare — no drift is claimed.
        assertNull(a.quant)
        assertEquals(false, a.quantDrift)
    }

    /**
     * near.ai's GLM-5.3 flash compose (prod/GLM-5.3-Flash-SGL-TP4.yaml at 01356c5d) points sglang at the local Hugging Face
     * cache, so the last path component is the snapshot hash — which the trust ladder then printed as the model's name:
     * "encrypted to 84c6a6aa9497…1033". iOS fixed it 2026-09-09 (d1ac2be); the Pixel still showed it 2026-09-23.
     */
    @Test fun hfCachePathNamesTheRepoNotTheSnapshot() {
        val a = ModelArtifact.parse("""
            command: >
              python3 -m sglang.launch_server
              --model-path /root/.cache/huggingface/hub/models--zai-org--GLM-5.3-Flash/snapshots/84c6a6aa9497188e15a635ba793b0f95a79b1033
              --revision 84c6a6aa9497188e15a635ba793b0f95a79b1033
              --served-model-name z-ai/glm-5.3-flash
        """.trimIndent())
        assertEquals("zai-org/GLM-5.3-Flash", a?.modelPath)
        assertEquals("GLM-5.3-Flash", a?.baseModelName)
        assertEquals("84c6a6aa9497188e15a635ba793b0f95a79b1033", a?.revision)
        assertEquals("z-ai/glm-5.3-flash", a?.servedName)
        assertEquals(false, a?.quantDrift)
        // The same shape through the multi-server path, and a snapshot with no explicit --revision becomes the revision.
        val bare = ModelArtifact.parseAll("""
            command: >
              vllm serve /models/hub/models--org--Some-Model-FP8/snapshots/abc123
              --served-model-name org/Some-Model
        """.trimIndent())
        assertEquals("org/Some-Model-FP8", bare.firstOrNull()?.modelPath)
        assertEquals("abc123", bare.firstOrNull()?.revision)
        assertEquals("FP8", bare.firstOrNull()?.quant)
    }

    /**
     * The real document, end to end: near.ai's GLM-5.3 flash compose (1,228 lines, a combined node) scoped to the requested
     * model the way the verifier does, then parsed. The hand-written excerpt above passed while the phone still showed the
     * hash (iOS, 2026-09-09) — both composes the host's nodes ran that day: the TP4 file (01356c5d) and the Canary (aea5fbfd).
     */
    @Test fun realGLM53FlashComposeNamesTheModel() {
        for (name in listOf("glm53flash_compose.yaml", "glm53flash_canary_compose.yaml")) {
            val raw = javaClass.getResource("/fixtures/$name")!!.readText()
            val scoped = PlaintextExposure.scoped(raw, toModel = "z-ai/glm-5.3-flash")
            val all = ModelArtifact.parseAll(scoped)
            val picked = ModelArtifact.parse(scoped, servingModel = "z-ai/glm-5.3-flash")
            assertEquals("$name: got ${picked?.modelPath} served=${picked?.servedName} among ${all.map { "${it.modelPath}|${it.servedName ?: "-"}" }}",
                "GLM-5.3-Flash", picked?.baseModelName)
        }
    }
}
