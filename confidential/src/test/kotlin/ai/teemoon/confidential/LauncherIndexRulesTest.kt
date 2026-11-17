// ports: teemoonTests/LauncherIndexWiringTests.swift @ 8cbd15c3 (the rules of conformance/nearai-trust/launcher-index-rules.json:
//        the signer, the child's linux/amd64 entry, the bytes' hash, a registry that does not answer; and the search order);
//        each refusal's and outage's words: teemoonTests/LauncherIndexWiringTests.swift @ 83fbc0a3, the rules file at its bytes there
//
// Trust scope (d): the launcher's measured child has no attestation of its own; its multi-platform index does. The
// same inputs give iOS's answer (the maintainer 2026-11-07: "1.1 must match").
package ai.teemoon.confidential

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class LauncherIndexRulesTest {
    private fun res(n: String) = javaClass.getResource("/fixtures/nearai-trust/$n")!!.readBytes()
    private val rules = Json.parseToJsonElement(res("launcher-index-rules.json").decodeToString()).jsonObject
    private val child = rules.getValue("child").jsonPrimitive.content
    private val index = rules.getValue("index_digest").jsonPrimitive.content
    private val indexBytes = res(rules.getValue("index_file").jsonPrimitive.content)
    private val attestation = res(rules.getValue("attestation_file").jsonPrimitive.content)
    private fun cases(rule: String) = rules.getValue(rule).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    /** ImageProvenance.failureReason per reason kind, verbatim (approved 2026-11-07). */
    private val reasons = rules.getValue("failure_reasons").jsonObject
    private fun reason(k: String) = reasons.getValue(k).jsonPrimitive.content
    /** The reason's kind as the rules file names it (iOS ConformanceAttestationVectors.reasonKind). */
    private fun kind(p: ImageProvenance) = ((p as ImageProvenance.Unverified).reason::class.simpleName ?: "").replaceFirstChar { it.lowercase() }

    /** Rule a: org-level trust is not enough — only the builder's workflow on master signs the index. */
    @Test fun ruleA_onlyTheBuilderSigns() {
        for (c in cases("rule_a_signer")) {
            val got = ProvenanceService.signedByItsBuilder(ImageProvenance.Verified(c.s("repository"), c.s("workflow_identity"), c.s("source_ref"), "8e07c358", true))
            assertEquals(c.s("name"), c.s("expect"), if (got is ImageProvenance.Verified) "verified" else "refused")
            if (c.s("expect") == "refused") {
                assertEquals("${c.s("name")}: $got", c.s("reason"), kind(got))
                assertEquals(c.s("name"), reason(c.s("reason")), got.failureReason)
            }
        }
    }

    /**
     * iOS provenance-wrong-build-signer (e03c78a5): an out-of-org signer keeps its own words; an in-org signer that is not
     * the image's build gets different ones, and both stay definitive (neither unpublished nor inconclusive). The old
     * words for an in-org signer, "outside near.ai's org", were false.
     */
    @Test fun anInOrgSignerThatIsNotTheBuildIsWordedApartFromAnOutOfOrgOne() {
        assertEquals("signed inside near.ai, but not by this image\u2019s build", reason("identityNotThisBuild"))
        assertEquals("signed by an identity outside near.ai\u2019s org", reason("identityNotTrusted"))
        val outOfOrg = ImageProvenance.Unverified(ImageProvenance.Reason.IdentityNotTrusted("https://github.com/someone/x/.github/workflows/b.yml@main"))
        assertEquals(reason("identityNotTrusted"), outOfOrg.failureReason)
        val wrongBranch = ProvenanceService.signedByItsBuilder(ImageProvenance.Verified(ProvenanceService.BUILDER_REPOSITORY,
            ProvenanceService.BUILDER_REPOSITORY + "/.github/workflows/build.yml@refs/heads/dev", "refs/heads/dev", null, true))
        assertEquals(reason("identityNotThisBuild"), wrongBranch.failureReason)
        // out of org through the same gate keeps the out-of-org words
        val elsewhere = ProvenanceService.signedByItsBuilder(ImageProvenance.Verified("https://github.com/someone/x",
            "https://github.com/someone/x/.github/workflows/build.yml@refs/heads/master", "refs/heads/master", null, true))
        assertEquals(reason("identityNotTrusted"), elsewhere.failureReason)
        val manifest = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(launcher, wrongBranch)), emptyList())
        assertFalse("a wrong-build signer is negative evidence", manifest.isInconclusive)
        assertFalse("a wrong-build signer is negative evidence", manifest.isUnpublishedOnly)
    }

    /** The index bytes [transform]ed; for rule b re-hashed so only the listing decides. */
    private fun transformed(transform: String): ByteArray {
        if (transform == "none") return indexBytes
        if (transform == "append_space") return indexBytes + " ".toByteArray()
        val o = Json.parseToJsonElement(indexBytes.decodeToString()).jsonObject
        val manifests = o.getValue("manifests").jsonArray.map { m ->
            val e = m.jsonObject
            if (e["digest"]?.jsonPrimitive?.content != "sha256:$child") return@map e
            when (transform) {
                "child_platform_arm64" -> JsonObject(e + ("platform" to JsonObject(e.getValue("platform").jsonObject + ("architecture" to JsonPrimitive("arm64")))))
                "child_as_attestation_manifest" -> JsonObject(e + ("annotations" to JsonObject(((e["annotations"] as? JsonObject) ?: JsonObject(emptyMap())) +
                    ("vnd.docker.reference.type" to JsonPrimitive("attestation-manifest")))))
                "child_digest_last_hex_to_0" -> JsonObject(e + ("digest" to JsonPrimitive("sha256:" + child.dropLast(1) + "0")))
                else -> error("unknown transform $transform")
            }
        }
        return JsonObject(o + ("manifests" to JsonArray(manifests))).toString().toByteArray()
    }

    /** Rules b and c through the real check: the child is the index's linux/amd64 image, and the bytes are the digest's. */
    @Test fun rulesBandC_theListingAndTheBytes() {
        for (c in cases("rule_b_child_linux_amd64")) {
            val bytes = transformed(c.s("transform"))
            val got = ImageIndexResolution.check(bytes, hex(bytes), child)
            assertEquals(c.s("name"), c.s("expect"), if (got is BindingCheck.Verified) "verified" else "unpublished")
        }
        for (c in cases("rule_c_bytes_hash")) {
            val got = ImageIndexResolution.check(transformed(c.s("transform")), index, child)
            assertEquals(c.s("name"), c.s("expect"), if (got is BindingCheck.Verified) "verified" else "unpublished")
        }
    }

    /** A fake GitHub (404 for the child, [attestation] for the index) and Docker Hub ([tags], each tag's HEAD → [digestOf]). */
    private class Hub(val tags: List<String>?, val digestOf: (String) -> String?, val bodies: Map<String, ByteArray>, val attestation: ByteArray,
                      val index: String, val tokenStatus: Int = 200, val attestationStatus: Int = 200) : ProvenanceHttp {
        val asked = ArrayList<String>()
        override suspend fun get(url: String, headers: Map<String, String>): ProvenanceResponse {
            asked += "GET $url"
            return when {
                url.startsWith("https://api.github.com/") && url.endsWith("sha256:$index") && "nearai/compose-manager/" in url ->
                    ProvenanceResponse(attestationStatus, mapOf("x-ratelimit-remaining" to "40"), if (attestationStatus == 200) attestation else ByteArray(0))
                url.startsWith("https://api.github.com/") -> ProvenanceResponse(404, mapOf("x-ratelimit-remaining" to "40"), "{}".toByteArray())
                url.startsWith("https://auth.docker.io/token") -> ProvenanceResponse(tokenStatus, emptyMap(), "{\"token\":\"anon\"}".toByteArray())
                url.endsWith("/tags/list") -> tags?.let { t -> ProvenanceResponse(200, emptyMap(), ("{\"tags\":[" + t.joinToString(",") { "\"$it\"" } + "]}").toByteArray()) }
                    ?: ProvenanceResponse(503, emptyMap(), ByteArray(0))
                else -> bodies[url.substringAfterLast("sha256:")]?.let { ProvenanceResponse(200, emptyMap(), it) } ?: ProvenanceResponse(404, emptyMap(), ByteArray(0))
            }
        }
        override suspend fun head(url: String, headers: Map<String, String>): ProvenanceResponse {
            asked += "HEAD $url"
            // no content-type: the bytes' hash and media type decide, not the HEAD (iOS)
            val d = digestOf(url.substringAfterLast('/')) ?: return ProvenanceResponse(404, emptyMap(), ByteArray(0))
            return ProvenanceResponse(200, mapOf("docker-content-digest" to "sha256:$d"), ByteArray(0))
        }
    }
    private fun service(hub: Hub) = ProvenanceService(http = hub, cache = InMemoryProvenanceCacheStore(), upgradeAttempted = HashSet(), recentMisses = HashMap(), sleepMs = {})
    private val launcher get() = ImageRef(image = "nearaidev/compose-manager-launcher", digest = child)

    /** End to end: prod names the index, its bytes list the child, the builder signed the index — the child is verified, with its commit. */
    @Test fun theLauncherVerifiesThroughItsIndexSignedByItsBuilder() = runTest {
        val hub = Hub(tags = null, digestOf = { if (it == "prod") index else null }, bodies = mapOf(index to indexBytes), attestation = attestation, index = index)
        val got = service(hub).verify(launcher)
        assertTrue("$got", got is ImageProvenance.Verified)
        got as ImageProvenance.Verified
        assertEquals(rules.getValue("rule_a_signer").jsonObject.s("repository"), got.repositoryURL)
        assertEquals(rules.getValue("rule_a_signer").jsonObject.s("source_commit_of_attestation_file"), got.sourceCommit)
        assertTrue("prod resolved it: no tag list is read", hub.asked.none { it.endsWith("/tags/list") })
    }

    /** Search order (iOS LauncherIndex.resolve): prod, then latest, then the newest tags of one list, capped at 200. */
    @Test fun prodThenLatestThenTheNewestTwoHundred() = runTest {
        val tags = (1..250).map { "staging-2026%04d".format(it) } + listOf("prod", "latest")
        val hub = Hub(tags = tags, digestOf = { if (it == "staging-20260051") index else "cd".repeat(32) }, bodies = mapOf(index to indexBytes),
            attestation = attestation, index = index)
        val got = service(hub).verify(launcher)
        val heads = hub.asked.filter { it.startsWith("HEAD") }.map { it.substringAfterLast('/') }
        assertEquals(listOf("prod", "latest"), heads.take(2))
        assertEquals("the 200th newest is the last one tried", "staging-20260051", heads.last())
        assertEquals(202, heads.size)
        assertTrue("$got", got is ImageProvenance.Verified)
        // one past the cap is not tried: a miss, remembered as no index
        // the other tags name an index that answers and doesn't list the child: a clean miss, not trouble
        val beyond = Hub(tags = tags, digestOf = { if (it == "staging-20260050") index else "cd".repeat(32) },
            bodies = mapOf(index to indexBytes, "cd".repeat(32) to "{\"mediaType\":\"application/vnd.oci.image.index.v1+json\",\"manifests\":[]}".toByteArray()),
            attestation = attestation, index = index)
        val miss = service(beyond).verify(launcher)
        assertTrue("$miss", (miss as ImageProvenance.Unverified).reason is ImageProvenance.Reason.FetchFailed)
    }

    /** Rule d: Docker Hub not answering is GitHub not answering (T2b) — never unpublished, and no miss is remembered. */
    @Test fun ruleD_aRegistryThatDoesNotAnswerIsInconclusive() = runTest {
        for (c in cases("rule_d_registry_no_answer")) {
            val hub = Hub(tags = null, digestOf = { null }, bodies = emptyMap(), attestation = attestation, index = index, tokenStatus = 503)
            val service = service(hub)
            val got = service.verify(launcher)
            assertTrue("${c.s("name")}: $got", (got as ImageProvenance.Unverified).reason is ImageProvenance.Reason.FetchTransient)
            assertFalse("a registry outage was remembered as a miss", service.recentlyMissed(child))
            // iOS provenance-dockerhub-unreachable (cf8002d5): the outage is worded as Docker Hub's, and stays inconclusive (T2b)
            assertEquals("fetchTransient_dockerHub", c.s("reason"))
            assertEquals("couldn\u2019t reach Docker Hub (rate-limited or offline)", reason("fetchTransient_dockerHub"))
            assertEquals(c.s("name"), reason("fetchTransient_dockerHub"), got.failureReason)
            val manifest = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(launcher, got)), emptyList())
            assertTrue("still no evidence either way", manifest.isInconclusive && !manifest.isUnpublishedOnly)
        }
    }

    /** GitHub not answering for the index's own attestation keeps GitHub's words (iOS 83fbc0a3). */
    @Test fun gitHubDownForTheIndexAttestationStillSaysGitHub() = runTest {
        assertEquals("couldn\u2019t reach GitHub (rate-limited or offline)", reason("fetchTransient_gitHub"))
        val hub = Hub(tags = null, digestOf = { if (it == "prod") index else null }, bodies = mapOf(index to indexBytes), attestation = attestation,
            index = index, attestationStatus = 503)
        val got = service(hub).verify(launcher)
        assertTrue("$got", (got as ImageProvenance.Unverified).reason is ImageProvenance.Reason.FetchTransient)
        assertEquals(reason("fetchTransient_gitHub"), got.failureReason)
    }
}
