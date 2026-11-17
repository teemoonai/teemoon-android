// ports: teemoonTests/ActionLogBindingTests.swift @ aa84e369 (TrustScopeLineTests) and @ 711e006b
//        (aDifferentRunningManagerStripsTheDeclaredImagesLinksAndReview)
//
// near-trust-scope (the maintainer 2026-11-06): which approved line each result gets.
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustScopeLineTest {
    @Test fun eachResultGetsItsApprovedLine() {
        assertEquals("action log bound to this enclave's hardware ✓", TrustScopeText.actionLogLine(DeployLogBinding.Bound)?.text)
        assertEquals(TrustScopeLine.Tone.CAUTION, TrustScopeText.actionLogLine(DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG))?.tone)
        assertTrue(TrustScopeText.actionLogLine(DeployLogBinding.Unchecked)?.text?.startsWith("action log not checked: intel's check") == true)
        assertEquals(TrustScopeLine("action log doesn't match its quote. do not trust.", "the quote answers a different request.", TrustScopeLine.Tone.BROKEN),
            TrustScopeText.actionLogLine(DeployLogBinding.Mismatch(DeployLogBinding.Check.NONCE)))
        assertNull(TrustScopeText.actionLogLine(null))
        assertEquals(TrustScopeLine.Tone.PROVEN, TrustScopeText.bootLogLine(BootLogReplay.Matches)?.tone)
        assertEquals(TrustScopeLine.Tone.CAUTION, TrustScopeText.bootLogLine(BootLogReplay.Absent)?.tone)
        assertEquals("boot event log doesn't match the quote. do not trust.", TrustScopeText.bootLogLine(BootLogReplay.Mismatch(BootLogReplay.What.RTMR3))?.text)
        assertEquals(" · in the quote ✓", TrustScopeText.osImageSuffix(BootLogReplay.Matches)); assertEquals("", TrustScopeText.osImageSuffix(BootLogReplay.Absent))
    }

    @Test fun theComposeManagerLineNamesWhatRuns() {
        val d = "6e".repeat(32); val r = "a0".repeat(32)
        assertNull("nothing from an unbound log", TrustScopeText.composeManagerLine(null))
        assertNull("nothing from an unbound log", TrustScopeText.composeManagerLine(ComposeManagerSeat(d, null, ComposeManagerSeat.Source.DECLARED_ONLY)))
        assertEquals("no start recorded in the action log: showing the declared image.",
            TrustScopeText.composeManagerLine(ComposeManagerSeat(d, null, ComposeManagerSeat.Source.DEPLOY_LOG))?.text)
        assertEquals("running per the action log", TrustScopeText.composeManagerLine(ComposeManagerSeat(d, d, ComposeManagerSeat.Source.DEPLOY_LOG))?.text)
        val differs = TrustScopeText.composeManagerLine(ComposeManagerSeat(d, r, ComposeManagerSeat.Source.DEPLOY_LOG))
        assertEquals("declared sha256:6e6e6e6e · running sha256:a0a0a0a0", differs?.text)
        assertEquals("never green: the running manager isn't measured", TrustScopeLine.Tone.CAUTION, differs?.tone)
    }

    @Test fun aRunningRowNamesServiceFileCommitAndItsFilesCheck() {
        val s = RunningService("proxy-glm53", "prod/V12Q8.yaml", "s", "7ee4a4c741259d0c", "v0.0.521", "2026-10-29T00:11:24.857389316+00:00")
        val row = TrustScopeText.runningRow(s, ComposeFileCheck.Unpublished)
        assertEquals("proxy-glm53 · prod/V12Q8.yaml @ 7ee4a4c", row.title)
        assertEquals("v0.0.521 · since 2026-10-29", row.caption)
        assertTrue(row.state!!.text.startsWith("not published at this commit") && row.state.tone == TrustScopeLine.Tone.CAUTION)
        assertEquals(TrustScopeLine.Tone.BROKEN, TrustScopeText.runningRow(s, ComposeFileCheck.Mismatch("0")).state?.tone)
        assertEquals("(whole file) · prod/V12Q8.yaml @ 7ee4a4c", TrustScopeText.runningRow(s.copy(service = ""), ComposeFileCheck.NotFetched).title)
        assertNull("android: no words for a fetch in flight", TrustScopeText.runningRow(s, ComposeFileCheck.Checking).state)
    }

    /** The live fleet since 2026-11-04 (audit): the row must not show 6e035c8f's links or review over a0c07308. */
    @Test fun aDifferentRunningManagerStripsTheDeclaredImagesLinksAndReview() {
        val d = "6e".repeat(32); val r = "a0".repeat(32)
        val entry = ImageEntry(id = "cm", name = "nearaidev/compose-manager", kind = ImageEntry.Kind.REGISTRY).apply {
            auditURL = "https://example.com/audit.md"; links = listOf(RunLink("sigstore", "https://example.com/sig")) }
        val differs = TrustScopeText.managerRow(entry, ComposeManagerSeat(d, r, ComposeManagerSeat.Source.DEPLOY_LOG))
        assertTrue(differs.auditURL == null && differs.links.isEmpty() && differs.projectPointer == null)
        assertEquals("the declared image stays shown", entry.name, differs.name)
        val same = TrustScopeText.managerRow(entry, ComposeManagerSeat(d, d, ComposeManagerSeat.Source.DEPLOY_LOG))
        assertNotNull("the same image keeps its own review", same.auditURL); assertTrue(same.links.isNotEmpty())
    }

    /**
     * iOS 359a99d1: audits 8c9208b published a page for a0c07308, so the row carries the RUNNING manager's own review,
     * never the declared one's (a live capture of 2026-11-06 showed no review at all on the row).
     */
    @Test fun aDifferentRunningManagerWearsItsOwnReview() {
        val d = "6e".repeat(32); val r = "a0".repeat(32)
        val declaredPage = "https://example.com/compose-manager/sha256-$d.md"; val runningPage = "https://example.com/compose-manager/sha256-$r.md"
        val entry = ImageEntry(id = "cm", name = "nearaidev/compose-manager", kind = ImageEntry.Kind.REGISTRY).apply { auditURL = declaredPage }
        val pages = mapOf(d to declaredPage, r to runningPage)
        val row = TrustScopeText.managerRow(entry, ComposeManagerSeat(d, r, ComposeManagerSeat.Source.DEPLOY_LOG)) { pages[it] }
        assertEquals(runningPage, row.auditURL)
        // the pill under the review is the running image, not the declared one (pm, iOS d667e6c5)
        assertEquals("sha256:$r", row.digestFull)
        assertEquals("sha256:${r.take(12)}…", row.digest)
        assertNull("no page for it: still nothing", TrustScopeText.managerRow(entry, ComposeManagerSeat(d, r, ComposeManagerSeat.Source.DEPLOY_LOG)) { null }.auditURL)
    }

    /** near-trust-scope: until (a) binds the log, nothing calls it signed — the enclave's deployment caption included. */
    @Test fun theDeploymentCaptionSaysSignedOnlyOnceTheLogIsBound() {
        val r = AttestationRecord.preview
        assertTrue(r.deploymentCaption(signed = true)!!.endsWith(" · per the signed action log"))
        assertTrue(r.deploymentCaption(signed = false)!!.endsWith(" · per the action log"))
        val unbound = EnclaveGrouping(r, ManifestProvenance.AllVerified(emptyList(), emptyList()), null, null, AuditIndex(fixedIndex = null), actionLogBound = false).groups().first { it.primary }
        assertEquals(r.deploymentCaption(signed = false), unbound.deployment)
    }

    /** trust-running-undecidable (the maintainer 2026-11-06): a row whose liveness the log can't decide says so, in orange. */
    @Test fun anUndecidableRowSaysTheLogCannotTell() {
        val s = RunningService("rotate-token", "prod/rotate-compose-manager-token.yaml", "s", "3c2f033b", null, "2026-10-01", undecidable = true)
        val row = TrustScopeText.runningRow(s, ComposeFileCheck.Matches)
        assertEquals(TrustScopeLine("the action log can't tell whether this is still running", "a later full redeploy of another model may have removed it",
            TrustScopeLine.Tone.CAUTION), row.liveness)
        assertNull(TrustScopeText.runningRow(s.copy(undecidable = false), ComposeFileCheck.Matches).liveness)
    }

    /**
     * running-manager-unreviewed (the maintainer approved, 2026-11-06): a running compose manager with no review of its own says
     * so in orange, never borrowing the declared manager's review, and the everyday claim drops to not-all-reviewed.
     * It never touches sending.
     */
    @org.junit.Test fun aRunningManagerWithNoReviewSaysSoAndIsNotReviewed() {
        val seat = ComposeManagerSeat(declared = "a".repeat(64), running = "b".repeat(64), source = ComposeManagerSeat.Source.DEPLOY_LOG)
        val line = TrustScopeText.unreviewedManagerLine(seat) { null }
        org.junit.Assert.assertEquals("running manager unreviewed: teemoon has no review of this build yet.", line?.text)
        org.junit.Assert.assertEquals(TrustScopeLine.Tone.CAUTION, line?.tone)
        org.junit.Assert.assertNull("a reviewed running manager", TrustScopeText.unreviewedManagerLine(seat) { "https://review/$it" })
        org.junit.Assert.assertNull("the declared manager running", TrustScopeText.unreviewedManagerLine(seat.copy(running = seat.declared)) { null })
        org.junit.Assert.assertEquals(EverydayAuditState.INCOMPLETE, withRunningManager(EverydayAuditState.REVIEWED, managerUnreviewed = true))
        org.junit.Assert.assertEquals(EverydayAuditState.INCOMPLETE, withRunningManager(EverydayAuditState.REVIEWED_WITH_CAVEAT, managerUnreviewed = true))
        org.junit.Assert.assertEquals("a leak stays a leak", EverydayAuditState.LEAKS, withRunningManager(EverydayAuditState.LEAKS, managerUnreviewed = true))
        org.junit.Assert.assertEquals(EverydayAuditState.REVIEWED, withRunningManager(EverydayAuditState.REVIEWED, managerUnreviewed = false))
    }
}
