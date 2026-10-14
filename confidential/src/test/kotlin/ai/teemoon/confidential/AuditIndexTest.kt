// ports: teemoonTests/AuditIndexTests.swift @ v1.0.4 (the AuditIndex suite; EgressRollupTests' splitNodeFindings
//        belongs to TrustLadderSupport and everydayRollupWorstClassWins is in TrustVerdictTest)
//        teemoonTests/AuditLinkScopingTests.swift @ v1.0.1
//        teemoonTests/AuditIndexStampTests.swift @ v1.0.1
//        teemoonTests/AuditIndexTests.swift @ v1.0.4 (AuditIndexRefreshTests: secondRefreshRevalidates, freshSnapshotSkipsTheFetch)
package ai.teemoon.confidential

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The teemoonai/audits path spec is a permanence contract: the normalization rules here must match the repo README exactly and never change. */
class AuditIndexTest {

    // MARK: the frozen normalization rules

    @Test fun bareOfficialImageGainsLibrary() = assertEquals("docker.io/library/nginx", AuditIndex.normalizedRef("nginx"))
    @Test fun bareNamespacedImageGainsDockerIO() = assertEquals("docker.io/nearaidev/vllm-proxy-rs", AuditIndex.normalizedRef("nearaidev/vllm-proxy-rs"))
    @Test fun tagIsStripped() = assertEquals("docker.io/lmsysorg/sglang", AuditIndex.normalizedRef("lmsysorg/sglang:dev-cu12"))
    @Test fun explicitRegistryKept() = assertEquals("ghcr.io/astral-sh/uv", AuditIndex.normalizedRef("ghcr.io/astral-sh/uv:python3.11-bookworm-slim"))
    @Test fun registryPortIsNotATag() = assertEquals("registry.example:5000/team/img", AuditIndex.normalizedRef("registry.example:5000/team/img"))
    @Test fun digestPrefixStripped() { assertEquals("abc123", AuditIndex.bareDigest("sha256:abc123")); assertEquals("abc123", AuditIndex.bareDigest("abc123")) }
    @Test fun yamlExtensionStripped() = assertEquals("nearai/cvm-compose-files/prod/X", AuditIndex.stripYamlExtension("nearai/cvm-compose-files/prod/X.yaml"))

    // MARK: gating — no index entry, no link (never overclaim)

    private val digest = "b183677a5d32267539b9b21ec45327a4f3be0a013afeb608c68c4d76e9472e36"
    private val fileSHA = "eb00b404e3218e2e8c8ab8da5845af10ce79929fd232fe8ac3d2f688582817be"
    private val osHash = "da9a3d5cc196a1a76d953fb27069be428ddf60a1ce10b0534c3cf968d3053fde"
    private val sglangDigest = "aac6b242680daeb74d2ab1d85f70575357552d7d165d2e5d30eb362797db54a1"
    private val zeros = "0".repeat(64)

    private fun seeded() = AuditIndex(fixedIndex = AuditIndex.Index(
        schema = 1,
        images = mapOf("docker.io/nearaidev/vllm-proxy-rs" to listOf(digest)),
        sources = mapOf("docker.io/lmsysorg/sglang" to mapOf(sglangDigest to AuditIndex.SourceRef("sgl-project/sglang", "8805f4cf166649d0ab7cd728df514ed476690115"))),
        projects = mapOf(
            "docker.io/otel/opentelemetry-collector-contrib" to AuditIndex.Project("OpenTelemetry Collector Contrib", "open-telemetry/opentelemetry-collector-contrib", "open source · shipped build not source-audited"),
            "nvcr.io/nvidia/k8s/dcgm-exporter" to AuditIndex.Project("NVIDIA DCGM Exporter", "NVIDIA/dcgm-exporter", "pinned by tag · can drift · not source-audited")),
        tagAudits = mapOf("nvcr.io/nvidia/k8s/dcgm-exporter" to listOf("4.5.2-4.8.1-distroless")),
        manifests = mapOf("nearai/cvm-compose-files/prod/GLM-5.1-SGL-AWQ-TP4.yaml" to listOf(fileSHA)),
        measured = listOf("2c650eae81601afade86d1aa5ad898473b992081998026bb42ee1524ce99ee38"),
        os = listOf(osHash), verdicts = null))

