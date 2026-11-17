// ports: teemoon/Confidential/TrustScopeText.swift @ aa84e369 (the approved lines and which result gets which), with
//        TrustScope.managerRow @ 711e006b; runningRows draws activeRunning as RecipeCardView.swift @ 71f83846
package ai.teemoon.confidential

/**
 * Trust scope's words, as approved on the review page (near-trust-scope, the maintainer 2026-11-06), and which line each result
 * gets — pure, so the choice is tested and both platforms map the same results the same way. Change the words only
 * through the review page.
 */
object TrustScopeText {
    // (a)
    const val ACTION_LOG_BOUND = "action log bound to this enclave's hardware ✓"
    /** A3 (the maintainer 2026-11-08; iOS 123d0b28): "signed" or "attested" action log only once (a) binds it. */
    fun measuredBindingNote(b: DeployLogBinding?): String =
        if (b == DeployLogBinding.Bound) MeasuredConfig.BINDING_NOTE
        else "parsed live from the inner compose — pinned by the action log and hash-checked on this device. the CPU's boot measurement alone does not cover this file."
    fun recipeDeclaresCaption(b: DeployLogBinding?): String =
        (if (b == DeployLogBinding.Bound) "the images the signed action log’s recipe declares" else "the images the action log’s recipe declares") +
            " — checked on this device against its `file_sha256`."
    /** One line for every binding: even a bound log can pin no recipe. */
    const val NO_ACTION_LOG_DETAIL = "the action log pins no engine + proxy recipe for this enclave, so it can’t be hash-checked on this device — it isn’t pinned by anything you can verify here."
    const val ACTION_LOG_BOUND_DETAIL = "the compose manager's own quote signs this log, inside the same enclave as the model."
    const val ACTION_LOG_NOT_BOUND = "action log not bound: near.ai didn't send the compose manager's quote, so the log below is what near.ai reports, not proven."
    const val ACTION_LOG_NOT_BOUND_SHORT = "action log not bound"
    const val ACTION_LOG_MISMATCH = "action log doesn't match its quote. do not trust."
    fun actionLogMismatchDetail(check: DeployLogBinding.Check): String = when (check) {
        DeployLogBinding.Check.QUOTE -> "the quote didn't verify."
        DeployLogBinding.Check.ACTIONS_HASH -> "the log was changed after it was signed."
        DeployLogBinding.Check.NONCE -> "the quote answers a different request."
        DeployLogBinding.Check.SAME_TD -> "the quote comes from a different enclave."
        DeployLogBinding.Check.TCB_DIFFERS -> "the two quotes disagree about the hardware's patch level."
    }

    // (b)
    const val BOOT_LOG_MEASURED = "compose file and guest os measured into the quote ✓"
    const val BOOT_LOG_MEASURED_DETAIL = "replayed from the boot event log (rtmr3)."
    const val BOOT_LOG_ABSENT = "boot event log missing: the compose file matches its hash, but that hash isn't tied to the quote."
    const val BOOT_LOG_MISMATCH = "boot event log doesn't match the quote. do not trust."
    const val OS_IMAGE_IN_QUOTE_SUFFIX = " · in the quote ✓"

    // (2)
    const val RUNNING_PER_ACTION_LOG_SUFFIX = " · running per the action log"
    fun composeManagerDiffers(declared: String, running: String) = "declared sha256:${declared.take(8)} · running sha256:${running.take(8)}"
    const val COMPOSE_MANAGER_DIFFERS_DETAIL = "the action log names the running compose manager. the measured launcher only requires near.ai's signature on it, so this image isn't measured."
    const val COMPOSE_MANAGER_NO_START = "no start recorded in the action log: showing the declared image."
    /** running-manager-unreviewed (the maintainer approved, 2026-11-06): the running manager has no review of its own. */
    const val RUNNING_MANAGER_UNREVIEWED = "running manager unreviewed: teemoon has no review of this build yet."

