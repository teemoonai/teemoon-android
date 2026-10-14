// ports: teemoonTests/ImageProvenanceTests.swift @ v1.0.4 (aCleanMissIsNotAskedAgainWithinTheHour) and @ 1102bc8 (the fetch suite and the four-answer 404) and @ v1.0.1 — the "ProvenanceService parsing" (the fetch/cache
//        tests; the parser tests are ProvenanceParsingTest), "legacy cache upgrade" and "404 classification" suites.
//        The stub GitHub is the ProvenanceHttp seam; the fixture body is the kit's positive attestation.
package ai.teemoon.confidential

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.io.File
import java.net.URI

class ProvenanceServiceTest {
    private val fixture get() = SigstoreKit.positives.first()
    private val fixtureDigest get() = fixture.expectedDigest

    /** Serves a scripted sequence of (status, headers, body); the last step repeats. */
    private class Scripted(vararg steps: Triple<Int, Map<String, String>, ByteArray>) : ProvenanceHttp {
        private val script = steps.toList(); var requestCount = 0
        override suspend fun get(url: String, headers: Map<String, String>): ProvenanceResponse {
            val step = script.getOrNull(requestCount) ?: script.last(); requestCount++
            return ProvenanceResponse(step.first, step.second, step.third)
        }
    }
    private fun step(status: Int, body: ByteArray = "{}".toByteArray(), vararg headers: Pair<String, String>) = Triple(status, headers.toMap(), body)
    private fun service(http: ProvenanceHttp, cache: ProvenanceCacheStore = InMemoryProvenanceCacheStore()) =
        ProvenanceService(http = http, cache = cache, upgradeAttempted = HashSet(), sleepMs = {})
    private fun seedLegacy(cache: ProvenanceCacheStore, digest: String) =
        cache.save(mapOf(digest to listOf("https://github.com/nearai/cloud-api", "https://github.com/nearai/cloud-api/.github/workflows/build.yml@refs/heads/main", "true")))

    @Test fun verifyManifest_thirdPartyOnly_failsClosed() = runTest {
        // A manifest with no near.ai images is nothing this check can vouch for.
        val r = service(Scripted(step(500))).verifyManifest("image: alpine@sha256:${"1".repeat(64)}")
        val inc = r as ManifestProvenance.Incomplete
        assertTrue(inc.verified.isEmpty() && inc.failures.isEmpty()); assertEquals(1, inc.thirdParty.size)
    }

    // MARK: - Verified-digest cache

    @Test fun legacyThreeFieldCacheEntry_decodesWithNullSourceInfo() {
        val cache = InMemoryProvenanceCacheStore(); val digest = "b".repeat(64)
        cache.save(mapOf(digest to listOf("https://github.com/nearai/cloud-api", "identity", "true")))
        val v = service(Scripted(step(500)), cache).cachedVerification(digest) as ImageProvenance.Verified
        assertEquals("https://github.com/nearai/cloud-api", v.repositoryURL); assertNull(v.sourceRef); assertNull(v.sourceCommit); assertTrue(v.transparencyLogChecked)
    }

