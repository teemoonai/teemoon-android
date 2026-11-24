// ports: teemoonTests/ListedReviewedRowTests.swift @ c5e329f2 (1.1.1 audit A5), all nine tests, over modelNodeAuditOver (iOS ModelNodeAudit.state): the same
//        fixtures, no network. Android's trust scope is NearTrustScope (its runningServices are iOS's running rows) and its audits index is built
//        through AuditIndex(fixedIndex:) refreshed once so it is fresh for the everyday claim (iOS passes loadedAt).
//
// Audit 1.1.1 A5: every enclave near.ai lists for a model holds the key a sealed request goes to, but the trust sheet's "reviewed" row described
// only the READ enclave's compose and what its host runs. Each listed enclave is scoped by everything its host runs, with an off-path LEAKS page
// not LEAKS for this chat (expected.json chat_path), and anything unreviewable reads "not everything here has been reviewed yet". The row is the
// worst across them all: leaks > incomplete > reviewedWithCaveat > reviewed. Display only: the send gate never reads the audits index.
package ai.teemoon.app

import ai.teemoon.confidential.AuditIndex
import ai.teemoon.confidential.BootLogReplay
import ai.teemoon.confidential.ChatPath
import ai.teemoon.confidential.DeployLog
import ai.teemoon.confidential.DeployLogBinding
import ai.teemoon.confidential.EnclaveGroup
import ai.teemoon.confidential.EverydayAuditState
import ai.teemoon.confidential.ImageEntry
import ai.teemoon.confidential.LauncherIndex
import ai.teemoon.confidential.MODEL_COMPOSE_REPO
import ai.teemoon.confidential.ModelCandidates
import ai.teemoon.confidential.NearTrustScope
import ai.teemoon.confidential.PlaintextExposure
import ai.teemoon.confidential.RunningService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ListedReviewedRowTest {
    private val engine = "cd".repeat(32)
    private val os = "05".repeat(32)
    private val composeA = "a1".repeat(32)
    private val composeB = "b2".repeat(32)
    private val unreviewedCompose = "ee".repeat(32)
    private val glmSHA = "11".repeat(32)
    private val smallSHA = "22".repeat(32)
    /** A revision the audits have no page for: what a private third-party image's revision looks like in the index. */
    private val sageSHA = "33".repeat(32)
    /** A running revision the audits have a page for but no verdict class on: a gap, never a pass. */
    private val extraSHA = "44".repeat(32)
    private val extraFile = "prod/sidecar.yaml"
    private val glmFile = "prod/GLM-5.3-Flash.yaml"
    private val smallFile = "prod/small-models.yaml"
    private val leakSentence = "the FLUX service logs every prompt."

    private fun page(url: String) = AuditIndex.pagePath(url)!!

    private fun audit(): AuditIndex {
        val base = AuditIndex.Index(schema = 1, images = mapOf("docker.io/nearaidev/engine" to listOf(engine)),
            manifests = mapOf("$MODEL_COMPOSE_REPO/$glmFile" to listOf(glmSHA), "$MODEL_COMPOSE_REPO/$smallFile" to listOf(smallSHA), "$MODEL_COMPOSE_REPO/$extraFile" to listOf(extraSHA)),
            measured = listOf(composeA, composeB), os = listOf(os))
        val bare = AuditIndex(fixedIndex = base)
        val small = bare.manifestAuditURL(smallFile, smallSHA)!!
        val classes = mutableMapOf(page(bare.imageAuditURL("nearaidev/engine", engine)!!) to "private",
            page(bare.manifestAuditURL(glmFile, glmSHA)!!) to "qualified-pass", page(bare.osAuditURL(os)!!) to "qualified-pass", page(small) to "leaks")
        for (c in listOf(composeA, composeB)) classes[page(bare.measuredAuditURL(c)!!)] = "qualified-pass"
        val index = base.copy(verdictClass = classes, exposure = mapOf(page(small) to AuditIndex.Exposure(leakSentence, scopeKind = "recipe", services = listOf("model-flux"))))
        // refreshed once (a 304): the index is then fresh for the everyday claim
        return AuditIndex(fixedIndex = index, fetch = { AuditIndex.Reply(304, ByteArray(0)) }, nowMs = { 1_000L }).also { runBlocking { it.refresh() } }
    }

    /** The read enclave: one plaintext toucher with a private review. Reviewed on its own. */
    private fun readGroup(audit: AuditIndex): EnclaveGroup {
        val row = ImageEntry(id = "engine", name = "nearaidev/engine", kind = ImageEntry.Kind.REGISTRY, plaintextRole = PlaintextExposure.Role.MODEL_SERVER,
            auditURL = audit.imageAuditURL("nearaidev/engine", engine))
        return EnclaveGroup(id = "model", role = "model enclave", primary = true, binding = "", mrConfig = null, quoteVerified = true, images = listOf(row))
    }

    private fun enclave(compose: String) = ModelCandidates.Enclave("i-${compose.take(4)}", compose, "app", null, null, ModelCandidates.Result.Clean, osImageHash = os)

    private fun source(file: String, sha: String) = DeployLog.Source("c0ffee", file, sha)
    private fun row(service: String, file: String, sha: String) = RunningService(service, file, sha, "c0ffee", null, null)

    /** A listed host serving glm on its own path; `also` runs beside it; `onPath` adds hops. */
    private fun scope(also: List<RunningService> = emptyList(), onPath: List<RunningService> = emptyList(), chatPath: ChatPath.Result? = null): NearTrustScope {
        val glm = listOf(row("nginx", glmFile, glmSHA), row("proxy-glm53", glmFile, glmSHA), row("model-glm53", glmFile, glmSHA))
        val hops = (glm + onPath).map { ChatPath.Hop(it.service, source(it.file!!, it.fileSHA256!!), null) }
        return NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, glm + onPath + also, LauncherIndex.NotChecked, chatPath = chatPath ?: ChatPath.Result.Path(hops))
    }

    private fun row(enclaves: List<ModelCandidates.Enclave>?, scopes: List<NearTrustScope>?, listFailed: Boolean = false): ModelNodeAudit {
        val audit = audit()
        assertNotNull("precondition: the index is fresh", audit.takeIf { it.isFreshForEveryday })
        return modelNodeAuditOver(readGroup(audit), hasAttestation = true, chatPath = null, runningSources = emptyList(), composeManager = null,
            listed = enclaves?.let { ModelCandidates.Listed(it) }, listFailed = listFailed, listedScopes = scopes, audit = audit)
    }

    @Test fun theReadEnclaveAloneIsReviewed() = assertEquals("precondition: no list, the read enclave's row as before", EverydayAuditState.REVIEWED, row(null, null).state)

    @Test fun everyListedEnclaveReviewedIsReviewed() {
        val r = row(listOf(enclave(composeA), enclave(composeB)), listOf(scope(), scope()))
        assertEquals(EverydayAuditState.REVIEWED, r.state)
        assertEquals(null, r.leaksExposure)
    }

    @Test fun oneListedOnAnUnreviewedComposeIsIncomplete() =
        assertEquals("a key holder on a compose the audits never read is not reviewed", EverydayAuditState.INCOMPLETE,
            row(listOf(enclave(composeA), enclave(unreviewedCompose)), listOf(scope(), scope())).state)

    @Test fun oneListedWhoseHostRunsAnOnPathLeakIsLeaks() {
        val flux = row("model-flux", smallFile, smallSHA)
        val r = row(listOf(enclave(composeA), enclave(composeB)), listOf(scope(), scope(onPath = listOf(flux))))
        assertEquals(EverydayAuditState.LEAKS, r.state)
        assertEquals("the reviewer's own sentence, from the listed enclave's page", leakSentence, r.leaksExposure)
    }

    @Test fun anOffPathLeakOnAListedHostIsNotLeaks() {
        val flux = row("model-flux", smallFile, smallSHA)
        val r = row(listOf(enclave(composeA), enclave(composeB)), listOf(scope(), scope(also = listOf(flux))))
        assertEquals("expected.json chat_path: a LEAKS page whose services miss the path is not this chat's", EverydayAuditState.REVIEWED, r.state)
    }

    @Test fun aLeakOnAListedHostWithNoResolvedPathCounts() {
        val flux = row("model-flux", smallFile, smallSHA)
        val unsure = scope(also = listOf(flux), chatPath = ChatPath.Result.Ambiguous("no server_name matches"))
        assertEquals("ambiguous counts every LEAKS page on the CVM", EverydayAuditState.LEAKS, row(listOf(enclave(composeA)), listOf(unsure)).state)
    }

    @Test fun anUnreviewablePrivateImageOnAListedHostIsIncomplete() {
        val sage = row("sage", smallFile, sageSHA)
        val r = row(listOf(enclave(composeA), enclave(composeB)), listOf(scope(), scope(also = listOf(sage))))
        assertEquals("whole host: a service off the path with no review still runs beside the key", EverydayAuditState.INCOMPLETE, r.state)
    }

    @Test fun listedInputsPendingAreNotReviewed() {
        val both = listOf(enclave(composeA), enclave(composeB))
        assertEquals("trust scopes not in yet", EverydayAuditState.INCOMPLETE, row(both, null).state)
        assertEquals("one enclave's scope missing", EverydayAuditState.INCOMPLETE, row(both, listOf(scope())).state)
        assertEquals("a list that failed names no holder", EverydayAuditState.INCOMPLETE, row(null, null, listFailed = true).state)
    }

    @Test fun aListedLogTeemoonCouldNotReadIsNotReviewed() {
        val unread = NearTrustScope(DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG), BootLogReplay.Absent, null, emptyList(), LauncherIndex.NotChecked)
        assertEquals("no bound log: what the host runs is unknown", EverydayAuditState.INCOMPLETE, row(listOf(enclave(composeA)), listOf(unread)).state)
    }

    // Android's additions: what iOS's nine leave to its mutants' luck (the order of the worst-of, the clean-entry gate, the whole-host pages)

    @Test fun leaksOutranksIncompleteAcrossTheList() {
        val flux = row("model-flux", smallFile, smallSHA)
        val r = row(listOf(enclave(unreviewedCompose), enclave(composeB)), listOf(scope(), scope(onPath = listOf(flux))))
        assertEquals("leaks > incomplete, wherever each sits in the list", EverydayAuditState.LEAKS, r.state)
        assertEquals(leakSentence, r.leaksExposure)
    }

    @Test fun aListedEntryThatFailedACheckIsNotReviewedOnItsCompose() {
        val broken = enclave(composeA).copy(result = ModelCandidates.Result.Mismatch(ModelCandidates.Why.MR_CONFIG))
        assertEquals("the measured compose is only read over a clean entry", EverydayAuditState.INCOMPLETE, row(listOf(broken), listOf(scope())).state)
    }

    @Test fun aRunningRevisionWithAPageButNoVerdictOnAListedHostIsNotReviewed() {
        val sidecar = row("sidecar", extraFile, extraSHA)
        assertEquals("whole host: every running revision's page judges the enclave", EverydayAuditState.INCOMPLETE,
            row(listOf(enclave(composeA)), listOf(scope(also = listOf(sidecar)))).state)
    }
}