    // (c)
    const val RUNNING_HEADER = "running now, per the action log"
    fun runningRow(service: String, file: String, commit: String) = "$service · $file @ ${commit.take(7)}"
    fun runningCaption(tag: String, since: String) = "$tag · since $since"
    const val RUNNING_FOOTER = "what the action log names as up, not a container list. a full restart may have removed services the log can't show."
    const val NOT_FETCHED = "not fetched"
    /** trust-running-undecidable (the maintainer 2026-11-06): the log can't say whether this row is still up. */
    const val UNDECIDABLE = "the action log can't tell whether this is still running"
    const val UNDECIDABLE_DETAIL = "a later full redeploy of another model may have removed it"
    const val NOT_PUBLISHED_AT_COMMIT = "not published at this commit: near.ai's compose-files repo has no such file, so it can't be checked."
    // Reused as-is from the recipe card.
    const val FILE_VERIFIED = "verified on this device ✓"
    const val FILE_MISMATCH = "✗ does not match the action log — recipe unverified, do not trust"
    const val FILE_UNREACHABLE = "source unreachable — could not verify (transient)"

    // (d)
    fun launcherViaIndex(index: String) = "attested via its linux/amd64 index ${index.take(8)}"
    const val LAUNCHER_CHECKING = "finding the launcher's published index…"
    const val LAUNCHER_NOT_FOUND = "not in any index near.ai published"

    // Which line each result gets.

    fun actionLogLine(b: DeployLogBinding?): TrustScopeLine? = when (b) {
        null -> null
        DeployLogBinding.Bound -> TrustScopeLine(ACTION_LOG_BOUND, ACTION_LOG_BOUND_DETAIL, TrustScopeLine.Tone.PROVEN)
        is DeployLogBinding.NotBound -> TrustScopeLine(ACTION_LOG_NOT_BOUND, null, TrustScopeLine.Tone.CAUTION)
        DeployLogBinding.Unchecked -> TrustScopeLine(NearTrustScope.ACTION_LOG_NOT_CHECKED, null, TrustScopeLine.Tone.CAUTION)
        is DeployLogBinding.Mismatch -> TrustScopeLine(ACTION_LOG_MISMATCH, actionLogMismatchDetail(b.check), TrustScopeLine.Tone.BROKEN)
    }

    fun bootLogLine(b: BootLogReplay?): TrustScopeLine? = when (b) {
        null -> null
        BootLogReplay.Matches -> TrustScopeLine(BOOT_LOG_MEASURED, BOOT_LOG_MEASURED_DETAIL, TrustScopeLine.Tone.PROVEN)
        BootLogReplay.Absent -> TrustScopeLine(BOOT_LOG_ABSENT, null, TrustScopeLine.Tone.CAUTION)
        is BootLogReplay.Mismatch -> TrustScopeLine(BOOT_LOG_MISMATCH, null, TrustScopeLine.Tone.BROKEN)
    }

    /** Appended to the guest OS's os_image value when the boot log puts it in the quote. */
    fun osImageSuffix(b: BootLogReplay?) = if (b == BootLogReplay.Matches) OS_IMAGE_IN_QUOTE_SUFFIX else ""

    /** The compose manager's row: what the action log says is running. Nothing from an unbound log. */
    fun composeManagerLine(seat: ComposeManagerSeat?): TrustScopeLine? {
        if (seat == null || seat.source == ComposeManagerSeat.Source.DECLARED_ONLY) return null
        val running = seat.running ?: return TrustScopeLine(COMPOSE_MANAGER_NO_START, null, TrustScopeLine.Tone.CAUTION)
        // never green when they differ: the running manager isn't measured
        if (running != seat.declared) return TrustScopeLine(composeManagerDiffers(seat.declared, running), COMPOSE_MANAGER_DIFFERS_DETAIL, TrustScopeLine.Tone.CAUTION)
        return TrustScopeLine(RUNNING_PER_ACTION_LOG_SUFFIX.drop(3), null, TrustScopeLine.Tone.PROVEN)
    }

    /**
     * One (c) row: a service, its file at its commit, and its file's check. [state] is null while the file is being
     * fetched; [liveness] is set when the log can't decide whether the row is still running.
     */
    data class RunningRowText(val title: String, val caption: String?, val state: TrustScopeLine?, val liveness: TrustScopeLine? = null)

