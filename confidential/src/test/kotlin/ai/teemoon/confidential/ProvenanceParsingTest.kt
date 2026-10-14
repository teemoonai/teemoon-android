// ports: teemoonTests/ImageProvenanceTests.swift @ v1.0.4 (the "ProvenanceService parsing" and "ProvenanceService
//        manifest parsing" suites; the Sigstore verifier, cache-upgrade and 404-classification suites are the
//        ProvenanceVerifier / ProvenanceService rows)
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceParsingTest {

    @Test fun imageRefs_extractsAllDigests() {
        val refs = ProvenanceParsing.imageRefs("""
services:
  cloud-api:
    image: nearaidev/cloud-api@sha256:ac8a539ce1ac9ae3ebd0dfcffdba18effaaedfa69685fce220e3b3a35357e326
  mesh:
    image: nearaidev/dstack-vpc@sha256:03bd4b222d07059af91fc3d2aa851026cecbecf719ba7423616058272db57b2c
""")
        assertEquals(2, refs.size)
        assertEquals("nearaidev/cloud-api", refs[0].image)
        assertEquals("ac8a539ce1ac9ae3ebd0dfcffdba18effaaedfa69685fce220e3b3a35357e326", refs[0].digest)
        assertEquals("nearaidev/dstack-vpc", refs[1].image)
    }

    /** A fully-pinned `repo:tag@sha256:…` ref must resolve to `repo`, NOT the tag. */
    @Test fun imageRefs_stripsTagFromPinnedRef() {
        val d = "9e02c8e1fe2790a1c445bd5f6814305fe43639a4adb01c8ad1e8e21e750bf581"
        val refs = ProvenanceParsing.imageRefs("image: lmsysorg/sglang:v0.5.12-cu129@sha256:$d")
        assertEquals(1, refs.size); assertEquals("lmsysorg/sglang", refs[0].image); assertEquals(d, refs[0].digest)
        assertEquals("lmsysorg/sglang", ProvenanceParsing.imageRefs("image: lmsysorg/sglang@sha256:$d").first().image)
    }

    @Test fun dockerfileBaseImage_extractsFromInlineDockerfile() {
        val d = "aac6b242680daeb74d2ab1d85f70575357552d7d165d2e5d30eb362797db54a1"
        val compose = """
x-awq-build: &awq-build
  dockerfile_inline: |
    FROM lmsysorg/sglang:dev-cu12@sha256:$d
    RUN python3 - <<'PYEOF'
    print("patch")
    PYEOF
services:
  model:
    image: glm51-sgl-awq-tp4-patched:local
    build: *awq-build
""".trimIndent()
        val base = ProvenanceParsing.dockerfileBaseImage(compose)!!
        assertEquals("lmsysorg/sglang", base.image); assertEquals(d, base.digest)
        assertNull(ProvenanceParsing.dockerfileBaseImage("services:\n  m:\n    image: x@sha256:$d"))   // No FROM → null
        assertTrue(ProvenanceParsing.hasInEnclaveEngineBuild(compose))
    }

    /** REGRESSION: a flat scan for the last FROM handed one model's base to another. Several candidates → fail closed. */
    @Test fun dockerfileBaseImage_failsClosedWhenSeveralEnginesAreBuiltInEnclave() {
        val flux = "8ece90ad52faa8b56149f0117227d9009db34513213e35990da468aeb6fe0b75"
        val whisper = "ccd6a6dbf4aba4e94c6f7052d1835d6e742082b6a5095276552e9b7a5a47c2e5"
        val compose = """
services:
  model-sg-flux2-klein-4b-tp1:
    image: sglang-diffusion
    build:
      dockerfile_inline: |
        FROM lmsysorg/sglang@sha256:$flux
        RUN pip install diffusers
  model-vllm-whisper-large-v3-tp1:
    image: vllm-with-audio
    build:
      dockerfile_inline: |
        FROM vllm/vllm-openai@sha256:$whisper
        RUN pip install openai-whisper
""".trimIndent()
        assertNull(ProvenanceParsing.dockerfileBaseImage(compose))
        assertTrue(ProvenanceParsing.hasInEnclaveEngineBuild(compose))
    }

    /** A compose whose engine is a plain digest-pinned registry image builds nothing in-enclave. */
    @Test fun hasInEnclaveEngineBuild_falseForPlainRegistryEngine() {
        val compose = "services:\n  model-vllm-gptoss-120b-mxfp4-tp1-r1:\n    image: vllm/vllm-openai@sha256:6766ce0c459e24b76f3e9ba14ffc0442131ef4248c904efdcbf0d89e38be01fe\n    command: openai/gpt-oss-120b --tensor-parallel-size 1"
        assertFalse(ProvenanceParsing.hasInEnclaveEngineBuild(compose)); assertNull(ProvenanceParsing.dockerfileBaseImage(compose))
    }

    @Test fun imageRefs_deduplicatesByDigest() {
        val d = "ac8a539ce1ac9ae3ebd0dfcffdba18effaaedfa69685fce220e3b3a35357e326"
        assertEquals(1, ProvenanceParsing.imageRefs("a@sha256:$d\nb@sha256:$d").size)
    }

    @Test fun imageRefs_noneWhenNoDigests() { assertTrue(ProvenanceParsing.imageRefs("image: nearaidev/cloud-api:latest").isEmpty()) }

    @Test fun githubRepo_mapsDockerNamespaceToNearai() {
        assertEquals("nearai/cloud-api", ProvenanceParsing.githubRepo("nearaidev/cloud-api"))
        assertEquals("nearai/cloud-api", ProvenanceParsing.githubRepo("cloud-api"))
    }

    @Test fun isNearAIImage_classifiesPublisherNamespace() {
        assertTrue(ProvenanceParsing.isNearAIImage("nearaidev/cloud-api")); assertTrue(ProvenanceParsing.isNearAIImage("nearai/cloud-api"))
        assertTrue(ProvenanceParsing.isNearAIImage("docker.io/nearaidev/dstack-vpc")); assertTrue(ProvenanceParsing.isNearAIImage("ghcr.io/nearai/cvm-ingress"))
        assertFalse(ProvenanceParsing.isNearAIImage("datadog/agent")); assertFalse(ProvenanceParsing.isNearAIImage("otel/opentelemetry-collector-contrib"))
        assertFalse(ProvenanceParsing.isNearAIImage("alpine")); assertFalse(ProvenanceParsing.isNearAIImage("registry.example.com:5000/alpine"))
    }

    /** Compose shell-default syntax leaks a "-" into the image match; it must still classify as near.ai. */
    @Test fun shellDefaultSyntax_stripsLeadingDash() {
        val refs = ProvenanceParsing.imageRefs("services:\n  cm:\n    image: \${COMPOSE_MANAGER_IMAGE:-nearaidev/compose-manager@sha256:${"b".repeat(64)}}")
        assertEquals(1, refs.size); assertEquals("nearaidev/compose-manager", refs.first().image)
        assertTrue(ProvenanceParsing.isNearAIImage(refs.first().image))
    }

    @Test fun multipleManifests_dedupeByDigestAndMergeHosts() {
        val shared = "c".repeat(64); val gwOnly = "d".repeat(64); val nodeOnly = "e".repeat(64)
        val refs = ProvenanceParsing.imageRefs(listOf(
            "gateway" to "image: nearaidev/dstack-vpc@sha256:$shared\nimage: nearaidev/cloud-api@sha256:$gwOnly",
            "model" to "image: nearaidev/dstack-vpc@sha256:$shared\nimage: nearaidev/compose-manager@sha256:$nodeOnly"))
        assertEquals(3, refs.size)
        assertEquals(listOf("gateway", "model"), refs.first { it.digest == shared }.hosts)
        assertEquals(listOf("gateway"), refs.first { it.digest == gwOnly }.hosts)
        assertEquals(listOf("model"), refs.first { it.digest == nodeOnly }.hosts)
    }

    @Test fun launcherImage_queriesComposeManagerRepo() {
        assertEquals("nearai/compose-manager", ProvenanceParsing.githubRepo("nearaidev/compose-manager-launcher"))
        assertEquals("nearai/cloud-api", ProvenanceParsing.githubRepo("nearaidev/cloud-api"))
    }
}
