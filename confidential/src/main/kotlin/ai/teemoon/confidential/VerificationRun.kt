// ports: teemoon/Confidential/VerificationRun.swift @ v1.0.1 (everything but SectionResult, its own file)
package ai.teemoon.confidential

/*
 * Structures the attestation data as a "verification run" that mirrors
 * near.ai's documented process (docs.near.ai/cloud/verification): one section
 * per documented verification (model / gateway / tls / chat), plus teemoon's
 * own provenance check. Each section carries a plain-language "why", the
 * technical steps with real results, a link to the exact doc page, and a
 * rolled-up result. Pure/testable — the sheet just renders it.
 */

/** Status of one step within a verification section. */
enum class RunStepStatus { PASS, FLAG, FAIL, RUNNING, PENDING }

/** A small named destination rendered under a step's detail. */
data class RunLink(val title: String, val url: String) {
    val id: String get() = "$title·$url"
}

/**
 * One technical step in a section (the real check + its result).
 *
 * [detail] is a plain sentence that wraps; literal attested tokens inside it
 * (`UpToDate`, `mr_config`, digests) are marked with backticks and rendered as
 * monospaced chips — so the eye lands on the crypto, not all-mono prose
 * (design review: proof rows must wrap, mono only the literal values).
 */
data class RunStep(
    val label: String,
    val detail: String,
    val status: RunStepStatus,
    /** Primary destination: tapping the step opens this (e.g. the exact source code the attested build ran from). */
    val url: String? = null,
    /** Secondary destinations backing the step's claim (e.g. the release page and the digest's public-log entry). */
    val links: List<RunLink> = emptyList(),
) {
    val id: String get() = "$label·$detail"
}

/** One documented verification, rendered as a `❯ verify …` section. */
data class VerificationSection(
    /** "model" | "gateway" | "tls" | "chat" | "provenance" */
    val id: String,
    /** e.g. "verify model" */
    val command: String,
    /** teemoon addition beyond near.ai's docs */
    val isExtra: Boolean,
    /** plain-language "why this matters" */
    val why: String,
    val docURL: String,
    val result: SectionResult,
    val steps: List<RunStep>,
)

private const val DOC_BASE = "https://docs.near.ai/cloud/verification"

/**
 * The whole verification run, in near.ai's documented order. Sections with no
 * data yet (e.g. gateway on a direct-only path) are dropped.
 */
val AttestationSummary.verificationRun: List<VerificationSection>
    get() = listOfNotNull(modelSection, gatewaySection, tlsSection, chatSection, provenanceSection)

/**
 * Overall run status for the console header dot — and the value that keeps
 * the hero honest: [AttestationSummary.headerSeverity] reads it, so a failed
 * or flagged section demotes the verdict above it.
 */
val AttestationSummary.runResult: SectionResult
    get() {
        val results = verificationRun.map { it.result }
        if (SectionResult.FAILED in results) return SectionResult.FAILED
        if (SectionResult.RUNNING in results) return SectionResult.RUNNING
        if (SectionResult.FLAGGED in results) return SectionResult.FLAGGED
        return if (results.isEmpty()) SectionResult.RUNNING else SectionResult.PASSED
    }

private fun rollup(steps: List<RunStep>): SectionResult {
    if (steps.any { it.status == RunStepStatus.FAIL }) return SectionResult.FAILED
    if (steps.any { it.status == RunStepStatus.RUNNING }) return SectionResult.RUNNING
    if (steps.any { it.status == RunStepStatus.FLAG }) return SectionResult.FLAGGED
    if (steps.all { it.status == RunStepStatus.PENDING }) return SectionResult.RUNNING
    return SectionResult.PASSED
}

// MARK: Sections

private val AttestationSummary.modelSection: VerificationSection?
    get() {
        if (attestation == null) return null
        val steps = ArrayList<RunStep>()
        nrasState?.let { nras ->
            steps.add(RunStep("GPU check", nrasDetail,
                if (nras == StepState.DONE) RunStepStatus.PASS else if (nras == StepState.STUCK) RunStepStatus.FAIL else RunStepStatus.RUNNING))
        }
        val dcap = dcapVerification
        if (dcap != null) {
            when (val modelDcap = dcap.model ?: dcap.gateway) {
                is DcapVerification.Verified ->
                    if (modelDcap.tcbStatus == DcapTcbStatus.UP_TO_DATE) {
                        steps.add(RunStep("hardware quote", "dcap-qvl verifies the TDX quote → `UpToDate`", RunStepStatus.PASS))
                    } else {
                        // Genuine hardware behind on Intel platform updates (outOfDate /
                        // configurationNeeded) is a note, not a warning — the quote proves
                        // real sealed silicon and the E2EE binding holds. Matches the green
                        // expert "genuine hardware" row; only `revoked` breaks (degraded via
                        // dcapVerification.hasHardFailure, a separate path).
                        steps.add(RunStep("hardware quote", "dcap-qvl → `${modelDcap.tcbStatus.rawValue}` (genuine hardware, behind on Intel platform updates)", RunStepStatus.PASS))
                    }
                is DcapVerification.Failed -> steps.add(RunStep("hardware quote", modelDcap.reason, RunStepStatus.FAIL))
                null -> Unit
            }
        } else {
            steps.add(RunStep("hardware quote", "verifying with Intel dcap-qvl…", RunStepStatus.PENDING))
        }
        e2eeKeyBound?.let { bound ->
            steps.add(RunStep("key + freshness",
                if (bound) "`report_data` binds the encryption key + nonce" else "encryption key not bound to the quote",
                if (bound) RunStepStatus.PASS else RunStepStatus.FAIL))
        }
        codeIdentityValid?.let { code ->
            steps.add(RunStep("code identity",
                if (code) "`mr_config` == `sha256(compose)`" else codeIdentityDetail,
                if (code) RunStepStatus.PASS else RunStepStatus.FAIL))
        }
        if (steps.isEmpty()) return null
        return VerificationSection(
            id = "model", command = "verify model", isExtra = false,
            why = "proves the AI model runs inside sealed hardware — so its operator can’t swap in a version that logs your chats.",
            docURL = "$DOC_BASE/model", result = rollup(steps), steps = steps)
    }

