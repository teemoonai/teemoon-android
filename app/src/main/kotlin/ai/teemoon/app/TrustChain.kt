// ports: teemoon/Views/Chat/TrustLadderView+Hero.swift @ e9e2e747 (design review: the expert bound node takes TrustVerdict.sealedStatus)
// ports: teemoon/Views/Chat/TrustLadderView+Hero.swift @ 37de10d9 (1.1.1 audit A9: the ladder step 'sealed to <model>'s enclave key')
// ports: teemoon/Views/Chat/TrustLadder.swift @ v1.0.4 (ChainNode, TrustRung), teemoon/Views/Chat/TrustLadderView+Hero.swift @ v1.0.4
//        (everydayChain, expertChain, dimDownstream, metaLine) — the ladder's chain as data, pure over the session's record.
// ports: teemoon/Views/Chat/TrustLadderView+Hero.swift @ a6f11ac7 (1.1.1 audit A7: the bound node reads summary.listedEnclaves); TrustLadder.swift, TrustLadderView+Hero.swift @ 5b56b7b4 (key-custody copy: KeyCustodyCopy.kt)
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.AttestationState
import ai.teemoon.confidential.AttestationSummary
import ai.teemoon.confidential.KeyCustodyCopy
import ai.teemoon.confidential.ModelArtifact
import ai.teemoon.confidential.RunLink
import ai.teemoon.confidential.StepState
import ai.teemoon.confidential.PROVENANCE_UNCHECKED_STEP
import ai.teemoon.confidential.TrustClaim
import ai.teemoon.confidential.TrustVerdict
import ai.teemoon.confidential.toHex

/** One node of the chain rail. Everyday copy is optional; the expert copy is the node's own. */
data class ChainNode(
    val id: String,
    val status: Status,
    val title: String,
    val detail: String,
    val value: String? = null,
    val copyValue: String? = null,
    val links: List<RunLink> = emptyList(),
    val jumpTitle: String? = null,
    val jumpTarget: String? = null,
    val jumpAtEveryday: Boolean = false,
    val dimmed: Boolean = false,
    val everydayTitle: String? = null,
    val everydayDetail: String? = null,
) {
    enum class Status { ORIGIN, OK, ALERT, INFO, FAIL, PENDING }
}

object TrustChain {
    /** The everyday chain: the verdict's claims, one node each. */
    fun everyday(claims: List<TrustClaim>, halted: Boolean = false): List<ChainNode> {
        val nodes = dimDownstream(claims.map { c ->
            ChainNode(id = c.id, status = status(c.status), title = c.title, detail = c.detail, jumpTitle = c.jumpTitle, jumpTarget = c.jumpTarget, jumpAtEveryday = c.jumpAtEveryday)
        })
        // trust-blocked-chain-and-gateway-row (a), the maintainer 2026-11-06: nothing green under a block or a stale record. A
        // failing step already greys what follows it; a block whose cause isn't on this chain greys everything after
        // the message.
        if (!halted || nodes.any { it.status == ChainNode.Status.FAIL }) return nodes
        return nodes.mapIndexed { i, n -> if (i > 0) n.copy(dimmed = true) else n }
    }

    private fun status(s: TrustClaim.Status) = when (s) {
        TrustClaim.Status.ORIGIN -> ChainNode.Status.ORIGIN; TrustClaim.Status.OK -> ChainNode.Status.OK; TrustClaim.Status.ALERT -> ChainNode.Status.ALERT
        TrustClaim.Status.INFO -> ChainNode.Status.INFO; TrustClaim.Status.FAIL -> ChainNode.Status.FAIL; TrustClaim.Status.PENDING -> ChainNode.Status.PENDING
    }

