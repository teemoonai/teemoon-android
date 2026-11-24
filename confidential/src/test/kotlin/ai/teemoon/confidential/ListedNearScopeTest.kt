// ports: teemoonTests/ListedTrustScopeTests.swift @ 1fe69e3d (1.1.1 audit A4). iOS runs TrustScope.run per listed enclave in a task the session holds (listedTrustScopeTask, in verdictsPending); here the
//        scopes are computed from the record and the one DCAP pass (ModelCandidates.trustScopes, ConfidentialSession.listedNearScopes), and
//        that pass is already what the pending hold waits on, so listedScopesStillRunningHoldTheSend and
//        adoptingARecordWithAListStartsItsScopes have no Android counterpart as such (adoptingARecordWithAListGivesItsScopes is the twin).
//
// Audit 1.1.1 A4: every enclave near.ai lists holds the sealed key, so each gets the read enclave's trust scope (compose-manager
// action-log binding, boot-log replay, running pairs) on its own entry, its own quote and the list's nonce. A mismatch blocks like the
// read one's; a log teemoon couldn't check asks. Before A4 a listed enclave with no boot event log passed as clean (Rtmr3Replay's
// Inconclusive is not a mismatch).
package ai.teemoon.confidential

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ListedNearScopeTest {
    companion object {
        val ok = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

        fun scope(binding: DeployLogBinding, boot: BootLogReplay) = NearTrustScope(binding, boot, null, emptyList(), LauncherIndex.NotChecked)
        val clean get() = scope(DeployLogBinding.Bound, BootLogReplay.Matches)
        fun clean(n: Int) = List(n) { clean }

        fun listed(list: List<JsonObject>, nonce: String, key: ByteArray): ModelCandidates.Listed {
            val body = JsonObject(mapOf("model_attestations" to JsonArray(list))).toString().toByteArray()
            return ModelCandidates.enclaves(body, nonce, key)!!
        }

        /** Every DCAP verdict up to date: the listed node quotes and each entry's compose-manager quote. */
        fun allOk(n: Int) = RecordDcapVerification(model = ok, listed = List(n) { ok }, listedManagers = List(n) { ok })

        fun editManager(entry: JsonObject, edit: (JsonObject) -> JsonObject) =
            JsonObject(entry + ("compose_manager_attestation" to edit(entry.getValue("compose_manager_attestation").jsonObject)))

        /**
         * One listed enclave's outcome, made on a REAL entry of the live list (1.1.1 audit: the gate reads no listed-scopes override; the
         * session builds each scope itself from the record and its DCAP pass, ModelCandidates.trustScopes).
         */
        enum class Entry(val binding: (DeployLogBinding) -> Boolean, val boot: (BootLogReplay) -> Boolean) {
            CLEAN({ it == DeployLogBinding.Bound }, { it == BootLogReplay.Matches }),
            /** the manager's action log one action short of what its quote signs */
            BINDING_MISMATCH({ it == DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH) }, { it == BootLogReplay.Matches }),
            /**
             * one boot event (not the compose hash) dropped: the log no longer replays to the quote's RTMR3. On a real entry the list's own
             * local checks run the same replay, so this entry is ALSO Mismatch(BOOT_LOG) there: a listed boot mismatch is always caught twice.
             */
            BOOT_MISMATCH({ it == DeployLogBinding.Bound }, { it == BootLogReplay.Mismatch(BootLogReplay.What.RTMR3) }),
            /** Intel couldn't check the manager quote */
            BINDING_UNCHECKED({ it == DeployLogBinding.Unchecked }, { it == BootLogReplay.Matches }),
            /**
             * the boot event log names no compose hash: couldn't check. (Dropping the whole log also drops the key-provider event, and the
             * list's one-key-service check then reads KEY_PROVIDER_DIFFERS: that is evidence, not this outcome.)
             */
            BOOT_ABSENT({ it == DeployLogBinding.Bound }, { it == BootLogReplay.Absent }),
            /** no compose-manager quote */
            NO_MANAGER_QUOTE({ it == DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG) }, { it == BootLogReplay.Matches }),
        }

        /**
         * The live list with entry i edited to [entries][i], and the DCAP verdicts that go with it, over [baseDcap] (the record's own verdicts kept).
         * Each entry's scope is checked to be the outcome it names, so a vector can never pass on a scope it did not make.
         */
        fun liveList(entries: List<Entry>, baseDcap: RecordDcapVerification): Pair<ModelCandidates.Listed, RecordDcapVerification> {
            val c = LiveCandidates.load()
            require(entries.size <= c.list.size) { "the live list has ${c.list.size} entries" }
            val unchecked = DcapVerification.Unchecked("collateral: pcs unreachable")
            val json = entries.mapIndexed { i, e ->
                val entry = c.list[i]
                when (e) {
                    Entry.BINDING_MISMATCH -> editManager(entry) { cm -> JsonObject(cm + ("actions" to JsonArray(cm.getValue("actions").jsonArray.dropLast(1)))) }
                    Entry.BOOT_MISMATCH -> LiveCandidates.editTcb(entry) { t ->
                        val log = t.getValue("event_log").jsonArray
                        val drop = log.indexOfFirst { it.jsonObject["imr"]?.jsonPrimitive?.content == "3" && it.jsonObject["event"]?.jsonPrimitive?.content != "compose-hash" }
                        t["event_log"] = JsonArray(log.filterIndexed { j, _ -> j != drop })
                    }
                    Entry.BOOT_ABSENT -> LiveCandidates.editTcb(entry) { t ->
                        t["event_log"] = JsonArray(t.getValue("event_log").jsonArray.filterNot { it.jsonObject["event"]?.jsonPrimitive?.content == "compose-hash" })
                    }
                    Entry.NO_MANAGER_QUOTE -> editManager(entry) { cm -> JsonObject(cm - "quote") }
                    Entry.CLEAN, Entry.BINDING_UNCHECKED -> entry
                }
            }
            val l = listed(json, c.nonce, c.key)
            val dcap = baseDcap.copy(listed = entries.map { ok }, listedManagers = entries.map { e ->
                when (e) { Entry.BINDING_UNCHECKED -> unchecked; Entry.NO_MANAGER_QUOTE -> null; else -> ok } })
            val scopes = ModelCandidates.trustScopes(l, dcap)
            for ((i, e) in entries.withIndex()) {
                val local = if (e == Entry.BOOT_MISMATCH) ModelCandidates.Result.Mismatch(ModelCandidates.Why.BOOT_LOG) else ModelCandidates.Result.Clean
                check(l.enclaves[i].result == local) { "entry $i ($e): the local checks read ${l.enclaves[i].result}" }
                check(e.binding(scopes[i].deployLogBinding) && e.boot(scopes[i].bootLogReplay)) { "entry $i ($e) made ${scopes[i].deployLogBinding} / ${scopes[i].bootLogReplay}" }
            }
            return l to dcap
        }
    }

    /** The gate's baseline (everything verified), its list the live one with each entry edited to [entries]: the session builds the listed scopes. */
    private fun session(vararg entries: Entry): ConfidentialSession {
        val gate = SendGateRuleTest().also { it.offline() }
        val (l, _) = liveList(entries.toList(), RecordDcapVerification())
        val s = gate.baselineSession(AttestationRecord.preview.copy(modelCandidates = l))
        s.dcapVerification = liveList(entries.toList(), s.dcapVerification!!).second
        return s
    }

    // the run, on the live list

    @Test fun theLiveListIsBoundAndReplaysOnEachEntrysOwnQuote() {
        val c = LiveCandidates.load()
        val scopes = ModelCandidates.trustScopes(listed(c.list, c.nonce, c.key), allOk(3))
        assertEquals(3, scopes.size)
        for ((i, scope) in scopes.withIndex()) {
            assertEquals("entry $i", DeployLogBinding.Bound, scope.deployLogBinding)
            assertEquals("entry $i", BootLogReplay.Matches, scope.bootLogReplay)
            assertTrue(scope.severity != NearTrustScope.Severity.HARD && !scope.listedEvidenceAbsent)
        }
    }

    /** DCAP is asked about each entry's own quote and its compose manager's. */
    @Test fun everyListedQuoteAndItsManagersGoesThroughDcap() = runTest {
        val c = LiveCandidates.load()
        val record = AttestationRecord.preview.copy(modelCandidates = listed(c.list, c.nonce, c.key))
        val seen = Collections.synchronizedList(mutableListOf<ByteArray>())
        val collateral = CollateralProvider { quote -> seen.add(quote); throw java.io.IOException("timed out") }
        val result = DcapService(collateral, DcapQvlEngine { _, _, _ -> throw AssertionError("no collateral, no engine") }).verify(record)
        fun hex(q: String) = q.removePrefix("0x").hexToBytes()
        for (entry in c.list) {
            val node = hex(entry.getValue("intel_quote").jsonPrimitive.content)
            val manager = hex(entry.getValue("compose_manager_attestation").jsonObject.getValue("quote").jsonPrimitive.content)
            assertTrue("an entry's own quote never reached DCAP", seen.any { it.contentEquals(node) })
            assertTrue("an entry's compose-manager quote never reached DCAP", seen.any { it.contentEquals(manager) })
        }
        assertEquals(3, result.listedManagers.size)
        assertTrue(result.hasUnchecked)
    }

    @Test fun theRunChecksTheListsNonce() {
        val c = LiveCandidates.load()
        val l = listed(c.list, c.nonce, c.key).copy(nonce = "00".repeat(32))
        val scopes = ModelCandidates.trustScopes(l, allOk(3))
        assertTrue(scopes.all { it.deployLogBinding == DeployLogBinding.Mismatch(DeployLogBinding.Check.NONCE) && it.severity == NearTrustScope.Severity.HARD })
    }

    @Test fun aListedActionLogThatIsNotItsQuotesIsAHardBreak() {
        val c = LiveCandidates.load()
        val bad = editManager(c.list[1]) { cm -> JsonObject(cm + ("actions" to JsonArray(cm.getValue("actions").jsonArray.dropLast(1)))) }
        val l = listed(listOf(c.list[0], bad, c.list[2]), c.nonce, c.key)
        assertTrue("the local checks never read the action log", l.enclaves.all { it.result == ModelCandidates.Result.Clean })
        val scopes = ModelCandidates.trustScopes(l, allOk(3))
        assertEquals(DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH), scopes[1].deployLogBinding)
        assertTrue(scopes[0].severity != NearTrustScope.Severity.HARD && scopes[2].severity != NearTrustScope.Severity.HARD)
        val s = session(Entry.CLEAN, Entry.BINDING_MISMATCH, Entry.CLEAN)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertTrue(s.degradeIsHardFailure)
    }

    @Test fun aListedEntryWithNoBootLogIsCouldntCheck() {
        val c = LiveCandidates.load()
        val bare = LiveCandidates.editTcb(c.list[2]) { it.remove("event_log") }
        val scopes = ModelCandidates.trustScopes(listed(listOf(c.list[0], bare), c.nonce, c.key), allOk(2))
        assertTrue(scopes[1].bootLogReplay == BootLogReplay.Absent && scopes[1].listedEvidenceAbsent && scopes[1].severity != NearTrustScope.Severity.HARD)
        val s = session(Entry.CLEAN, Entry.BOOT_ABSENT)
        assertEquals("no boot log read as clean", TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertFalse(s.degradeIsHardFailure)
    }

    /** An entry with no compose-manager quote has nothing to bind: couldn't check, never clean. */
    @Test fun aListedEntryWithNoManagerQuoteIsCouldntCheck() {
        val c = LiveCandidates.load()
        val bare = editManager(c.list[1]) { cm -> JsonObject(cm - "quote") }
        val dcap = RecordDcapVerification(model = ok, listed = listOf(ok, ok), listedManagers = listOf(ok, null))
        val scopes = ModelCandidates.trustScopes(listed(listOf(c.list[0], bare), c.nonce, c.key), dcap)
        assertTrue(scopes[1].deployLogBinding is DeployLogBinding.NotBound && scopes[1].listedEvidenceAbsent)
    }

    /** A listed compose manager's quote that fails DCAP is the read enclave's own (a) mismatch: evidence. */
    @Test fun aListedManagerQuoteThatFailsDcapIsAHardBreak() {
        val c = LiveCandidates.load()
        val dcap = allOk(3).copy(listedManagers = listOf(ok, DcapVerification.Failed("signature invalid"), ok))
        val scopes = ModelCandidates.trustScopes(listed(c.list, c.nonce, c.key), dcap)
        assertEquals(DeployLogBinding.Mismatch(DeployLogBinding.Check.QUOTE), scopes[1].deployLogBinding)
        assertTrue(scopes[0].severity != NearTrustScope.Severity.HARD && scopes[2].severity != NearTrustScope.Severity.HARD)
    }

    /** An entry teemoon can't read (no report kept, or no quote) is not bound with no boot log: couldn't check, never clean. */
    @Test fun anEntryTeemoonCannotReadIsCouldntCheck() {
        val scopes = ModelCandidates.trustScopes(ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean)), allOk(1))
        assertTrue(scopes[0].deployLogBinding is DeployLogBinding.NotBound && scopes[0].bootLogReplay == BootLogReplay.Absent && scopes[0].listedEvidenceAbsent)
    }

    /** A listed hard break ends the unpublished-image exemption, as the read one's does (the send stays blocked, not softened). */
    @Test fun aBrokenListedScopeEndsTheUnpublishedImageExemption() {
        val s = session(Entry.CLEAN, Entry.BINDING_MISMATCH)
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(
            ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)), ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))), emptyList())
        assertFalse(s.unpublishedOnlyButE2EEIntact)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
    }

    // the gate

    @Test fun everyCleanListedScopeAllows() = assertEquals(TrustSendPolicy.ALLOW, session(Entry.CLEAN, Entry.CLEAN).sendPolicy)

    @Test fun aListedBindingMismatchBlocks() {
        val s = session(Entry.CLEAN, Entry.BINDING_MISMATCH)
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertTrue(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertEquals("another enclave near.ai lists for this model failed verification.", s.e2eeDegradedReason)
        // the read enclave's own line is unchanged
        s.nearScopeOverride = scope(DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH), BootLogReplay.Matches)
        assertEquals(NearTrustScope.ACTION_LOG_BROKEN, s.e2eeDegradedReason)
    }

    @Test fun aListedBootLogMismatchBlocks() {
        val s = session(Entry.CLEAN, Entry.BOOT_MISMATCH)
        assertTrue(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertEquals("another enclave near.ai lists for this model runs code its boot log doesn't match.", s.e2eeDegradedReason)
        s.nearScopeOverride = scope(DeployLogBinding.Bound, BootLogReplay.Mismatch(BootLogReplay.What.RTMR3))
        assertEquals(NearTrustScope.BOOT_LOG_BROKEN, s.e2eeDegradedReason)
    }

    @Test fun aListedBindingIntelCouldNotCheckAsks() {
        val s = session(Entry.BINDING_UNCHECKED, Entry.CLEAN)
        assertFalse(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertTrue(s.softButSealed)
    }

    @Test fun aListedEnclaveWithNoBootLogOrNoManagerQuoteAsks() {
        for (bad in listOf(Entry.BOOT_ABSENT, Entry.NO_MANAGER_QUOTE)) {
            val s = session(Entry.CLEAN, bad)
            assertFalse(s.degradeIsHardFailure)
            assertEquals("$bad", TrustSendPolicy.CONFIRM, s.sendPolicy)
            assertEquals(NrasVerification.EVIDENCE_ABSENT, s.e2eeDegradedReason)
        }
    }

    /** The session derives the scopes itself from the record and its DCAP pass (the twin of iOS's adopt-starts-its-scopes test). */
    @Test fun adoptingARecordWithAListGivesItsScopes() {
        val c = LiveCandidates.load()
        val record = AttestationRecord.preview.copy(modelCandidates = listed(c.list, c.nonce, c.key))
        val s = SendGateRuleTest().also { it.offline() }.baselineSession(record)
        s.dcapVerification = allOk(3)
        assertEquals(3, s.listedNearScopes?.size)
        assertTrue(s.listedNearScopes!!.all { it.deployLogBinding == DeployLogBinding.Bound })
        s.dcapVerification = RecordDcapVerification(model = ok)
        assertEquals("a DCAP pass without the listed verdicts has no scopes yet", null, s.listedNearScopes)
    }

    /**
     * Audit 1.1.1 A5 (iOS c5e329f2): a list entry names no host, so a listed scope resolves its chat path against the host the app connects to (the record's
     * gpuNodeHost), as the read enclave's does; a host that no running service serves is ambiguous, and an ambiguous path counts every LEAKS page.
     */
    @Test fun aListedScopeResolvesItsChatPathAgainstTheHostTheAppConnectsTo() {
        val c = LiveCandidates.load()
        val l = listed(c.list, c.nonce, c.key)
        assertTrue("no host, no path", ModelCandidates.trustScopes(l, allOk(3)).all { it.chatPath == null })
        val withHost = ModelCandidates.trustScopes(l, allOk(3), host = "glm-5-3.completions.near.ai")
        assertTrue("$withHost", withHost.all { it.chatPath is ChatPath.Result.Ambiguous })
        // and through the session: the record's own gpuNodeHost
        val s = SendGateRuleTest().also { it.offline() }.baselineSession(AttestationRecord.preview.copy(modelCandidates = l, gpuNodeHost = "glm-5-3.completions.near.ai"))
        s.dcapVerification = allOk(3)
        assertTrue(s.listedNearScopes!!.all { it.chatPath is ChatPath.Result.Ambiguous })
    }

    // the key-custody line

    @Test fun theListedStateReadsTheListedScopes() {
        val r = AttestationRecord.preview.copy(modelCandidates = ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean)))
        val d = RecordDcapVerification(model = ok, listed = listOf(ok, ok))
        fun state(scopes: List<NearTrustScope>?) = KeyCustodyCopy.listed(r, d, NrasVerification.Verified, scopes)
        assertEquals(KeyCustodyCopy.Listed.CHECKED, state(clean(2)))
        assertEquals(KeyCustodyCopy.Listed.CHECKING, state(null))
        assertEquals(KeyCustodyCopy.Listed.CHECKING, state(clean(1)))
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, state(listOf(clean, scope(DeployLogBinding.Bound, BootLogReplay.Absent))))
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, state(listOf(clean, scope(DeployLogBinding.Unchecked, BootLogReplay.Matches))))
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, state(listOf(clean, scope(DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG), BootLogReplay.Matches))))
        assertEquals(KeyCustodyCopy.Listed.FAILED, state(listOf(clean, scope(DeployLogBinding.Mismatch(DeployLogBinding.Check.NONCE), BootLogReplay.Matches))))
        assertEquals(KeyCustodyCopy.Listed.FAILED, state(listOf(scope(DeployLogBinding.Bound, BootLogReplay.Mismatch(BootLogReplay.What.COMPOSE_HASH)), clean)))
    }
}