private val AttestationSummary.gatewaySection: VerificationSection?
    get() {
        // Only when the request actually goes through the gateway.
        val att = attestation ?: return null
        if (att.intelQuote.isEmpty() || att.signingAddress == null) return null
        val steps = ArrayList<RunStep>()
        val gw = dcapVerification?.gateway
        if (gw != null) {
            when (gw) {
                is DcapVerification.Verified ->
                    if (gw.tcbStatus == DcapTcbStatus.UP_TO_DATE) steps.add(RunStep("hardware quote", "dcap-qvl → `UpToDate`", RunStepStatus.PASS))
                    else steps.add(RunStep("hardware quote", "dcap-qvl → `${gw.tcbStatus.rawValue}` (genuine, behind on patches)", RunStepStatus.PASS))
                is DcapVerification.Failed -> steps.add(RunStep("hardware quote", gw.reason, RunStepStatus.FAIL))
            }
        } else {
            steps.add(RunStep("hardware quote", "verifying with Intel dcap-qvl…", RunStepStatus.PENDING))
        }
        keyBoundToHardware?.let { bound ->
            steps.add(RunStep("key + freshness",
                if (bound) "`report_data` binds the signing address + nonce" else "signing address not in `report_data`",
                if (bound) RunStepStatus.PASS else RunStepStatus.FAIL))
        }
        if (steps.isEmpty()) return null
        return VerificationSection(
            id = "gateway", command = "verify gateway", isExtra = false,
            why = "proves the entry point routing your request is itself sealed hardware, not a server that could snoop before handing off.",
            docURL = "$DOC_BASE/gateway", result = rollup(steps), steps = steps)
    }

private const val TLS_WHY =
    "proves your encrypted connection ends inside the sealed hardware — so nothing in between (network, load balancers, near.ai’s own infra) can read it."

private fun tlsSection(result: SectionResult, steps: List<RunStep>) = VerificationSection(
    id = "tls", command = "verify tls attestation", isExtra = false, why = TLS_WHY, docURL = "$DOC_BASE/tls", result = result, steps = steps)

private val AttestationSummary.tlsSection: VerificationSection?
    get() = when (val tls = tlsAttestation) {
        // pending
        null -> tlsSection(SectionResult.RUNNING,
            listOf(RunStep("checking", "verifying the connection terminates in the enclave…", RunStepStatus.PENDING)))
        TlsAttestation.NotPerformed -> null   // no direct host — not applicable, don't clutter the run
        TlsAttestation.Verified -> tlsSection(SectionResult.PASSED, listOf(
            RunStep("cert bound to hardware", "`report_data[0:32]` == `sha256(address ‖ tls fingerprint)`", RunStepStatus.PASS),
            RunStep("live cert matches", "live `SPKI` hash == attested fingerprint", RunStepStatus.PASS)))
        is TlsAttestation.Failed -> tlsSection(SectionResult.FAILED,
            listOf(RunStep("connection binding", tls.reason, RunStepStatus.FAIL)))
        // Couldn't complete (unreachable/unparseable) — advisory, not a
        // MITM verdict. Fail-closed elsewhere, but shown orange here.
        is TlsAttestation.Inconclusive -> tlsSection(SectionResult.FLAGGED,
            listOf(RunStep("connection binding", "${tls.reason} — could not complete", RunStepStatus.FLAG)))
    }

private val AttestationSummary.chatSection: VerificationSection?
    get() {
        val resp = responseSigState ?: return null
        val steps = when (resp) {
            StepState.DONE -> listOf(
                RunStep("request + response hash", "`sha256` of what was sent/received matches the signed value", RunStepStatus.PASS),
                RunStep("signature", responseSigEvidence ?: "`ecrecover(sig)` == attested signer", RunStepStatus.PASS))
            StepState.STUCK -> listOf(RunStep("signature", responseSigDetail, RunStepStatus.FAIL))
            StepState.LIVE, StepState.PENDING -> listOf(RunStep("signature", "waiting for a signed response…", RunStepStatus.PENDING))
        }
        return VerificationSection(
            id = "chat", command = "verify chat", isExtra = false,
            why = "proves each answer came from the verified model, unaltered — signed by a key only the enclave holds.",
            docURL = "$DOC_BASE/chat", result = rollup(steps), steps = steps)
    }

