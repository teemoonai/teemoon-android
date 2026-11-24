// ports: teemoon/Confidential/KeyCustodyCopy.swift @ cb690537 (audit 1.1.1: an uncompared listed running file reads unfinished); @ 39ba0919 (A7 read: listed(deadlinePassed): a missing input reads unfinished past the deadline)
// ports: teemoon/Confidential/KeyCustodyCopy.swift @ 37de10d9 (1.1.1 audit A9-6: the expert tail is "the gateway doesn't hold the key.")
// ports: teemoon/Confidential/KeyCustodyCopy.swift @ 1fe69e3d (1.1.1 audit A4: listed() reads the trust scopes); @ c1eb3f4b (1.1.1 audit A7, read of a6f11ac7: listed() reads the GPU verdict); @ a6f11ac7 (five listed states, "secure enclaves"); before that @ 3c8b7021
package ai.teemoon.confidential

/**
 * What the trust screens say about the model key. near.ai's key service derives it from the app id and decides which
 * sealed chips get it (dstack kms derive_k256_key); one listed holder serves other models too. Never say the key
 * "never leaves" a chip or that only one chip holds it (KeyCustodyCopyTest).
 */
object KeyCustodyCopy {
    private val UNCOMPARED: Set<ComposeFileCheck> = setOf(ComposeFileCheck.Inconclusive, ComposeFileCheck.NotFetched, ComposeFileCheck.Unpublished, ComposeFileCheck.Checking)

    /**
     * What teemoon knows about the enclaves near.ai lists for the model (audit 1.1.1 A7): CHECKED only when the list
     * was read and every listed check passed: each entry's own checks, its DCAP verdict and the GPU verdict (A3, which
     * covers every listed enclave's evidence) and each trust scope (A4). Add any new per-enclave check here too.
     */
    enum class Listed { CHECKED, LIST_FAILED, CHECKING, UNFINISHED, FAILED }

    fun listed(record: AttestationRecord?, dcap: RecordDcapVerification?, gpu: NrasVerification?, scopes: List<NearTrustScope>?, deadlinePassed: Boolean = false): Listed {
        // Past the verdict deadline the send ask says the checks didn't finish: a missing input never reads "checking" (iOS 39ba0919).
        val waiting = if (deadlinePassed) Listed.UNFINISHED else Listed.CHECKING
        if (record == null) return waiting
        if (record.modelCandidatesListFailed) return Listed.LIST_FAILED
        if (record.candidatesBroken || dcap?.listed?.any { it.isHardFailure } == true || gpu?.isHardFailure == true
            || scopes?.any { it.severity == NearTrustScope.Severity.HARD } == true) return Listed.FAILED
        val enclaves = record.modelCandidates?.enclaves
        if (enclaves == null || dcap == null || dcap.listed.size != enclaves.size || gpu == null || scopes == null || scopes.size != enclaves.size) return waiting
        // A listed enclave's running compose file that wasn't compared (fetch failed or budget spent → Inconclusive, past the
        // pair cap → NotFetched, 404 at the pinned commit → Unpublished) is not "checked every enclave": a listed enclave has
        // no row of its own to carry the caution (audit 1.1.1, iOS cb690537). Android can publish a scope whose row is still
        // Checking (iOS awaits every fetch before it publishes one), and that is not checked either.
        val scopeUnfinished = scopes.any { scope ->
            scope.deployLogBinding == DeployLogBinding.Unchecked || scope.listedEvidenceAbsent ||
                scope.composeFiles.any { it.check in UNCOMPARED }
        }
        return if (dcap.listed.any { it.isUnchecked } || !gpu.isVerified || scopeUnfinished) Listed.UNFINISHED else Listed.CHECKED
    }

    // Approved 2026-11-19 (card key-custody-listed-states v2): everyday = expert.
    fun listedSentence(state: Listed): String = when (state) {
        Listed.CHECKED -> "teemoon checked every enclave near.ai listed."
        Listed.LIST_FAILED -> "teemoon couldn't load near.ai's list of enclaves, so it couldn't check them."
        Listed.CHECKING -> "teemoon is checking the enclaves near.ai listed…"
        Listed.UNFINISHED -> "teemoon couldn't finish checking the enclaves near.ai listed."
        Listed.FAILED -> "one of the enclaves near.ai listed failed teemoon's check."
    }

    fun listedTail(state: Listed): String = when (state) {
        Listed.CHECKED -> "teemoon checked each one near.ai listed."
        Listed.LIST_FAILED -> "teemoon couldn't load near.ai's list, so it couldn't check them."
        Listed.CHECKING -> "teemoon is checking each one near.ai listed…"
        Listed.UNFINISHED -> "teemoon couldn't finish checking the ones near.ai listed."
        Listed.FAILED -> "one near.ai listed failed teemoon's check."
    }

    fun sealedDetail(device: String, model: String, listed: Listed): String =
        "before anything leaves $device it's locked to $model's enclave key. near.ai's key service decides which secure enclaves get that key; the network and near.ai's gateway don't have it. ${listedSentence(listed)}"
    fun sealingPending(model: String): String = "locking it to $model's enclave key…"
    fun replySigned(model: String): String =
        "each answer carries a signature from $model's enclave key, so you know it came from near.ai's enclaves for the model."
    fun replySignatureFailed(model: String): String =
        "one answer didn't carry a valid signature from $model's enclave key."
    fun keyInQuoteExpert(model: String, listed: Listed): String =
        "that public key sits in `report_data` of $model's hardware quote. near.ai's key service derives it for this app and decides which enclaves get it; teemoon can't check that decision. ${listedSentence(listed)} the gateway doesn't hold the key."
    fun keyHeldEveryday(listed: Listed): String =
        "the key to open your chat is held in near.ai's secure enclaves — near.ai's key service decides which ones — not by the network or near.ai's gateway. ${listedTail(listed)}"
    const val replySignedExpert = "each reply is signed by the enclave's key — recover any message to check it yourself."

    // Approved 2026-11-19 (card key-custody-copy-2).
    const val keyHeldEverydayTitle = "near.ai's secure enclaves hold the key"
    // Approved 2026-11-19 (card key-custody-copy-2). iOS shows it on the ladder's sample chain
    // (ChainNode.sample, Xcode previews); Android has no sample chain, so nothing renders it yet.
    const val keyNotHeldRow = "the network and gateway don't hold the key"
}
