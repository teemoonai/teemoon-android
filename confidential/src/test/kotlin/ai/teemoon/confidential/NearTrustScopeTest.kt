package ai.teemoon.confidential

// android-only until iOS's trust scope commit: the five fields (pm 2026-11-04, iOS draft 1's names) over audit's live
// glm-5-3-flash report, and the severity each outcome carries: (a) or (b) mismatch hard, not bound / absent soft.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NearTrustScopeTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/$name")!!.readText()).jsonObject
    private val report = resource("glm-5-3-flash-gpu04-5f7ebd85-2026-10-30T2241Z.json")
    private val expected = resource("expected.json")
    private val nonce = expected.getValue("request_nonce_sent").jsonPrimitive.content
    private val cm = report.getValue("compose_manager_attestation").jsonObject
    private val nodeQuote = report.getValue("intel_quote").jsonPrimitive.content
    private val info = report.getValue("info").jsonObject
    private val tcb = info.getValue("tcb_info").jsonObject
    private val osImageHash = info["os_image_hash"]?.jsonPrimitive?.content

    private fun scope(r: JsonObject = report, sent: String = nonce, node: String? = nodeQuote, dcap: Boolean? = true, tcbDiffers: Boolean = false) =
        NearTrustScope.from(r, sent, node, osImageHash, dcap, tcbDiffers, LauncherIndex.NotChecked)
    private fun withCm(c: JsonObject) = JsonObject(report + ("compose_manager_attestation" to c))
    private fun withTcb(t: JsonObject) = JsonObject(report + ("info" to JsonObject(info + ("tcb_info" to t))))

    @Test fun theLiveReportIsBoundAndReplaysAndNamesItsManagerAndServices() {
        val s = scope()
        assertEquals(DeployLogBinding.Bound, s.deployLogBinding)
        assertEquals(BootLogReplay.Matches, s.bootLogReplay)
        assertEquals(NearTrustScope.Severity.NONE, s.severity)
        assertEquals(ComposeManagerSeat.Source.DEPLOY_LOG, s.composeManager?.source)
        assertEquals("a320bb4882341c8e19ef1944436f6591ed73303732009ed5db9f6db6c9da756a", s.composeManager?.running)
        assertEquals(DeployLog.declaredManager(tcb.getValue("app_compose").jsonPrimitive.content), s.composeManager?.declared)
        // with no whole file fetched, the walk reads them as services unknown (RunningWalkTest has the fetched case)
        // (the rows add per-service liveness on top of the walk: RunningWalkTest holds it)
        assertEquals(DeployLog.runningServices(cm.getValue("actions").jsonArray) { null }, s.runningServices.map { it.copy(undecidable = false) })
        cm.getValue("actions").jsonArray.let { a -> assertEquals((DeployLog.wholeFileSources(a) + DeployLog.upRevisionSources(a)).distinct(), s.wholeFileSources) }
    }

    /**
     * The scope with every revision's file in hand (iOS 4d2a41e3, b3d90fac): each row carries per-service liveness —
     * on gpu04, rotate-token alone — and a host gets a chat path.
     */
    @Test fun withTheFilesInHandEachRowCarriesItsLivenessAndTheHostAPath() {
        val acts = cm.getValue("actions").jsonArray
        val sources = (DeployLog.wholeFileSources(acts) + DeployLog.upRevisionSources(acts) +
            DeployLog.runningServices(acts, ChatPathTest.vendored).mapNotNull(ChatPath::sourceOf)).distinct()
        val fetched = sources.associate { (it.commit to it.file) to (ComposeFileFetch.Digest(it.fileSha256, ChatPathTest.vendored(it)) as ComposeFileFetch) }
        val bodies = sources.mapNotNull { src -> ChatPathTest.body(src)?.let { src to it } }.toMap()
        val s = NearTrustScope.from(report, nonce, nodeQuote, osImageHash, true, false, LauncherIndex.NotChecked, fetched, bodies = bodies, host = "nobody.completions.near.ai")
        assertEquals(listOf("rotate-token"), s.runningServices.filter { it.undecidable }.map { it.service })
        assertEquals(true, s.chatPath is ChatPath.Result.Ambiguous)
        assertEquals("no host, no path", null, NearTrustScope.from(report, nonce, nodeQuote, osImageHash, true, false, LauncherIndex.NotChecked, fetched, bodies = bodies).chatPath)
    }

    /** Nothing from the log is shown as pinned until (a) passes: no running manager, no services. */
    @Test fun anUnboundLogNamesNothing() {
        val actions = cm.getValue("actions").jsonArray
        val first = actions[0].jsonObject
        val bent = JsonObject(first + ("timestamp" to JsonPrimitive(first.getValue("timestamp").jsonPrimitive.content.replaceFirst('2', '3'))))
        val s = scope(withCm(JsonObject(cm + ("actions" to JsonArray(listOf(bent) + actions.drop(1))))))
        assertEquals(DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH), s.deployLogBinding)
        assertEquals(NearTrustScope.Severity.HARD, s.severity)
        assertEquals(ComposeManagerSeat.Source.DECLARED_ONLY, s.composeManager?.source)
        assertEquals(null, s.composeManager?.running)
        assertEquals(emptyList<RunningService>(), s.runningServices)
    }

    @Test fun eachBindingCheckHasItsName() {
        assertEquals(DeployLogBinding.Mismatch(DeployLogBinding.Check.NONCE), scope(sent = nonce.reversed()).deployLogBinding)
        assertEquals(DeployLogBinding.Mismatch(DeployLogBinding.Check.QUOTE), scope(dcap = false).deployLogBinding)
        assertEquals(DeployLogBinding.Mismatch(DeployLogBinding.Check.TCB_DIFFERS), scope(tcbDiffers = true).deployLogBinding)
        // the node's MRTD (body offset 136, after the 48-byte header) moved: the manager quote is another TD's
        val at = (48 + 136) * 2
        val otherTd = nodeQuote.substring(0, at) + (if (nodeQuote[at] == '0') '1' else '0') + nodeQuote.substring(at + 1)
        assertEquals(DeployLogBinding.Mismatch(DeployLogBinding.Check.SAME_TD), NearTrustScope.deployLogBinding(cm, nonce, otherTd, true, false))
    }

    @Test fun notBoundIsSoft() {
        assertEquals(DeployLogBinding.NotBound(DeployLogBinding.Reason.CHECKING), scope(dcap = null).deployLogBinding)
        assertEquals(NearTrustScope.Severity.SOFT, scope(dcap = null).severity)
        val none = scope(JsonObject(report - "compose_manager_attestation"))
        assertEquals(DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG), none.deployLogBinding)
        assertEquals(NearTrustScope.Severity.SOFT, none.severity)
        assertEquals(ComposeManagerSeat.Source.DECLARED_ONLY, none.composeManager?.source)
    }

    @Test fun eachReplayFailureHasItsName() {
        val log = tcb.getValue("event_log").jsonArray
        val idx = log.indices.filter { log[it].jsonObject.getValue("imr").jsonPrimitive.content == "3" }
        val swapped = log.toMutableList().also { val t = it[idx[0]]; it[idx[0]] = it[idx[1]]; it[idx[1]] = t }
        assertEquals(BootLogReplay.Mismatch(BootLogReplay.What.RTMR3), scope(withTcb(JsonObject(tcb + ("event_log" to JsonArray(swapped))))).bootLogReplay)
        assertEquals(BootLogReplay.Mismatch(BootLogReplay.What.COMPOSE_HASH),
            scope(withTcb(JsonObject(tcb + ("app_compose" to JsonPrimitive(tcb.getValue("app_compose").jsonPrimitive.content + " "))))).bootLogReplay)
        assertEquals(BootLogReplay.Mismatch(BootLogReplay.What.OS_IMAGE_HASH),
            NearTrustScope.bootLogReplay(tcb, nodeQuote, "0".repeat(64)))
        // the compose-hash entry's payload bent: its digest no longer recomputes, while the replay (over digests) still holds
        val renamed = log.map { e -> val o = e.jsonObject
            if (o["event"]?.jsonPrimitive?.content != "compose-hash") o
            else JsonObject(o + ("event_payload" to JsonPrimitive("00" + o.getValue("event_payload").jsonPrimitive.content.drop(2)))) }
        assertEquals(BootLogReplay.Mismatch(BootLogReplay.What.EVENT_DIGEST), scope(withTcb(JsonObject(tcb + ("event_log" to JsonArray(renamed))))).bootLogReplay)
        val bent = scope(withTcb(JsonObject(tcb + ("event_log" to JsonArray(swapped)))))
        assertEquals(NearTrustScope.Severity.HARD, bent.severity)
        assertEquals(NearTrustScope.BOOT_LOG_BROKEN, bent.hardBreakReason)
        assertEquals(null, scope().hardBreakReason)
    }

    @Test fun noBootLogIsAbsentAndSoft() {
        val s = scope(withTcb(JsonObject(tcb - "event_log")))
        assertEquals(BootLogReplay.Absent, s.bootLogReplay)
        assertEquals(NearTrustScope.Severity.SOFT, s.severity)
    }

    /** 1.1.1 audit integ read, item 2 (iOS TrustScope.swift @ 01a337a4 run(): info.tcb_info may be a STRING holding the JSON). */
    @Test fun aTcbInfoStringHoldingTheJsonReadsLikeTheObject() {
        val asString = JsonObject(report + ("info" to JsonObject(info + ("tcb_info" to JsonPrimitive(tcb.toString())))))
        val s = scope(asString); val o = scope()
        assertEquals(BootLogReplay.Matches, s.bootLogReplay)
        assertEquals(o.bootLogReplay, s.bootLogReplay)
        assertEquals(o.composeManager, s.composeManager)
        assertEquals(NearTrustScope.Severity.NONE, s.severity)
    }
}