    @Test fun imageSourceLink_pinsRunningVersion() {
        val s = seeded().imageSourceLink("lmsysorg/sglang", "sha256:$sglangDigest")!!
        assertEquals("sgl-project/sglang @ 8805f4c", s.title)
        assertEquals("https://github.com/sgl-project/sglang/tree/8805f4cf166649d0ab7cd728df514ed476690115", s.url)
        assertNull(seeded().imageSourceLink("lmsysorg/sglang", zeros))   // unrecorded digest → no pin
    }

    @Test fun projectPointer_resolvesForThirdPartyDigestImage() {
        val p = seeded().projectPointer("otel/opentelemetry-collector-contrib")!!
        assertEquals("OpenTelemetry Collector Contrib", p.name)
        assertEquals("open-telemetry/opentelemetry-collector-contrib", p.repo)
        assertEquals("open source · shipped build not source-audited", p.status)
        assertEquals("https://github.com/open-telemetry/opentelemetry-collector-contrib", p.url)   // project root, NOT a tree/<commit>
    }

    @Test fun projectPointer_resolvesForTagPinnedImageDespiteTagOnRef() {
        val p = seeded().projectPointer("nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless")!!
        assertEquals("NVIDIA/dcgm-exporter", p.repo)
        assertEquals("pinned by tag · can drift · not source-audited", p.status)
    }

    @Test fun projectPointer_unrecordedImageGivesNothing() {
        assertNull(seeded().projectPointer("lmsysorg/sglang"))
        assertNull(AuditIndex(fixedIndex = null).projectPointer("otel/opentelemetry-collector-contrib"))
    }

    @Test fun tagAuditURL_resolvesForAssessedTag() {
        assertEquals("https://github.com/teemoonai/audits/blob/main/images/nvcr.io/nvidia/k8s/dcgm-exporter/tag-4.5.2-4.8.1-distroless.md",
            seeded().tagAuditURL("nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-4.8.1-distroless", "4.5.2-4.8.1-distroless"))
    }

    @Test fun tagAuditURL_unassessedTagGivesNoLink() {
        assertNull(seeded().tagAuditURL("nvcr.io/nvidia/k8s/dcgm-exporter", "9.9.9"))
        assertNull(seeded().tagAuditURL("docker.io/library/nginx", "latest"))
    }

    @Test fun missingProjectAndTagFieldsDecodeToNoLink() {
        val idx = AuditIndex.decodeIndex("""{ "schema": 1, "images": {}, "manifests": {}, "measured": [] }""".encodeToByteArray())!!
        assertNull(idx.projects); assertNull(idx.tagAudits)
        val index = AuditIndex(fixedIndex = idx)
        assertNull(index.projectPointer("otel/opentelemetry-collector-contrib"))
        assertNull(index.tagAuditURL("nvcr.io/nvidia/k8s/dcgm-exporter", "4.5.2-4.8.1-distroless"))
    }

    @Test fun assessedImageLinks() {
        assertEquals("https://github.com/teemoonai/audits/blob/main/images/docker.io/nearaidev/vllm-proxy-rs/sha256-$digest.md",
            seeded().imageAuditURL("nearaidev/vllm-proxy-rs", "sha256:$digest"))
    }

    @Test fun unassessedDigestGivesNoLink() = assertNull(seeded().imageAuditURL("nearaidev/vllm-proxy-rs", zeros))
    @Test fun unknownImageGivesNoLink() = assertNull(seeded().imageAuditURL("evil/injected", digest))
    @Test fun noIndexGivesNoLink() = assertNull(AuditIndex(fixedIndex = null).imageAuditURL("nearaidev/vllm-proxy-rs", digest))