    @Test fun verifiedDigestCache_roundTrips() {
        val cache = InMemoryProvenanceCacheStore(); val s = service(Scripted(step(500)), cache); val digest = "a".repeat(64)
        assertNull(s.cachedVerification(digest))
        val outcome = ImageProvenance.Verified("https://github.com/nearai/cloud-api", "https://github.com/nearai/cloud-api/.github/workflows/build.yml@refs/heads/main", "refs/heads/main", "84367f0253fa94aa6816d64210e5812215ee2622", true)
        s.cacheVerification(digest, outcome)
        assertEquals(outcome, s.cachedVerification(digest))
        // Unverified outcomes are never cached.
        s.cacheVerification("ff", ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("x")))
        assertNull(s.cachedVerification("ff"))
    }

    @Test fun fileCacheStore_persistsAndTreatsGarbageAsEmpty() {
        val f = File.createTempFile("prov", ".json"); f.delete()
        val store = FileProvenanceCacheStore(f)
        assertTrue(store.load().isEmpty())
        store.save(mapOf("d" to listOf("r", "i", "", "", "true"))); assertEquals(listOf("r", "i", "", "", "true"), FileProvenanceCacheStore(f).load()["d"])
        f.writeText("not json"); assertTrue(FileProvenanceCacheStore(f).load().isEmpty()); f.delete()
    }

    // MARK: - Legacy cache upgrade

    /** A legacy 3-field entry upgrades via one re-fetch, and the upgraded entry persists in the 5-field format. */
    @Test fun legacyEntry_upgradesToSourceInfoOnRefetch() = runTest {
        val cache = InMemoryProvenanceCacheStore(); seedLegacy(cache, fixtureDigest)
        val r = service(Scripted(step(200, fixture.attestationJson)), cache).verify(ImageRef(image = "nearaidev/cloud-api", digest = fixtureDigest)) as ImageProvenance.Verified
        assertEquals("refs/heads/main", r.sourceRef); assertEquals("84367f0253fa94aa6816d64210e5812215ee2622", r.sourceCommit)
        assertEquals(5, cache.load()[fixtureDigest]!!.size)
    }

    /** Rate limits (403) are transient — inconclusive, never evidence of compromise. A 404 (no attestation exists) stays a definitive, fail-closed failure. */
    @Test fun rateLimit_isInconclusive_missingAttestation_isNot() = runTest {
        val manifest = "image: nearaidev/cloud-api@sha256:${"d".repeat(64)}"
        val rateLimited = service(Scripted(step(403))).verifyManifest(manifest)
        assertTrue(rateLimited.isInconclusive); assertFalse(rateLimited.isFullyVerified)
        val missing = service(Scripted(step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "5"))).verifyManifest(manifest)
        assertFalse(missing.isInconclusive); assertTrue(missing.isUnpublishedOnly)
    }

    /** The upgrade re-fetch happens once per launch — repeated verifications while rate-limited must not burn quota again. */
    @Test fun legacyUpgrade_attemptedOncePerLaunch() = runTest {
        val cache = InMemoryProvenanceCacheStore(); val digest = "f".repeat(64); seedLegacy(cache, digest)
        val http = Scripted(step(403)); val s = service(http, cache)
        s.verify(ImageRef(image = "nearaidev/cloud-api", digest = digest)); val after1 = http.requestCount
        val second = s.verify(ImageRef(image = "nearaidev/cloud-api", digest = digest))
        assertEquals(after1, http.requestCount)   // no second fetch
        assertTrue(second is ImageProvenance.Verified)
    }

    @Test fun legacyEntry_failedRefetch_staysVerified() = runTest {
        val cache = InMemoryProvenanceCacheStore(); val digest = "c".repeat(64); seedLegacy(cache, digest)
        val r = service(Scripted(step(403)), cache).verify(ImageRef(image = "nearaidev/cloud-api", digest = digest)) as ImageProvenance.Verified
        assertEquals("https://github.com/nearai/cloud-api", r.repositoryURL); assertNull(r.sourceRef); assertNull(r.sourceCommit)
    }

    @Test fun modelLayerManifest_triStateDistinguishesMatchMismatchAndFetchFailure() = runTest {
        val yaml = "services:\n  proxy:\n    image: nearaidev/vllm-proxy-rs@sha256:${"a".repeat(64)}\n"
        val sha = MessageDigest.getInstance("SHA-256").digest(yaml.toByteArray()).toHex()
        val ok = service(Scripted(step(200, yaml.toByteArray()))).fetchModelLayerManifest("prod/x.yaml", "c545c95", sha)
        assertEquals(ModelLayerFetch.Verified(yaml), ok)
        // hash mismatch → HashMismatch (adversarial), NEVER unverified content; distinct from a fetch failure so the UI can fail LOUD on tamper.
        val mismatch = service(Scripted(step(200, yaml.toByteArray()))).fetchModelLayerManifest("prod/x.yaml", "c545c95", "0".repeat(64))
        assertTrue(mismatch.toString(), mismatch is ModelLayerFetch.HashMismatch)
        // non-200 (throttled/unreachable) → FetchFailed — transient, NOT tamper.
        assertEquals(ModelLayerFetch.FetchFailed, service(Scripted(step(403))).fetchModelLayerManifest("prod/x.yaml", "c545c95", sha))
    }

    // MARK: - 404 classification under rate pressure (live regression 2026-07-19)

    @Test fun rateLimited404IsTransientNotDefinitive() = runTest {
        val http = Scripted(step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "0"))
        val r = service(http).verify(ImageRef(image = "nearaidev/compose-manager-launcher", digest = "1".repeat(64)))
        val why = ((r as ImageProvenance.Unverified).reason as ImageProvenance.Reason.FetchTransient).detail
        assertTrue(why.contains("rate limit")); assertEquals(1, http.requestCount)   // no pointless retry against a dry quota
    }

    /** Two candidates (the guessed repo, then the compose repo), one retry each: a clean 404 is believed only after four answers. */
    @Test fun clean404RetriesOnceThenFailsClosed() = runTest {
        val http = Scripted(step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "37"), step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "36"),
            step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "35"), step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "34"))
        val r = service(http).verify(ImageRef(image = "nearaidev/compose-manager-launcher", digest = "2".repeat(64)))
        val why = ((r as ImageProvenance.Unverified).reason as ImageProvenance.Reason.FetchFailed).detail
        assertTrue(why.contains("404")); assertEquals(4, http.requestCount)   // both repos, each retried once
    }

    // MARK: - fetch: the guessed repo is not the only place near.ai publishes

    @Test fun repoCandidatesStartWithTheGuessAndEndWithTheComposeRepo() {
        assertEquals(listOf("nearai/sglang", "nearai/cvm-compose-files"), ProvenanceParsing.githubRepos("docker.io/nearaidev/sglang"))
        // The compose repo itself is asked once, not twice.
        assertEquals(listOf("nearai/cvm-compose-files"), ProvenanceParsing.githubRepos("nearaidev/cvm-compose-files"))
    }

    /**
     * GLM-5.3 flash: `nearai/sglang` does not exist, and the image's SLSA attestations live under the compose repo's
     * workflow. The guessed repo's clean 404 used to be the final answer — "image unpublished" for an image that is
     * published. On a Pixel 10 Pro, 2026-09-22, that was every glm-5.3-flash session.
     */
    @Test fun aCleanMissAtTheGuessedRepoFallsThroughToTheComposeRepo() = runTest {
        val asked = ArrayList<String>()
        val http = ProvenanceHttp { url, _ ->
            asked.add(URI(url).path)
            if (url.contains("/repos/nearai/cvm-compose-files/")) ProvenanceResponse(200, mapOf("x-ratelimit-remaining" to "40"), fixture.attestationJson)
            else ProvenanceResponse(404, mapOf("x-ratelimit-remaining" to "40"), "{\"message\":\"Not Found\"}".toByteArray())
        }
        val r = service(http).verify(ImageRef(image = "docker.io/nearaidev/sglang", digest = fixtureDigest))
        assertTrue("expected Verified via the fallback repo, got $r", r is ImageProvenance.Verified)
        assertEquals("https://github.com/nearai/cloud-api", (r as ImageProvenance.Verified).repositoryURL)   // whatever the bundle says
        assertEquals("the guess retried once, then the compose repo answered", listOf(
            "/repos/nearai/sglang/attestations/sha256:$fixtureDigest",
            "/repos/nearai/sglang/attestations/sha256:$fixtureDigest",
            "/repos/nearai/cvm-compose-files/attestations/sha256:$fixtureDigest"), asked)
    }

    /** A clean 404 from every candidate repo is remembered for an hour: an unpublished image no longer spends the anonymous GitHub quota on every refresh. */
    @Test fun aCleanMissIsNotAskedAgainWithinTheHour() = runTest {
        val http = Scripted(step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "37"), step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "36"),
            step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "35"), step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "34"))
        var now = 1_000_000L
        val service = ProvenanceService(http = http, upgradeAttempted = HashSet(), recentMisses = HashMap(), nowMs = { now }, sleepMs = {})
        val ref = ImageRef(image = "nearaidev/compose-manager-launcher", digest = "9".repeat(64))
        val first = service.verify(ref)
        assertTrue("expected a definitive miss, got $first", (first as? ImageProvenance.Unverified)?.reason is ImageProvenance.Reason.FetchFailed)
        assertEquals(4, http.requestCount)

        val second = service.verify(ref)
        assertTrue("expected the remembered miss, got $second", (second as? ImageProvenance.Unverified)?.reason is ImageProvenance.Reason.FetchFailed)
        assertEquals("no request for a digest missed within the hour", 4, http.requestCount)

        // Android-only: after the hour it is asked again.
        now += ProvenanceService.MISS_TTL_MS
        service.verify(ref)
        assertTrue(http.requestCount > 4)
    }

    @Test fun transient404ThenSuccessIsRescuedByTheRetry() = runTest {
        val http = Scripted(step(404, "{}".toByteArray(), "x-ratelimit-remaining" to "37"), step(200, "{}".toByteArray(), "x-ratelimit-remaining" to "36"))
        val r = service(http).verify(ImageRef(image = "nearaidev/compose-manager-launcher", digest = "3".repeat(64)))
        // The `{}` body won't pass Sigstore verification, but the 404 must NOT be believed: the outcome is a verification verdict, not a fetch-404 failure.
        val reason = (r as ImageProvenance.Unverified).reason
        if (reason is ImageProvenance.Reason.FetchFailed) assertFalse(reason.detail, reason.detail.contains("404"))
        assertEquals(2, http.requestCount)
    }

    /** Android-only: a verified fetch lands in the cache, and the whole manifest verifies against the kit's real attestation. */
    @Test fun verifyManifests_realAttestation_allVerifiedAndCached() = runTest {
        val cache = InMemoryProvenanceCacheStore()
        val r = service(Scripted(step(200, fixture.attestationJson)), cache).verifyManifests(listOf("gateway" to "image: nearaidev/cloud-api@sha256:$fixtureDigest\nimage: alpine@sha256:${"1".repeat(64)}"))
        val all = r as ManifestProvenance.AllVerified
        assertEquals("https://github.com/nearai/cloud-api", all.verified.single().sourceRepo); assertEquals(listOf("gateway"), all.verified.single().hosts); assertEquals(1, all.thirdParty.size)
        assertTrue(cache.load().containsKey(fixtureDigest))
    }
}
