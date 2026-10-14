// ports: teemoonTests/VerificationRunTests.swift @ v1.0.1 (RecipeClassification, TagPinnedExtraction, RecipeRowPartition)
//        teemoonTests/AuditIndexTests.swift @ v1.0.4 (EgressRollupTests)
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustLadderSupportTest {

    // MARK: RecipeClassification — the in-enclave engine is always recipe, the guest OS never, everything else by declared digest / tag ref.

    private val proxyDigest = "aa11bb22cc33dd44ee55ff6600112233445566778899aabbccddeeff00112233"
    /** A production-shaped inner compose whose telemetry LABELS mention sidecars that are NOT declared here. */
    private val inner = """
services:
  proxy:
    image: nearaidev/vllm-proxy-rs@sha256:$proxyDigest
    labels:
      nearai.otel.scrape: "true"
      com.datadoghq.ad.logs: '[{"source":"vllm-proxy"}]'
  model-sg:
    image: glm51-sgl-awq-tp4-patched:local
    command: sglang serve --model-path QuantTrio/GLM-5.1-AWQ
networks:
  default:
""".trimIndent()

    @Test fun localEngineIsAlwaysRecipe() {
        assertTrue(isRecipeImage(digestFull = null, isLocalEngine = true, isGuestOS = false, innerComposeYaml = inner))
        assertTrue(isRecipeImage(digestFull = "sha256:whatever", isLocalEngine = true, isGuestOS = false, innerComposeYaml = null))
    }

    @Test fun guestOSIsNeverRecipe() {
        assertFalse(isRecipeImage(digestFull = "sha256:$proxyDigest", isLocalEngine = false, isGuestOS = true, innerComposeYaml = inner))
    }

    @Test fun imageDeclaredByDigestInInnerComposeIsRecipe() {
        assertTrue(isRecipeImage(digestFull = "sha256:$proxyDigest", isLocalEngine = false, isGuestOS = false, innerComposeYaml = inner))
    }

    /** THE L1 regression: a sidecar whose NAME appears in telemetry labels but whose DIGEST is not declared must classify as measured. */
    @Test fun labelSubstringDoesNotPullSidecarIntoRecipe() {
        val otelDigest = "ffeeddccbbaa998877665544332211000011223344556677889900aabbccddee"
        assertFalse(isRecipeImage(digestFull = "sha256:$otelDigest", isLocalEngine = false, isGuestOS = false, innerComposeYaml = inner))
    }

    @Test fun noInnerComposeMeansNotRecipe() {
        assertFalse(isRecipeImage(digestFull = "sha256:$proxyDigest", isLocalEngine = false, isGuestOS = false, innerComposeYaml = null))
    }

    @Test fun emptyDigestIsNotRecipe() {
        assertFalse(isRecipeImage(digestFull = null, isLocalEngine = false, isGuestOS = false, innerComposeYaml = inner))
        assertFalse(isRecipeImage(digestFull = "", isLocalEngine = false, isGuestOS = false, innerComposeYaml = inner))
    }

    /** The tag-membership rule: a DIGESTLESS entry is recipe iff its full tag ref is declared as a service image. */
    @Test fun tagRefDeclaredInInnerComposeIsRecipe() {
        val yaml = "services:\n  dcgm:\n    image: nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless\n    labels:\n      com.datadoghq.ad.logs: '[{\"source\":\"dcgm\"}]'"
        assertTrue(isRecipeImage(digestFull = null, tagRef = "nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless", isLocalEngine = false, isGuestOS = false, innerComposeYaml = yaml))
        assertFalse(isRecipeImage(digestFull = null, tagRef = "datadog/agent:7.55.0", isLocalEngine = false, isGuestOS = false, innerComposeYaml = yaml))
    }

    // MARK: TagPinnedExtraction

    @Test fun realDcgmShapeIsExtracted() {
        val yaml = "services:\n  dcgm-exporter:\n    image: nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless\n    cap_add:\n      - SYS_ADMIN\n    runtime: nvidia"
        assertEquals(listOf("nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless"), ProvenanceParsing.tagPinnedImageRefs(yaml))
    }

    /** Flood guards: digest-pinned refs belong to imageRefs, :local builds to the local-engine row. */
    @Test fun digestPinnedAndLocalBuildsAreExcluded() {
        val yaml = "services:\n  proxy:\n    image: nearaidev/vllm-proxy-rs@sha256:${"a".repeat(64)}\n  engine:\n    image: glm51-sgl-awq-tp4-patched:local\n  nginx:\n    image: nginx:1.25"
        assertEquals(listOf("nginx:1.25"), ProvenanceParsing.tagPinnedImageRefs(yaml))
    }

    @Test fun replicasDedupeByFullRef() {
        val yaml = "services:\n  dcgm-r1:\n    image: nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2\n  dcgm-r2:\n    image: nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2"
        assertEquals(listOf("nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2"), ProvenanceParsing.tagPinnedImageRefs(yaml))
    }

    @Test fun shellDefaultSyntaxUnwraps() {
        assertEquals(listOf("nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2"),
            ProvenanceParsing.tagPinnedImageRefs("services:\n  dcgm:\n    image: \${DCGM_IMAGE:-nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2}"))
    }

    @Test fun labelValuesAndCommentsDoNotEmitRefs() {
        val yaml = "services:\n  proxy:\n    image: nearaidev/vllm-proxy-rs@sha256:${"b".repeat(64)}\n    # the old deploy used datadog/agent:7.55.0 here\n    labels:\n      com.datadoghq.ad.logs: '[{\"source\":\"otel/opentelemetry-collector-contrib:0.98.0\"}]'"
        assertTrue(ProvenanceParsing.tagPinnedImageRefs(yaml).isEmpty())
    }

    // MARK: RecipeRowPartition

    private data class Row(val name: String, val handler: Boolean, val tagged: Boolean = true)

    @Test fun partitionsByDataPath() {
        val split = partitionRecipeRows(listOf(Row("proxy", true), Row("engine", true), Row("dcgm", false), Row("otel", false))) { it.handler }!!
        assertEquals(listOf("proxy", "engine"), split.first.map { it.name })
        assertEquals(listOf("dcgm", "otel"), split.second.map { it.name })
    }

    /** FAIL-SOFT: no identified handler → null, so the view falls back to the flat list. */
    @Test fun noHandlersMeansNoSplit() { assertNull(partitionRecipeRows(listOf(Row("proxy", false), Row("otel", false))) { it.handler }) }

    /** INVARIANT: an untagged telemetry row must still be in the partition's telemetry list, never suppressed. */
    @Test fun untaggedTelemetryRowStillRenders() {
        val split = partitionRecipeRows(listOf(Row("engine", true), Row("mystery-sidecar", false, tagged = false))) { it.handler }!!
        assertEquals(listOf("mystery-sidecar"), split.second.map { it.name })
    }

    @Test fun allHandlersYieldsEmptyTelemetry() {
        val split = partitionRecipeRows(listOf(Row("proxy", true), Row("engine", true))) { it.handler }!!
        assertEquals(2, split.first.size); assertTrue(split.second.isEmpty())
    }

    @Test fun emptyInputHasNoSplit() { assertNull(partitionRecipeRows(emptyList<Row>()) { it.handler }) }

    // MARK: EgressRollup

    private fun finding(severity: String, deployed: String?, n: Int) =
        NodeFinding("f$n", severity, deployed, null, "t$n", "s", "https://example.com/p.md#a$n")

    @Test fun liveSplitsFromLatentAndSortsBySeverity() {
        val split = splitNodeFindings(listOf(finding("medium", "on", 1), finding("high", "off", 2), finding("high", "on", 3), finding("info", null, 4), finding("medium", "armed", 5)))
        assertEquals(listOf("f3", "f1"), split.live.map { it.id })     // live = deployed ON only, severity order
        assertEquals(listOf("f2", "f5"), split.latent.map { it.id })
        assertEquals(listOf("f4"), split.info.map { it.id })
    }

    /** A severity this build doesn't know must stay visible, ranked after everything known. */
    @Test fun unknownSeveritySinksToTheBottomButSurvives() {
        assertEquals(listOf("f2", "f1"), splitNodeFindings(listOf(finding("catastrophic", "off", 1), finding("low", "off", 2))).latent.map { it.id })
    }

    @Test fun kindLabelsAreTheDesignsWording() {
        assertEquals("pinned by tag · can drift", ImageEntry.Kind.TAG_PINNED.label)
        assertEquals("built in-enclave", ImageEntry.Kind.LOCAL.label)
    }
}