    @Test fun assessedManifestLinks() {
        assertEquals("https://github.com/teemoonai/audits/blob/main/manifests/nearai/cvm-compose-files/prod/GLM-5.1-SGL-AWQ-TP4/sha256-$fileSHA.md",
            seeded().manifestAuditURL("prod/GLM-5.1-SGL-AWQ-TP4.yaml", fileSHA))
    }

    @Test fun measuredHashLinks() {
        val hash = "2c650eae81601afade86d1aa5ad898473b992081998026bb42ee1524ce99ee38"
        assertEquals("https://github.com/teemoonai/audits/blob/main/manifests/measured/sha256-$hash.md", seeded().measuredAuditURL(hash))
    }

    @Test fun assessedOSImageLinks() = assertEquals("https://github.com/teemoonai/audits/blob/main/os/sha256-$osHash.md", seeded().osAuditURL(osHash))
    @Test fun unassessedOSGivesNoLink() = assertNull(seeded().osAuditURL(zeros))

    @Test fun missingOSFieldDecodesToNoLink() {
        val idx = AuditIndex.decodeIndex("""{ "schema": 1, "images": {}, "manifests": {}, "measured": [] }""".encodeToByteArray())!!
        assertNull(idx.os)
        assertNull(AuditIndex(fixedIndex = idx).osAuditURL(osHash))
    }

    @Test fun decodesPublishedSchema() {
        val idx = AuditIndex.decodeIndex("""{ "schema": 1, "updated": "2026-07-19",
          "images": { "docker.io/library/nginx": ["$digest"] },
          "manifests": { "nearai/cvm-compose-files/prod/X.yaml": ["$fileSHA"] },
          "measured": ["$fileSHA"] }""".encodeToByteArray())!!
        assertEquals(1, idx.schema)
        assertEquals(listOf(digest), idx.images["docker.io/library/nginx"])
    }

    /** REGRESSION: the ladder rendered one fixed, reassuring subtitle for every audit link. The verdict now travels with the link and fails closed. */
    @Test fun verdictTravelsWithTheAuditLinkAndFailsClosed() {
        val dig = "a".repeat(64)
        val path = "images/docker.io/lmsysorg/sglang/sha256-$dig.md"
        val idx = AuditIndex(fixedIndex = AuditIndex.Index(schema = 1, images = mapOf("docker.io/lmsysorg/sglang" to listOf(dig)),
            manifests = emptyMap(), measured = emptyList(), verdicts = mapOf(path to "private at deployed flags — one unauthenticated runtime switch remains live")))
        val url = idx.imageAuditURL("lmsysorg/sglang", dig)!!
        assertTrue(idx.verdict(url)!!.contains("unauthenticated runtime switch"))
        assertNull(idx.verdict("${AuditIndex.WEB_BASE}/images/docker.io/x/y/sha256-${"b".repeat(64)}.md"))
        assertNull(idx.verdict("https://example.com/whatever.md"))   // outside the audits repo is never matched
    }

    // MARK: the machine-readable layer (verdictClass + findings + updated)

    private val leakDigest = "c".repeat(64)
    private val leakPath get() = "images/docker.io/lmsysorg/sglang/sha256-$leakDigest.md"
    private val machineLayerJSON get() = """
        { "schema": 1, "updated": "2026-08-04",
          "images": { "docker.io/lmsysorg/sglang": ["$leakDigest"] },
          "manifests": {}, "measured": [],
          "verdicts": { "$leakPath": "LEAKS — prompts logged at INFO" },
          "verdictClass": { "$leakPath": "leaks" },
          "findings": { "$leakPath": [
            { "severity": "high", "deployed": "on", "qualifier": "deployed: ON, unconditional",
              "title": "the full user prompt is logged at INFO on every generation", "anchor": "high-deployed-on-unconditional--the-full-user-prompt" },
            { "severity": "medium", "deployed": null, "qualifier": "mechanism",
              "title": "structural finding with no deployed state", "anchor": "medium-mechanism--structural" } ] } }
    """