    fun runningRow(s: RunningService, check: ComposeFileCheck): RunningRowText {
        val caption = s.tag?.let { runningCaption(it, (s.since ?: "").take(10)) }
        val state = when (check) {
            ComposeFileCheck.Matches -> TrustScopeLine(FILE_VERIFIED, null, TrustScopeLine.Tone.PROVEN)
            is ComposeFileCheck.Mismatch -> TrustScopeLine(FILE_MISMATCH, null, TrustScopeLine.Tone.BROKEN)
            ComposeFileCheck.Unpublished -> TrustScopeLine(NOT_PUBLISHED_AT_COMMIT, null, TrustScopeLine.Tone.CAUTION)
            ComposeFileCheck.Inconclusive -> TrustScopeLine(FILE_UNREACHABLE, null, TrustScopeLine.Tone.CAUTION)
            ComposeFileCheck.NotFetched -> TrustScopeLine(NOT_FETCHED, null, TrustScopeLine.Tone.QUIET)
            ComposeFileCheck.Checking -> null   // android-only: no approved words for "being fetched"; nothing is claimed
        }
        val liveness = if (s.undecidable) TrustScopeLine(UNDECIDABLE, UNDECIDABLE_DETAIL, TrustScopeLine.Tone.CAUTION) else null
        return RunningRowText(runningRow(s.service.ifEmpty { "(whole file)" }, s.file.orEmpty(), s.commit.orEmpty()), caption, state, liveness)
    }

    /**
     * (c)'s rows in the walk's order, each with its pair's check: the sheet's ACTIVE rows (the maintainer 2026-11-08, iOS
     * 71f83846), what the log vouches for plus this chat's path. The checks themselves read every row.
     */
    fun runningRows(scope: NearTrustScope): List<RunningRowText> {
        val checks = scope.composeFiles.associate { it.key to it.check }
        return scope.activeRunning.mapNotNull { s ->
            val key = (s.commit ?: return@mapNotNull null) to (s.file ?: return@mapNotNull null)
            runningRow(s, checks[key] ?: ComposeFileCheck.Checking)
        }
    }

    /**
     * The compose manager's row when another one runs: the declared image stays shown but unlinked — its provenance,
     * project and audit are about an image that isn't running (audit 2026-11-06; iOS TrustScope.managerRow @ 711e006b)
     * — and it wears the RUNNING digest's own review when the audits have one ([pageFor], by bare digest; iOS 359a99d1),
     * over the running digest's pill (iOS d667e6c5).
     */
    /** Another manager runs and the audits have no page for its digest: the declared one's review is never borrowed. */
    fun runningManagerUnreviewed(seat: ComposeManagerSeat?, pageFor: (String) -> String?): Boolean {
        val running = seat?.running ?: return false
        return seat.source == ComposeManagerSeat.Source.DEPLOY_LOG && running != seat.declared && pageFor(running) == null
    }

    /** The orange line under that manager's row (running-manager-unreviewed). Never read by the send gate. */
    fun unreviewedManagerLine(seat: ComposeManagerSeat?, pageFor: (String) -> String?): TrustScopeLine? =
        if (runningManagerUnreviewed(seat, pageFor)) TrustScopeLine(RUNNING_MANAGER_UNREVIEWED, null, TrustScopeLine.Tone.CAUTION) else null

    fun managerRow(image: ImageEntry, seat: ComposeManagerSeat?, pageFor: (String) -> String? = { null }): ImageEntry {
        val running = seat?.running ?: return image
        if (seat.source != ComposeManagerSeat.Source.DEPLOY_LOG || running == seat.declared) return image
        return image.copy().apply {
            links = emptyList(); auditURL = pageFor(running); projectPointer = null
            // the pill shows the image the review is about; the declared one is named in the scope line only (iOS d667e6c5)
            digest = "sha256:${running.take(12)}…"; digestFull = "sha256:$running"
        }
    }
}

/** One trust-scope line as the screen draws it: words, an optional detail, and how sure it is. */
data class TrustScopeLine(val text: String, val detail: String?, val tone: Tone) {
    enum class Tone { PROVEN, CAUTION, BROKEN, QUIET }
}
