// ports: teemoonTests/EnclaveGroupingTests.swift @ v1.0.1
//        + anUnverifiedRowLinksEveryRepoTheAppAsked (Android-only pin of EnclaveGrouping.swift @ 4b9d154; iOS has none)
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EnclaveGroupingTest {

    private val digest = "ab".repeat(32)
    private val datadogDigest = "5556fb80b952832719a76b016f905616c76ee0989a239c4680c6220148e865d6"

    private fun emptyAudit() = AuditIndex(fixedIndex = AuditIndex.Index(schema = 1, images = emptyMap(), manifests = emptyMap(), measured = emptyList(), os = emptyList()))
    private fun datadogAudit() = AuditIndex(fixedIndex = AuditIndex.Index(schema = 1, images = mapOf("docker.io/datadog/agent" to listOf(datadogDigest)), manifests = emptyMap(), measured = emptyList(), os = emptyList()))

    private fun record(gpuHash: String? = "gpu-hash", osHash: String? = null) = AttestationRecord(
        composeHash = "gateway-hash", mrtd = "mrtd", osImageHash = "gateway-os", intelQuote = "",
        composeManifest = null, gpuArch = null, gpuNodeComposeHash = gpuHash, modelFileHash = null,
        signingAddress = null, gpuSigningAddress = null, modelEd25519PubKey = null,
        quoteVerification = null, gpuQuoteVerification = null, modelQuoteVerification = null,
        fetchedAt = Instant.now(), providerID = "p", modelOSImageHash = osHash)

    private fun ref(image: String, digest: String = this.digest, hosts: List<String>) = ImageRef(image, digest, hosts = hosts)

    private fun grouping(attestation: AttestationRecord? = null, provenance: ManifestProvenance? = null, inner: String? = null, audit: AuditIndex? = null) =
        EnclaveGrouping(attestation ?: record(), provenance, inner, null, audit ?: emptyAudit())

    @Test fun noProvenanceAndNoMrConfigIsEmpty() { assertTrue(grouping(attestation = record(gpuHash = null), provenance = null).groups().isEmpty()) }

    @Test fun emptyHostFallsToTheModelGroupOnce() {
        val groups = grouping(provenance = ManifestProvenance.AllVerified(listOf(ref("nearaidev/engine", hosts = emptyList())), emptyList())).groups()
        assertTrue(groups.first { it.primary }.images.any { it.name == "nearaidev/engine" })
        assertFalse(groups.first { !it.primary }.images.any { it.name == "nearaidev/engine" })
    }

    @Test fun gatewayHostDoesNotAppearOnTheModel() {
        val groups = grouping(provenance = ManifestProvenance.AllVerified(listOf(ref("nearaidev/gateway-api", hosts = listOf("gateway"))), emptyList())).groups()
        assertFalse(groups.first { it.primary }.images.any { it.name == "nearaidev/gateway-api" })
        assertTrue(groups.first { !it.primary }.images.any { it.name == "nearaidev/gateway-api" })
    }

    @Test fun guestOSIsModelOnlyAndFirst() {
        val groups = grouping(attestation = record(osHash = "oshashoshashoshashoshashoshashoshashoshashoshashoshashoshash12"),
            provenance = ManifestProvenance.AllVerified(listOf(ref("nearaidev/engine", hosts = listOf("model"))), emptyList())).groups()
        assertEquals(ImageEntry.Kind.GUEST_OS, groups.first { it.primary }.images.first().kind)
        assertFalse(groups.first { !it.primary }.images.any { it.kind == ImageEntry.Kind.GUEST_OS })
    }

    /**
     * 4b9d154: the unverified row links every repo the app asked, guessed first — on GLM-5.3 flash the one link went to
     * nearai/sglang, which does not exist, while the attestation sat in the compose repo the app also asked.
     */
    @Test fun anUnverifiedRowLinksEveryRepoTheAppAsked() {
        val d = "a".repeat(64)
        val e = EnclaveGrouping.unverifiedEntry(ProvenanceFailure(ImageRef("nearaidev/sglang", d), AttestationFixtures.fetchFailed()))
        assertEquals(listOf("attestation api · nearai/sglang", "attestation api · nearai/cvm-compose-files", "sigstore"), e.links.map { it.title })
        assertEquals("https://api.github.com/repos/nearai/cvm-compose-files/attestations/sha256:$d", e.links[1].url)
    }

    @Test fun unverifiedOutranksVerifiedInTheModelGroup() {
        val bad = ProvenanceFailure(ref("nearaidev/broken", digest, listOf("model")), ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("404")))
        val good = ref("nearaidev/engine", "cd".repeat(32), listOf("model"))
        val kinds = grouping(provenance = ManifestProvenance.Incomplete(listOf(good), listOf(bad), emptyList())).groups().first { it.primary }.images.map { it.kind }
        assertEquals(ImageEntry.Kind.UNVERIFIED, kinds.first()); assertTrue(ImageEntry.Kind.REGISTRY in kinds)
    }

    @Test fun gatewaySameDigestDoesNotCarryTheModelAudit() {
        val image = ImageRef("datadog/agent", datadogDigest, hosts = listOf("gateway", "model"))
        val groups = grouping(provenance = ManifestProvenance.AllVerified(emptyList(), listOf(image)), audit = datadogAudit()).groups()
        assertNotNull(groups.first { it.primary }.images.first { it.name == "datadog/agent" }.auditURL)
        assertNull(groups.first { !it.primary }.images.first { it.name == "datadog/agent" }.auditURL)
    }

    @Test fun belongsEmptyHostIsPrimaryOnly() {
        val empty = ref("x", hosts = emptyList())
        assertTrue(EnclaveGrouping.belongs(empty, "model", true)); assertFalse(EnclaveGrouping.belongs(empty, "gateway", false))
        val both = ref("x", hosts = listOf("model", "gateway"))
        assertTrue(EnclaveGrouping.belongs(both, "model", true)); assertTrue(EnclaveGrouping.belongs(both, "gateway", false))
    }

    // MARK: Android-only — the tiers, the recipe split and the egress rollup over a production-shaped inner compose

    private val proxyDigest = "b183677a5d32267539b9b21ec45327a4f3be0a013afeb608c68c4d76e9472e36"
    private val sglangDigest = "aac6b242680daeb74d2ab1d85f70575357552d7d165d2e5d30eb362797db54a1"
    private val inner = """
services:
  proxy-glm51:
    image: nearaidev/vllm-proxy-rs@sha256:$proxyDigest
    environment:
      - OHTTP_ENABLED=true
  model-sg:
    image: glm51-sgl-awq-tp4-patched:local
    build:
      dockerfile_inline: |
        FROM lmsysorg/sglang@sha256:$sglangDigest
        RUN echo patch
    command: python -m sglang.launch_server --model-path QuantTrio/GLM-5.1-AWQ --revision 8f60817aa28023f2607850d1a1e51d21aa34817a --log-requests-level 0
  dcgm:
    image: nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless
    cap_add:
      - SYS_ADMIN
    runtime: nvidia
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
""".trimIndent()

    @Test fun modelGroupTiersRecipeAndEgressOverAProductionShapedNode() {
        val leakPath = "images/docker.io/lmsysorg/sglang/sha256-$sglangDigest.md"
        val audit = AuditIndex(fixedIndex = AuditIndex.Index(schema = 1,
            images = mapOf("docker.io/lmsysorg/sglang" to listOf(sglangDigest), "docker.io/nearaidev/vllm-proxy-rs" to listOf(proxyDigest)),
            sources = mapOf("docker.io/lmsysorg/sglang" to mapOf(sglangDigest to AuditIndex.SourceRef("sgl-project/sglang", "8805f4cf166649d0ab7cd728df514ed476690115"))),
            projects = mapOf("nvcr.io/nvidia/k8s/dcgm-exporter" to AuditIndex.Project("NVIDIA DCGM Exporter", "NVIDIA/dcgm-exporter", "pinned by tag · can drift")),
            tagAudits = mapOf("nvcr.io/nvidia/k8s/dcgm-exporter" to listOf("4.5.2-4.8.1-distroless")),
            manifests = emptyMap(), measured = emptyList(),
            verdictClass = mapOf(leakPath to "leaks"),
            findings = mapOf(leakPath to listOf(AuditIndex.Finding("high", "on", "deployed: ON", "prompt logged", "high-prompt")))))
        val rec = AttestationRecord.preview.copy(gpuNodeComposeManifest = null)
        val model = EnclaveGrouping(rec, ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/vllm-proxy-rs", proxyDigest, hosts = listOf("model"))), emptyList()),
            inner, ModelLayerVerification.VERIFIED, audit).groups().first { it.primary }

        val byName = model.images.associateBy { it.name }
        // The proxy is the terminator, the in-enclave engine row is named after its base and carries the model-server role.
        assertEquals(PlaintextExposure.Role.E2EE_TERMINATOR, byName.getValue("nearaidev/vllm-proxy-rs").plaintextRole)
        val engine = byName.getValue("lmsysorg/sglang")
        assertEquals(ImageEntry.Kind.LOCAL, engine.kind); assertEquals(PlaintextExposure.Role.MODEL_SERVER, engine.plaintextRole)
        assertTrue(engine.links.any { it.title == "sgl-project/sglang @ 8805f4c" })
        assertTrue(engine.configLines.any { it.id == "logging" })
        // The tag-pinned dcgm row: device privilege, tag audit, project pointer, and inside the recipe card.
        val dcgm = byName.getValue("nvcr.io/nvidia/k8s/dcgm-exporter")
        assertEquals(ImageEntry.Kind.TAG_PINNED, dcgm.kind); assertEquals("4.5.2-4.8.1-distroless", dcgm.tag)
        assertEquals(PlaintextExposure.Capability.DEVICE_PRIVILEGE, dcgm.capability)
        assertNotNull(dcgm.auditURL); assertEquals("NVIDIA/dcgm-exporter", dcgm.projectPointer?.repo); assertTrue(dcgm.isRecipe)
        // Guest OS first, then the bands: terminator, model server, then device privilege.
        assertEquals(ImageEntry.Kind.GUEST_OS, model.images.first().kind)
        assertEquals(listOf("nearaidev/vllm-proxy-rs", "lmsysorg/sglang", "nvcr.io/nvidia/k8s/dcgm-exporter"), model.images.drop(1).map { it.name })
        assertFalse(model.images.first().isRecipe)
        // Recipe card and the egress rollup keyed off the engine's audit page.
        assertEquals(rec.modelFileHash, model.recipe?.fileSHA); assertEquals(ModelLayerVerification.VERIFIED, model.recipe?.verification)
        assertEquals("yaml @ c545c95", model.recipe?.yamlLink?.title)
        assertEquals(1, model.egressFindings.size)
        // The in-enclave engine row has no registry digest of its own, so its provenance label is the bare base name.
        assertEquals("sglang", model.egressFindings.single().source)
        assertTrue(model.egressFindings.single().url.endsWith("#high-prompt"))
        assertTrue(model.plaintextCaption!!.contains("images without a tag"))
        assertNotNull(model.deployment)
    }
}