    /**
     * The expert chain (TrustLadderView+Hero.expertChain): message → encryption target → key bound in the quote →
     * model-layer YAML (+ drift) → provenance → response signatures. Each node carries its everyday copy too, so the
     * rail can switch rungs without re-deriving anything.
     */
    fun expert(
        attestation: AttestationRecord?, state: AttestationState, summary: AttestationSummary, artifact: ModelArtifact?,
        modelName: String, deviceBoundary: String = "your phone", deviceSubject: String = "your phone",
    ): List<ChainNode> {
        val verifying = state == AttestationState.VERIFYING
        val nodes = ArrayList<ChainNode>()
        nodes += ChainNode("msg", ChainNode.Status.ORIGIN, "your message", "plaintext, on your device", everydayTitle = "your message", everydayDetail = "readable only here.")
        val key = attestation?.modelEd25519PubKey
        if (key != null) {
            val hex = key.toHex()
            // the seal is not proven until DCAP and NVIDIA answer (trust-pending-state Q4, iOS hardwareChecksPending)
            nodes += ChainNode("target", if (summary.hardwareChecksPending) ChainNode.Status.PENDING else ChainNode.Status.OK, "encryption target · Ed25519 public key",
                "your device seals this chat to $modelName's public key before it ever leaves $deviceBoundary.",
                value = "ed25519:${hex.take(12)}…", copyValue = hex,
                everydayTitle = "$deviceSubject locks this chat to $modelName", everydayDetail = "before anything leaves your device, it's sealed to $modelName's enclave key.")
        } else {
            nodes += ChainNode("target", if (verifying) ChainNode.Status.PENDING else ChainNode.Status.FAIL, "encryption target · Ed25519 public key",
                if (verifying) "fetching the model's public key…" else "no Ed25519 key — the model's public key is unavailable, so this chat can't be sealed to it.",
                everydayTitle = if (verifying) "locking this chat…" else "couldn't lock this chat",
                everydayDetail = if (verifying) "getting $modelName's key…" else "$modelName's key wasn't available, so nothing was sealed to it.")
        }
        nodes += when (summary.e2eeKeyBound) {
            true -> ChainNode("bound", status(TrustVerdict.sealedStatus(summary.hardwareChecksPending, summary.listedEnclaves)), "key bound in the model's TDX quote",
                KeyCustodyCopy.keyInQuoteExpert(modelName, summary.listedEnclaves),
                everydayTitle = KeyCustodyCopy.keyHeldEverydayTitle, everydayDetail = KeyCustodyCopy.keyHeldEveryday(summary.listedEnclaves))
            // the key IS in report_data; the quote that carries it didn't verify (trust-quote-failed, the maintainer 2026-11-06)
            false -> if (summary.keyInUnverifiedQuote) ChainNode("bound", ChainNode.Status.FAIL, "key in report_data, but the quote didn't verify",
                "the public key matches report_data, but the quote's signature or certificate chain failed, so nothing proves this key lives inside the enclave.",
                everydayTitle = "couldn't lock this chat", everydayDetail = "$deviceSubject couldn't confirm $modelName holds the key — so nothing has been sent.")
            else ChainNode("bound", ChainNode.Status.FAIL, "key binding · could not be established",
                "the encryption key was NOT found in $modelName's `report_data` — end-to-end encryption isn't established.",
                everydayTitle = "couldn't lock this chat", everydayDetail = "$deviceSubject couldn't confirm $modelName holds the key — so nothing has been sent.")
            null -> ChainNode("bound", ChainNode.Status.PENDING, "key bound in the model's TDX quote", "checking the model quote…",
                everydayTitle = "checking the lock…", everydayDetail = "confirming $modelName holds the key…")
        }
        artifact?.let { art ->
            val rev = art.revision?.let { " @ ${it.take(7)}" } ?: ""
            nodes += ChainNode("yaml", ChainNode.Status.OK, "model-layer YAML pins the image",
                "the inner compose pins the weights to an immutable revision — the quant tag (${art.quant ?: "unspecified"}) is read from the model path, never the served alias.",
                value = "${art.modelPath}$rev", copyValue = art.revision?.let { "${art.modelPath}@$it" } ?: art.modelPath,
                everydayTitle = "it's the pinned, published $modelName build", everydayDetail = "the version is pinned to an immutable release and checked — a swap would show up as a provenance mismatch.")
            art.driftNote?.let { note ->
                nodes += ChainNode("drift", ChainNode.Status.INFO, "the served name differs from the pinned build", note,
                    everydayTitle = "it's more compressed than the name says", everydayDetail = "answers may be slightly lower quality than the full-size model — your privacy isn't affected.")
            }
        }
        nodes += when (summary.provenanceState) {
            StepState.DONE -> ChainNode("source", ChainNode.Status.OK, "the running code traces to public source",
                summary.provenanceEvidence ?: "every near.ai image running the model verified against its published build.", jumpTitle = "known code",
                everydayTitle = "the code is public and checked", everydayDetail = "every piece of software running the model is open source, and teemoon checked it matches.")
            // GitHub didn't answer: said, not a spinner (trust-provenance-unchecked)
            StepState.UNCHECKED -> ChainNode("source", ChainNode.Status.INFO, "checking the running code against public source", summary.provenanceDetail,
                everydayTitle = PROVENANCE_UNCHECKED_STEP, everydayDetail = "")
            StepState.STUCK -> ChainNode("source", ChainNode.Status.FAIL, "image provenance · unverified", summary.provenanceDetail,
                everydayTitle = "couldn't confirm the code", everydayDetail = "teemoon couldn't confirm the running software matches the published code.")
            else -> ChainNode("source", ChainNode.Status.PENDING, "checking the running code against public source", "checking Sigstore / public attestations…",
                everydayTitle = "checking the code against its published source", everydayDetail = "checking the software against its published source…")
        }
        val addr = attestation?.signingAddress
        if (addr != null) {
            nodes += if (summary.responseSigState == StepState.STUCK) ChainNode("signed", ChainNode.Status.FAIL, "a reply's signature didn't match", summary.responseSigDetail,
                value = "${addr.take(6)}…${addr.takeLast(4)}", copyValue = addr)
            else ChainNode("signed", if (verifying) ChainNode.Status.PENDING else ChainNode.Status.OK,
                if (verifying) "checking each reply's signature" else "every reply comes back signed",
                if (verifying) "recovering each reply to the enclave signing key…" else KeyCustodyCopy.replySignedExpert,
                value = "${addr.take(6)}…${addr.takeLast(4)}", copyValue = addr)
        } else if (verifying) {
            nodes += ChainNode("signed", ChainNode.Status.PENDING, "checking each reply's signature", "recovering each reply to the enclave signing key…")
        }
        return dimDownstream(nodes)
    }

    /** Everything after the first break is dimmed: a broken link makes the rest of the chain moot. */
    fun dimDownstream(nodes: List<ChainNode>): List<ChainNode> {
        val breakAt = nodes.indexOfFirst { it.status == ChainNode.Status.FAIL }
        if (breakAt < 0) return nodes
        return nodes.mapIndexed { i, n -> if (i > breakAt) n.copy(dimmed = true) else n }
    }

    /** TrustLadderView+Hero.metaLine: the stamp, then the nonce's ends. */
    fun metaLine(state: AttestationState, isPaused: Boolean, timestampText: String, attestation: AttestationRecord?): String {
        if (state == AttestationState.VERIFYING) return ""
        val parts = ArrayList<String>()
        parts += if (isPaused) "could not verify" else timestampText
        val nonce = attestation?.modelNonce ?: attestation?.gatewayNonce
        if (nonce != null && nonce.length >= 8) parts += "nonce ${nonce.take(4)}…${nonce.takeLast(4)}"
        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