private val AttestationSummary.provenanceSection: VerificationSection?
    get() {
        val state = provenanceState ?: return null
        val steps: List<RunStep> = when (state) {
            StepState.DONE -> {
                val built = arrayListOf(RunStep("image provenance", provenanceEvidence ?: "images trace to github.com/nearai ✓", RunStepStatus.PASS))
                // One step per verified image: the running digest, the attested
                // version, and links to the exact source, release, and public log —
                // everything needed to compare the instance to a verifiable build.
                (imageProvenance as? ManifestProvenance.AllVerified)?.let { p ->
                    // Grouped by machine: gateway, then shared, then model —
                    // the host tag leads each detail line.
                    val ordered = p.verified.sortedWith(compareBy({ hostTag(it) }, { it.image }))
                    for (image in ordered) if (image.sourceRepo != null) built.add(imageStep(image))
                }
                engineStep?.let { built.add(it) }
                built
            }
            StepState.STUCK -> listOf(RunStep("image provenance", provenanceDetail, RunStepStatus.FAIL))
            StepState.LIVE, StepState.PENDING -> listOf(RunStep("image provenance", "checking Sigstore / Rekor…", RunStepStatus.PENDING))
        }
        return VerificationSection(
            id = "provenance", command = "verify provenance", isExtra = true,
            why = "proves the running images were built from near.ai’s public source, not modified in secret. (beyond near.ai’s docs — teemoon adds this.)",
            docURL = "https://search.sigstore.dev", result = rollup(steps), steps = steps)
    }

/**
 * "gateway", "model", or "gateway + model" for a shared image; empty when host
 * membership wasn't recorded. Sorts groups in that order.
 */
private fun hostTag(image: ImageRef): String = image.hosts.sorted().joinToString(" + ")

/**
 * The inference engine itself: built in-enclave from the compose-files repo at
 * the commit the compose-manager's action log attests. The engine image has no
 * registry digest (`:local`), so its identity is the attested YAML —
 * hash-verified against the fetched file — rather than a Sigstore record.
 */
private val AttestationSummary.engineStep: RunStep?
    get() {
        val att = attestation ?: return null
        val path = att.modelComposePath ?: return null
        val commit = att.modelComposeCommit ?: return null
        val (status, note) = when (modelLayerVerification) {
            ModelLayerVerification.VERIFIED -> RunStepStatus.PASS to "hash verified on this device"
            ModelLayerVerification.HASH_MISMATCH -> RunStepStatus.FAIL to "does NOT match the action log’s pinned hash — recipe unverified, do not trust"
            ModelLayerVerification.FETCH_FAILED -> RunStepStatus.FLAG to "source unreachable — could not verify (transient)"
            null -> RunStepStatus.PENDING to "verifying…"
        }
        val tag = att.modelComposeTag?.let { " · $it" } ?: ""
        val source = "https://github.com/$MODEL_COMPOSE_REPO/blob/$commit/$path"
        val links = arrayListOf(RunLink("source", source))
        att.modelComposeTag?.let { links.add(RunLink("release", "https://github.com/$MODEL_COMPOSE_REPO/releases/tag/$it")) }
        return RunStep(
            label = "inference engine (built in-enclave)",
            detail = "runs on model · $path$tag @ ${commit.take(7)} · $note",
            status = status, url = source, links = links)
    }

/**
 * One provenance step for a verified image: which machine runs it, the running
 * digest + attested version in the detail, with links to the exact source
 * commit, the release (tag builds only), and the digest's public Sigstore log
 * entry.
 */
private fun imageStep(image: ImageRef): RunStep {
    val repo = image.sourceRepo ?: ""
    val shortRef = image.sourceRef?.replace("refs/tags/", "")?.replace("refs/heads/", "")
    // Primary destination = the code that's running: the repo tree at the
    // attested commit, falling back to the tag's tree, then the repo root.
    val isTag = image.sourceRef?.startsWith("refs/tags/") == true
    val sourceURL = when {
        image.sourceCommit != null -> "$repo/tree/${image.sourceCommit}"
        isTag && shortRef != null -> "$repo/tree/$shortRef"
        else -> repo
    }
    val links = ArrayList<RunLink>()
    if (isTag && shortRef != null) links.add(RunLink("release", "$repo/releases/tag/$shortRef"))
    links.add(RunLink("sigstore", "https://search.sigstore.dev/?hash=${image.digest}"))
    val version = shortRef?.let { " · $it" } ?: ""
    val commit = image.sourceCommit?.let { " @ ${it.take(7)}" } ?: ""
    val host = if (hostTag(image).isEmpty()) "" else "runs on ${hostTag(image)} · "
    return RunStep(
        label = image.image,
        detail = "${host}sha256:${image.digest.take(12)}…$version$commit → ${repo.replace("https://github.com/", "")}",
        status = RunStepStatus.PASS, url = sourceURL, links = links)
}