    @Test fun decodesMachineReadableLayerAndResolvesAccessors() {
        val parsed = AuditIndex.decodeIndex(machineLayerJSON.encodeToByteArray())!!
        assertEquals("2026-08-04", parsed.updated)
        val index = AuditIndex(fixedIndex = parsed)
        val url = index.imageAuditURL("lmsysorg/sglang", leakDigest)!!
        assertEquals(AuditVerdictClass.LEAKS, index.verdictClass(url))
        val findings = index.findings(url)
        assertEquals(2, findings.size)
        assertEquals("high", findings[0].severity); assertEquals("on", findings[0].deployed)
        assertTrue(findings[0].anchor.startsWith("high-deployed-on"))
        assertNull(findings[1].deployed)   // JSON null → null, not a decode failure
        assertNull(index.verdictClass("https://example.com/whatever.md"))
        assertTrue(index.findings("https://example.com/whatever.md").isEmpty())
    }

    @Test fun unknownFutureVerdictClassFailsToNeutral() {
        val parsed = AuditIndex.decodeIndex(machineLayerJSON.replace("\"leaks\"", "\"on-fire\"").encodeToByteArray())!!
        val index = AuditIndex(fixedIndex = parsed)
        val url = index.imageAuditURL("lmsysorg/sglang", leakDigest)!!
        assertNull(index.verdictClass(url))
        assertTrue(index.verdict(url)!!.startsWith("LEAKS"))   // the verbatim verdict still travels
    }

    @Test fun schemaMismatchFailsClosedToNoIndex() {
        assertNull(AuditIndex.decodeIndex("""{ "schema": 2, "images": {}, "manifests": {}, "measured": [] }""".encodeToByteArray()))
        assertNotNull(AuditIndex.decodeIndex("""{ "schema": 1, "images": {}, "manifests": {}, "measured": [] }""".encodeToByteArray()))
    }

    @Test fun indexWithoutMachineLayerStaysNeutral() {
        val parsed = AuditIndex.decodeIndex("""{ "schema": 1, "images": {}, "manifests": {}, "measured": [] }""".encodeToByteArray())!!
        assertNull(parsed.verdictClass); assertNull(parsed.findings); assertNull(parsed.updated)
        val index = AuditIndex(fixedIndex = parsed)
        val url = "${AuditIndex.WEB_BASE}/os/sha256-${"d".repeat(64)}.md"
        assertNull(index.verdictClass(url)); assertTrue(index.findings(url).isEmpty())
    }

    // MARK: AuditLinkScopingTests — the enclave scope gate

    private val ddRef = "docker.io/datadog/agent"
    private val ddDigest = "5556fb80b952832719a76b016f905616c76ee0989a239c4680c6220148e865d6"
    private fun indexWithDatadog() = AuditIndex(fixedIndex = AuditIndex.Index(schema = 1, images = mapOf(ddRef to listOf(ddDigest)),
        manifests = emptyMap(), measured = emptyList(), os = emptyList()))

    @Test fun modelEnclaveImageSurfacesAuditLink() {
        assertEquals("https://github.com/teemoonai/audits/blob/main/images/docker.io/datadog/agent/sha256-$ddDigest.md",
            indexWithDatadog().scopedImageAuditURL(inScope = true, image = "datadog/agent", digestFull = "sha256:$ddDigest"))
    }

    /** THE regression: the model-node review must not leak onto the out-of-scope gateway row running the identical digest. */
    @Test fun gatewaySameDigestGetsNoAuditLink() = assertNull(indexWithDatadog().scopedImageAuditURL(false, "datadog/agent", "sha256:$ddDigest"))
    @Test fun inScopeButUnassessedDigestGetsNoLink() = assertNull(indexWithDatadog().scopedImageAuditURL(true, "datadog/agent", "sha256:$zeros"))
    @Test fun missingDigestGetsNoLink() = assertNull(indexWithDatadog().scopedImageAuditURL(true, "datadog/agent", null))

