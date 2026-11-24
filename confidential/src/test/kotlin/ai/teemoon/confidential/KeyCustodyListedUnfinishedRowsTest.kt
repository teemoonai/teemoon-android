// ports: teemoonTests/KeyCustodyListedStatesTests.swift @ cb690537 (aRunningFileThatCouldntBeComparedIsUnfinished), on real sessions
//
// Audit LOW (1.1.1, on 9951e20d): KeyCustodyCopy.listed read CHECKED ("teemoon checked every enclave near.ai listed.") while a
// listed enclave's scope still had running compose rows teemoon never compared. Four causes, one named test each: the file's
// fetch failed (Inconclusive); the log's 200-fetch FetchBudget was spent (the session marks the file Failed, so Inconclusive);
// the pair was past the 10-pair cap (NotFetched); the file 404s at the pinned commit (Unpublished, "no published attestation";
// audit ruled it in too). The send gate is right here; only the sentence over-claimed. The fix iOS lands first: .listed reads
// UNFINISHED ("teemoon couldn't finish checking the enclaves near.ai listed.") when any listed scope has a running row that is
// inconclusive, not fetched or unpublished.
//
// The listed scopes come from a real record: the live list, each entry's compose-manager action log replaced by a small log
// and its quote's report data re-signed over it (as ConfidentialSessionTest's budget tests do), so the session binds each log
// and builds the scopes itself (ConfidentialSession.listedNearScopes). Only the fetch outcomes are set, through the session's
// own composeFileFetches, which is where the (c) fetch writes them.
package ai.teemoon.confidential

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.teemoon.providers.Provider
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCustodyListedUnfinishedRowsTest {
    private val ok = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

    private fun sha(tag: String) = java.security.MessageDigest.getInstance("SHA-256").digest(tag.toByteArray()).toHex()

    /** One compose_up naming its service, its own (commit, file) and a pinned hash; newer [i] is newer in the log. */
    private fun up(enclave: Int, i: Int) = buildJsonObject {
        put("action", JsonPrimitive("compose_up")); put("commit", JsonPrimitive("e${enclave}c$i"))
        put("file", JsonPrimitive("prod/e${enclave}f$i.yaml")); put("file_sha256", JsonPrimitive(sha("e$enclave/$i")))
        put("services", JsonArray(listOf(JsonPrimitive("svc$enclave-$i"))))
        put("timestamp", JsonPrimitive("2026-11-20T%05d".format(i)))
    }

    /** The live entry [i] with its manager's action log replaced by [actions], report data re-signed over them (ConfidentialSessionTest.entryWithLog). */
    private fun entryWithLog(c: LiveCandidates.Capture, i: Int, actions: List<JsonObject>): JsonObject {
        val entry = c.list[i]
        val cm = entry.getValue("compose_manager_attestation").jsonObject
        val arr = JsonArray(actions)
        val quoteHex = cm.getValue("quote").jsonPrimitive.content
        val quote = quoteHex.removePrefix("0x").hexToBytes()
        val reportDataAt = QuoteParser.HEADER_SIZE + 520
        java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.encode(arr).toByteArray(Charsets.UTF_8)).copyInto(quote, reportDataAt)
        val resigned = (if (quoteHex.startsWith("0x")) "0x" else "") + quote.toHex()
        val edited = JsonObject(cm + mapOf("actions" to arr, "quote" to JsonPrimitive(resigned)) - "actions_hash")
        return JsonObject(entry + ("compose_manager_attestation" to edited))
    }

    /**
     * A session whose listed enclaves pass every check (local, DCAP node and manager, GPU, binding, boot log), each with the log in
     * [logs], and [fetched] as what the (c) fetch found.
     */
    private fun session(logs: List<List<JsonObject>>, fetched: Map<Pair<String, String>, ComposeFileFetch>): ConfidentialSession {
        val c = LiveCandidates.load()
        val listed = ListedNearScopeTest.listed(logs.mapIndexed { i, log -> entryWithLog(c, i, log) }, c.nonce, c.key)
        val s = SendGateRuleTest().also { it.offline() }.baselineSession(AttestationRecord.preview.copy(modelCandidates = listed))
        s.dcapVerification = s.dcapVerification!!.copy(listed = List(logs.size) { ok }, listedManagers = List(logs.size) { ok })
        s.composeFileFetches = fetched
        assertListedClean(s, logs.size)
        return s
    }

    /**
     * Every listed check passed: bound, boot log replays, nothing hard, no evidence absent, GPU verified. An Unpublished row is
     * the one soft severity a scope may carry here (NearTrustScope.severity); the control asserts NONE itself.
     */
    private fun assertListedClean(s: ConfidentialSession, n: Int) {
        val scopes = s.listedNearScopes!!
        assertEquals(n, scopes.size)
        for ((i, sc) in scopes.withIndex()) {
            assertEquals("entry $i", DeployLogBinding.Bound, sc.deployLogBinding)
            assertEquals("entry $i", BootLogReplay.Matches, sc.bootLogReplay)
            assertTrue("entry $i is ${sc.severity}", sc.severity != NearTrustScope.Severity.HARD && !sc.listedEvidenceAbsent)
        }
        assertEquals(NrasVerification.Verified, s.gpuAttestation)
    }

    /** What the trust screens read: TrustPresentation's AttestationSummary.listedEnclaves over the session. */
    private fun listed(s: ConfidentialSession) = KeyCustodyCopy.listed(s.attestation, s.dcapVerification, s.gpuAttestation, s.listedNearScopes, s.tierGaveUp)

    private fun log(enclave: Int, pairs: Int) = (0 until pairs).map { up(enclave, it) }
    private fun matching(logs: List<List<JsonObject>>) =
        logs.flatten().associate { (it.str("commit") to it.str("file")) to ComposeFileFetch.Digest(it.str("file_sha256")) }
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    /** The control: every listed running row was fetched and matches the log, so "checked every enclave" holds. */
    @Test fun everyListedRunningRowComparedAndMatchingReadsChecked() {
        val logs = listOf(log(0, 2), log(1, 3))
        val s = session(logs, matching(logs))
        assertTrue(s.listedNearScopes!!.all { sc -> sc.severity == NearTrustScope.Severity.NONE && sc.composeFiles.isNotEmpty() && sc.composeFiles.all { it.check == ComposeFileCheck.Matches } })
        assertEquals(KeyCustodyCopy.Listed.CHECKED, listed(s))
    }

    /** (a) One listed running file's fetch failed (network, rate limit): never compared. */
    @Test fun aListedRunningRowWhoseFetchFailedReadsUnfinished() {
        val logs = listOf(log(0, 2), log(1, 3))
        val failed = logs[1][1].let { it.str("commit") to it.str("file") }
        val s = session(logs, matching(logs) + (failed to ComposeFileFetch.Failed))
        val scope = s.listedNearScopes!![1]
        assertEquals(ComposeFileCheck.Inconclusive, scope.composeFiles.single { it.key == failed }.check)
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, listed(s))
    }

    /** (b) One listed log names more than [ComposeFiles.CAP] running pairs: the one past the cap is never fetched. */
    @Test fun aListedRunningRowPastTheCapReadsUnfinished() {
        val logs = listOf(log(0, 2), log(1, ComposeFiles.CAP + 1))
        // newest up first, so the oldest (index 0) is the pair past the cap: the session never fetches it (ComposeFiles.toFetch)
        val pastCap = logs[1][0].let { it.str("commit") to it.str("file") }
        val fetched = matching(logs) - pastCap
        val s = session(logs, fetched)
        val files = s.listedNearScopes!![1].composeFiles
        assertEquals(ComposeFiles.CAP + 1, files.size)
        assertEquals(ComposeFileCheck.NotFetched, files.single { it.key == pastCap }.check)
        assertTrue(files.filter { it.key != pastCap }.all { it.check == ComposeFileCheck.Matches })
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, listed(s))
    }

    /**
     * Android only (pm/ios 2026-11-22): Android can publish a listed scope while one of its running files is still being
     * fetched (no entry yet, so the pair reads Checking); iOS awaits every fetch before it publishes a scope. Still being
     * compared is not "checked every enclave".
     */
    @Test fun aListedRunningRowStillBeingComparedReadsUnfinished() {
        val logs = listOf(log(0, 2), log(1, 3))
        val pending = logs[1][2].let { it.str("commit") to it.str("file") }
        val s = session(logs, matching(logs) - pending)
        val scope = s.listedNearScopes!![1]
        assertEquals(ComposeFileCheck.Checking, scope.composeFiles.single { it.key == pending }.check)
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, listed(s))
    }

    /**
     * (c) One listed running file 404s at the pinned commit: ProvenanceService.fetchComposeFile maps 404 to ComposeFileFetch.NotFound,
     * which ComposeFiles.pairs reads as Unpublished ("no published attestation"). Soft for the gate; never compared.
     */
    @Test fun aListedRunningRowThatIsUnpublishedReadsUnfinished() {
        val logs = listOf(log(0, 2), log(1, 3))
        val gone = logs[1][1].let { it.str("commit") to it.str("file") }
        val s = session(logs, matching(logs) + (gone to ComposeFileFetch.NotFound))
        val scope = s.listedNearScopes!![1]
        assertEquals(ComposeFileCheck.Unpublished, scope.composeFiles.single { it.key == gone }.check)
        assertTrue(scope.composeFiles.filter { it.key != gone }.all { it.check == ComposeFileCheck.Matches })
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, listed(s))
    }

    /**
     * (d) The log's FetchBudget is spent before its running pair: driven through the session's real (c) fetch, not a set map. Enclave 1's
     * log takes down [ComposeFiles.FETCH_BUDGET] other files (whole-file sources, fetched first, newest first), then its one running
     * up is past the budget: the session marks it Failed (ConfidentialSession.verifyComposeFiles' overBudget), so Inconclusive.
     * Enclave 0's log is served and matches.
     */
    @Test fun aListedRunningRowPastTheFetchBudgetReadsUnfinished() = runTest {
        fun body(path: String) = "# $path\nservices:\n  svc:\n    image: nearaidev/x@sha256:abc\n".toByteArray()
        fun path(commit: String, file: String) = ComposeFiles.url(commit, file).substringAfter("githubusercontent.com")
        fun servedUp(enclave: Int, i: Int) = up(enclave, i).let { a ->
            JsonObject(a + ("file_sha256" to JsonPrimitive(sha256(body(path(a.str("commit"), a.str("file")))))))
        }
        val down = { i: Int -> buildJsonObject {
            put("action", JsonPrimitive("compose_down")); put("commit", JsonPrimitive("d$i")); put("file", JsonPrimitive("prod/d$i.yaml"))
            put("timestamp", JsonPrimitive("2026-11-21T%05d".format(i)))
        } }
        val logs = listOf(listOf(servedUp(0, 0), servedUp(0, 1)), listOf(servedUp(1, 0)) + (0 until ComposeFiles.FETCH_BUDGET).map(down))
        val running = logs[1][0].let { it.str("commit") to it.str("file") }
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val saved = ProvenanceService.default
        try {
            ProvenanceService.default = ProvenanceService(http = { url, _ ->
                val p = java.net.URI(url).path; asked += p
                val served = logs.flatten().any { it.str("action") == "compose_up" && path(it.str("commit"), it.str("file")) == p }
                ProvenanceResponse(if (served) 200 else 404, emptyMap(), if (served) body(p) else ByteArray(0))
            }, cache = InMemoryProvenanceCacheStore(), upgradeAttempted = HashSet(), sleepMs = {}, io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            val c = LiveCandidates.load()
            val listedRecord = ListedNearScopeTest.listed(logs.mapIndexed { i, log -> entryWithLog(c, i, log) }, c.nonce, c.key)
            val verdict = RecordDcapVerification(model = ok, manager = ok, listed = List(logs.size) { ok }, listedManagers = List(logs.size) { ok })
            val record = AttestationRecord.preview.copy(modelCandidates = listedRecord)
            val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
            val s = ConfidentialSession(this, { p }, credentialFor = { "" },
                services = ConfidentialSession.Services(skipLiveAttestation = true, verifyDcap = { verdict }),
                sealedModels = SealedModels(InMemorySealedModelStore()))
            s.attestation = record
            s.gpuAttestation = NrasVerification.Verified
            s.verifyDcap(record)
            advanceUntilIdle()
            assertTrue("the running file was asked for: ${asked.size} fetches", path(running.first, running.second) !in asked)
            assertEquals(ComposeFileFetch.Failed, s.composeFileFetches[running])
            assertListedClean(s, logs.size)
            assertEquals(ComposeFileCheck.Inconclusive, s.listedNearScopes!![1].composeFiles.single { it.key == running }.check)
            assertTrue(s.listedNearScopes!![0].composeFiles.all { it.check == ComposeFileCheck.Matches })
            assertEquals(KeyCustodyCopy.Listed.UNFINISHED, listed(s))
        } finally {
            ProvenanceService.default = saved
        }
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).toHex()
}
