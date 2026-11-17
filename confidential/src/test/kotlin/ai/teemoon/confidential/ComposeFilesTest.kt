package ai.teemoon.confidential

// (c) over audit's live glm-5-3-flash report, under audit's canonical rule A (2026-11-06; iOS 20e97066/28c43414): the
// walk (RunningWalkTest) gives expected.json's 4 (commit, file) pairs, newest attributing compose_up first; each is
// fetched from near.ai's compose-files repo and must hash to the log's file_sha256. Audit's mutations each change the
// verdict they name: a flipped hash, a repointed commit, a retired service, a past-cap row.

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeFilesTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/$name")!!.readText()).jsonObject
    private val report = resource("glm-5-3-flash-gpu04-5f7ebd85-2026-10-30T2241Z.json")
    private val expected = resource("expected.json")
    private val want = expected.getValue("running_pairs").jsonObject
    private val wantPairs = want.getValue("pairs").jsonArray.map { it.jsonObject }
    private val revisions = resource("up-revision-services.json").getValue("services").jsonObject
        .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
    /** Every up revision's names, then the whole-file bodies as vendored, hash-checked: the walk's service names. */
    private val vendored: (DeployLog.Source) -> List<String>? = { src ->
        revisions["${src.commit}/${src.file}"] ?: javaClass.getResource("/fixtures/nearai-trust/compose-files/${src.commit}/${src.file}")?.readBytes()
            ?.takeIf { b -> src.fileSha256.isEmpty() || java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } == src.fileSha256 }
            ?.let { DeployLog.serviceNames(String(it, Charsets.UTF_8)) }
    }
    private val services = DeployLog.runningServices(report.getValue("compose_manager_attestation").jsonObject.getValue("actions").jsonArray, vendored)
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    /** Every pair fetched and hashing to what audit verified on 2026-11-04. */
    private val honest = wantPairs.associate { (it.s("commit") to it.s("file")) to (ComposeFileFetch.Digest(it.s("file_sha256")) as ComposeFileFetch) }

    @Test fun theRunningTableCollapsesToAuditsPairsNewestFirst() {
        val pairs = ComposeFiles.pairs(services, honest)
        assertEquals(want.getValue("distinct_pairs").jsonPrimitive.content.toInt(), pairs.size)
        assertEquals(4, pairs.size)
        assertEquals(wantPairs.map { it.s("commit") to it.s("file") }, pairs.map { it.key })
        for ((p, w) in pairs.zip(wantPairs)) {
            assertEquals(w.s("file_sha256"), p.fileSHA256)
            assertEquals(w.getValue("services").jsonArray.map { it.jsonPrimitive.content }.toSet(), p.services.toSet())
            assertEquals(w.s("raw_url"), ComposeFiles.url(p.commit, p.file))
        }
        assertTrue(pairs.all { it.check == ComposeFileCheck.Matches })
    }

    @Test fun aFlippedHashIsAHardBreak() {
        val (commit, file) = wantPairs[0].let { it.s("commit") to it.s("file") }
        val bent = services.map { if (it.commit == commit && it.file == file) it.copy(fileSHA256 = it.fileSHA256!!.let { h -> (if (h[0] == '0') "1" else "0") + h.drop(1) }) else it }
        val pairs = ComposeFiles.pairs(bent, honest)
        assertTrue(pairs.first { it.key == commit to file }.check is ComposeFileCheck.Mismatch)
        val scope = scopeWith(pairs)
        assertEquals(NearTrustScope.Severity.HARD, scope.severity)
        assertEquals(NearTrustScope.RECIPE_UNVERIFIED, scope.hardBreakReason)
    }

    @Test fun aRepointedCommitIsFetchedThereAndA404IsUnpublished() {
        val row = services.first { it.service == "rotate-token" }
        val moved = services.map { if (it === row) it.copy(commit = "0".repeat(40)) else it }
        val fetched = honest + (("0".repeat(40) to row.file!!) to ComposeFileFetch.NotFound)
        val pairs = ComposeFiles.pairs(moved, fetched)
        assertEquals(ComposeFileCheck.Unpublished, pairs.first { it.commit == "0".repeat(40) }.check)
        assertEquals(NearTrustScope.Severity.SOFT, scopeWith(pairs).severity)
        // unfetched yet, and a network failure, are neither a match nor a break
        assertEquals(ComposeFileCheck.Checking, ComposeFiles.pairs(moved, honest).first { it.commit == "0".repeat(40) }.check)
        val failed = ComposeFiles.pairs(moved, honest + (("0".repeat(40) to row.file) to ComposeFileFetch.Failed))
        assertEquals(ComposeFileCheck.Inconclusive, failed.first { it.commit == "0".repeat(40) }.check)
        assertEquals(NearTrustScope.Severity.NONE, scopeWith(failed).severity)
    }

    /** Mutation #5: a newer whole-file up of a file whose revision no longer names a service retires that service's pair. */
    @Test fun aWholeFileUpThatDropsAServiceRetiresItsPair() {
        val actions = report.getValue("compose_manager_attestation").jsonObject.getValue("actions").jsonArray
        val canary = wantPairs.first { it.s("file").endsWith("Q8Canary.yaml") }
        val retired = kotlinx.serialization.json.JsonArray(actions + kotlinx.serialization.json.JsonObject(mapOf(
            "action" to kotlinx.serialization.json.JsonPrimitive("compose_up"), "file" to kotlinx.serialization.json.JsonPrimitive(canary.s("file")),
            "commit" to kotlinx.serialization.json.JsonPrimitive("f".repeat(40)), "file_sha256" to kotlinx.serialization.json.JsonPrimitive("e".repeat(64)),
            "timestamp" to kotlinx.serialization.json.JsonPrimitive("2026-10-30T00:00:00Z"))))
        val table = DeployLog.runningServices(retired) { if (it.commit == "f".repeat(40)) listOf("model-sg-glm53-w4afp8-tp2-r9") else vendored(it) }
        val pairs = ComposeFiles.pairs(table, emptyMap())
        assertTrue("the canary's older pair is gone", pairs.none { it.commit == canary.s("commit") && it.file == canary.s("file") })
        // r4 is no longer the canary's; an older entry of another, still-live file may name it (no cross-file eviction)
        assertTrue(table.none { it.service == "model-sg-glm53-w4afp8-tp2-r4" && it.file == canary.s("file") })
    }

    @Test fun pastTheCapNothingIsFetchedAndNothingBreaks() {
        val extra = (1..ComposeFiles.CAP).map { i -> RunningService("svc$i", "prod/x$i.yaml", "a".repeat(64), "c".repeat(39) + i % 10, null, "2000-01-01T00:00:0${i % 10}Z") }
        val all = services + extra
        val wanted = ComposeFiles.toFetch(all)
        assertEquals(ComposeFiles.CAP, wanted.size)
        assertEquals("the live pairs are the newest, so they are fetched", wantPairs.map { it.s("commit") to it.s("file") }, wanted.take(wantPairs.size))
        // a past-cap row whose file would not match: not fetched, so no verdict and no break
        val pairs = ComposeFiles.pairs(all, honest + extra.associate { (it.commit!! to it.file!!) to ComposeFileFetch.Digest("b".repeat(64)) })
        val past = pairs.drop(ComposeFiles.CAP)
        assertTrue(past.isNotEmpty() && past.all { it.check == ComposeFileCheck.NotFetched })
        assertTrue(pairs.take(ComposeFiles.CAP).none { it.check == ComposeFileCheck.NotFetched })
    }

    /**
     * Audit's hash-differs case on a real commit: the cd948600 TP2x4 rows repointed to 1121ee56, where that same file
     * exists with another hash. The fetch succeeds (the fake serves what 1121ee56 has: its body hashes to the 1121ee56
     * pair's file_sha256), and the rows still claim cd948600's hash, so it is a mismatch, and hard.
     */
    @Test fun aRowRepointedToARealCommitWhereTheFileDiffersIsAHardMismatch() {
        val from = "cd94860052fede8d56d8f017c55b241362c75d29"; val to = "1121ee56851dcec7dc89e60099d7be28fc51a73e"
        val file = "prod/GLM-5.3-Flash-SGL-TP2x4-W4AFP8.yaml"
        // 1121ee56's pinned hash, from the fixture before rule A (it no longer reports that pair; 2026-11-04 audit-verified)
        val atTo = "b0077025ac5f099ca6b30dd21a2605e3ba600b01be38e1605b5ec93f69a125da"
        val atFrom = wantPairs.first { it.s("commit") == from && it.s("file") == file }.s("file_sha256")
        assertTrue("the file differs between the two commits", atTo != atFrom)
        val moved = services.map { if (it.commit == from && it.file == file) it.copy(commit = to) else it }
        val pairs = ComposeFiles.pairs(moved, honest + ((to to file) to ComposeFileFetch.Digest(atTo)))
        val p = pairs.first { it.key == to to file }
        assertEquals(ComposeFileCheck.Mismatch(atTo), p.check)
        assertEquals(NearTrustScope.Severity.HARD, scopeWith(pairs).severity)
    }

    @Test fun aDigestIsKeptOnDiskAndA404OrAFailureIsAskedAgain() = runTest {
        var calls = 0
        var status = 200
        val store = InMemoryProvenanceCacheStore()
        val svc = ProvenanceService(http = { _, _ -> calls++; ProvenanceResponse(status, emptyMap(), "x".toByteArray()) }, cache = store)
        val c = "f".repeat(39) + "1"
        val first = svc.fetchComposeFile(c, "prod/a.yaml")
        assertTrue(first is ComposeFileFetch.Digest)
        svc.fetchComposeFile(c, "prod/a.yaml")
        assertEquals("a commit's file never changes: fetched once", 1, calls)
        // a cold launch: a new service over the same store asks nothing
        assertEquals(first, ProvenanceService(http = { _, _ -> error("asked again") }, cache = store).fetchComposeFile(c, "prod/a.yaml"))
        status = 404
        assertEquals(ComposeFileFetch.NotFound, svc.fetchComposeFile(c, "prod/b.yaml")); svc.fetchComposeFile(c, "prod/b.yaml")
        assertEquals(3, calls)
        assertEquals("only a success is kept", listOf("${ProvenanceService.COMPOSE_FILE_KEY}$c/prod/a.yaml"), store.load().keys.toList())
        status = 503
        assertEquals(ComposeFileFetch.Failed, svc.fetchComposeFile(c, "prod/c.yaml"))
        val thrown = ProvenanceService(http = { _, _ -> throw java.io.IOException("offline") })
        assertEquals(ComposeFileFetch.Failed, thrown.fetchComposeFile(c, "prod/d.yaml"))
    }

    /** The chat path's bodies (iOS b3d90fac): the same URL, hash-checked against the pin, kept in memory, asked once. */
    @Test fun aBodyIsHashCheckedAndAskedOnce() = runTest {
        var calls = 0
        val svc = ProvenanceService(http = { _, _ -> calls++; ProvenanceResponse(200, emptyMap(), "services:\n  a:\n".toByteArray()) })
        val c = "f".repeat(39) + "2"
        val pin = java.security.MessageDigest.getInstance("SHA-256").digest("services:\n  a:\n".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("services:\n  a:\n", svc.fetchComposeBody(DeployLog.Source(c, "prod/a.yaml", pin)))
        svc.fetchComposeBody(DeployLog.Source(c, "prod/a.yaml", pin))
        assertEquals("kept for the launch", 1, calls)
        assertEquals("not the pinned file: no body", null, svc.fetchComposeBody(DeployLog.Source(c, "prod/b.yaml", "0".repeat(64))))
    }

    private fun scopeWith(pairs: List<ComposeFilePair>) =
        NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, services, LauncherIndex.NotChecked, pairs)
}