    // MARK: AuditIndexStampTests

    private val now = 1_800_000_000_000L

    @Test fun missingIndexProducesNoStamp() = assertNull(AuditIndexStamp.make(false, "2026-01-01", now, now))

    @Test fun unknownFetchAgeIsStale() {
        val s = AuditIndexStamp.make(true, "2026-08-01", null, now)!!
        assertTrue(s.stale); assertTrue(s.text.contains("fetch age unknown")); assertTrue(s.text.contains("verdicts may lag"))
    }

    @Test fun emptyUpdatedReadsUnknown() = assertTrue(AuditIndexStamp.make(true, "", null, now)!!.text.contains("updated unknown"))

    @Test fun olderThanADayIsStale() {
        val s = AuditIndexStamp.make(true, "2026-08-01", now - (AuditIndexStamp.STALE_AFTER_SECONDS + 1) * 1000, now)!!
        assertTrue(s.stale); assertTrue(s.text.contains("verdicts may lag")); assertTrue(s.text.contains("cached"))
    }

    @Test fun freshIndexIsNotStale() {
        val s = AuditIndexStamp.make(true, "2026-08-24", now - 60_000, now)!!
        assertFalse(s.stale); assertTrue(s.text.contains("2026-08-24")); assertTrue(s.text.contains("refreshed"))
    }

    // MARK: Android-only — the load path over the seams

    @Test fun loadIfNeededSeedsFromTheStoreThenAdoptsAFreshFetchAndPersistsIt() = runTest {
        val saved = """{ "schema": 1, "images": {"docker.io/library/nginx": ["$digest"]}, "manifests": {}, "measured": [] }""".encodeToByteArray()
        val fresh = machineLayerJSON.encodeToByteArray()
        var persisted: Pair<ByteArray, Long>? = null
        val store = object : AuditIndex.Store {
            override fun loadIndex() = saved
            // Older than the TTL: a younger snapshot answers the launch without a fetch (385948e, pinned below).
            override fun loadFetchedAtMs() = 1_000L
            override fun save(index: ByteArray, fetchedAtMs: Long) { persisted = index to fetchedAtMs }
        }
        val later = 1_000L + 3_600_000L + 4_000L
        val idx = AuditIndex(fetch = { AuditIndex.Reply(200, fresh) }, store = store, nowMs = { later })
        assertNull(idx.index)
        idx.loadIfNeeded(this)
        // Seeded synchronously from the snapshot, with its own fetch time.
        assertNotNull(idx.imageAuditURL("nginx", digest)); assertEquals(1_000L, idx.loadedAtMs)
        advanceUntilIdle()
        // The fetch replaced it and was persisted with the new time.
        assertEquals("2026-08-04", idx.index?.updated); assertEquals(later, idx.loadedAtMs)
        assertEquals(later, persisted?.second)
        // Within the TTL a second call is a no-op.
        idx.loadIfNeeded(this); advanceUntilIdle()
        assertEquals(later, idx.loadedAtMs)
    }

    /**
     * The ladder redraws when the index lands: iOS's AuditIndex is @Observable, and the Compose snapshot is keyed on
     * [AuditIndex.revision]. Seeding from the store and adopting the fetch each bump it; a failed fetch does not.
     */
    @Test fun revisionBumpsWhenTheIndexChangesAndOnlyThen() = runTest {
        val idx = AuditIndex(fetch = { AuditIndex.Reply(200, machineLayerJSON.encodeToByteArray()) }, nowMs = { 5_000L })
        val before = idx.revision.value
        idx.loadIfNeeded(this); advanceUntilIdle()
        assertTrue("an adopted fetch must bump the revision the sheet is keyed on", idx.revision.value > before)
        val down = AuditIndex(fetch = { null })
        val r0 = down.revision.value
        down.loadIfNeeded(this); advanceUntilIdle()
        assertEquals("nothing changed, nothing to redraw", r0, down.revision.value)
    }

    /** An in-memory Store that keeps the ETag, as the app's file store does. */
    private class MemoryStore : AuditIndex.Store {
        var bytes: ByteArray? = null; var at: Long? = null; var etag: String? = null
        override fun loadIndex() = bytes
        override fun loadFetchedAtMs() = at
        override fun save(index: ByteArray, fetchedAtMs: Long) { bytes = index; at = fetchedAtMs }
        override fun loadETag() = etag
        override fun saveETag(etag: String?) { this.etag = etag }
        override fun touch(fetchedAtMs: Long) { at = fetchedAtMs }
    }
    /** Serves the index with an ETag; a matching If-None-Match gets a bodiless 304. Records every If-None-Match sent. */
    private class IndexStub {
        val sent = ArrayList<String?>()
        val etag = "\"idx-v1\""
        val body = """{"schema":1,"images":{"nearaidev/x":["sha256:abc"]},"manifests":{},"measured":[]}""".encodeToByteArray()
        val fetch: suspend (String?) -> AuditIndex.Reply? = { inm -> sent += inm; if (inm == etag) AuditIndex.Reply(304, ByteArray(0), etag) else AuditIndex.Reply(200, body, etag) }
    }

    /** A second refresh sends the ETag, and a 304 keeps the index (iOS 385948e: ~375 KB per cold open of the sheet otherwise). */
    @Test fun secondRefreshRevalidates() = runTest {
        val stub = IndexStub(); var now = 10_000L
        val index = AuditIndex(fetch = stub.fetch, store = MemoryStore(), nowMs = { now })
        index.refresh()
        assertEquals(listOf("sha256:abc"), index.index?.images?.get("nearaidev/x"))
        assertNull(stub.sent.first())
        val firstLoad = index.loadedAtMs!!
        now += 1_000
        index.refresh()
        assertEquals(2, stub.sent.size)
        assertEquals(stub.etag, stub.sent.last())
        assertEquals(listOf("sha256:abc"), index.index?.images?.get("nearaidev/x"))
        assertTrue(index.loadedAtMs!! >= firstLoad)
    }

    /** A snapshot younger than the TTL is not refetched at launch. */
    @Test fun freshSnapshotSkipsTheFetch() = runTest {
        val stub = IndexStub(); val store = MemoryStore(); val now = 10_000L
        AuditIndex(fetch = stub.fetch, store = store, nowMs = { now }).refresh()
        assertEquals(1, stub.sent.size)
        // A new process: only the persisted snapshot, fetched a moment ago.
        val relaunched = AuditIndex(fetch = stub.fetch, store = store, nowMs = { now + 200 })
        relaunched.loadIfNeeded(this); advanceUntilIdle()
        assertEquals(listOf("sha256:abc"), relaunched.index?.images?.get("nearaidev/x"))
        assertEquals("the fresh snapshot should have answered", 1, stub.sent.size)
    }

    /** A failed fetch keeps whatever we had; a schema-2 body is a failure too. */
    @Test fun failedOrForeignFetchKeepsTheCachedIndex() = runTest {
        val cached = AuditIndex.decodeIndex("""{ "schema": 1, "images": {}, "manifests": {}, "measured": ["$fileSHA"] }""".encodeToByteArray())
        val down = AuditIndex(fixedIndex = null, fetch = { null }, nowMs = { 1L })
        down.loadIfNeeded(this); advanceUntilIdle()
        assertNull(down.index)
        val v2 = AuditIndex(fetch = { AuditIndex.Reply(200, """{ "schema": 2, "images": {}, "manifests": {}, "measured": [] }""".encodeToByteArray()) })
        v2.loadIfNeeded(this); advanceUntilIdle()
        assertNull(v2.index)
        val skipped = AuditIndex(fetch = { AuditIndex.Reply(200, machineLayerJSON.encodeToByteArray()) }, skipLive = true)
        skipped.loadIfNeeded(this); advanceUntilIdle()
        assertNull(skipped.index)
        assertNotNull(cached)
    }
}
