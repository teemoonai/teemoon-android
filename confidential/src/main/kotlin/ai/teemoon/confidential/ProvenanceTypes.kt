// ports: teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (ModelLayerVerification and the nested
//        ImageRef / Failure / ManifestProvenance value types only, hoisted to top level; the service
//        that fetches GitHub attestations is ProvenanceService.kt)
package ai.teemoon.confidential

/** The compose-files repo the engine layer is built from (ProvenanceService.modelComposeRepo). */
const val MODEL_COMPOSE_REPO = "nearai/cvm-compose-files"

/**
 * Outcome of fetching + hash-checking the model-layer (inner) compose against
 * the action log's pinned `file_sha256`. The three cases are deliberately
 * distinct: a tamper ([HashMismatch]) must fail loud, a transient network
 * failure ([FetchFailed]) must not.
 */
sealed interface ModelLayerFetch {
    /** Hash matched — carries the verified YAML text. */
    data class Verified(val yaml: String) : ModelLayerFetch
    /** The fetched file's hash does NOT match the pinned value: an adversarial integrity break. */
    data class HashMismatch(val expected: String, val got: String) : ModelLayerFetch
    /** Could not fetch (network/HTTP error) — transient, not evidence of tamper. */
    data object FetchFailed : ModelLayerFetch
}

/**
 * The display-level verdict of the model-layer hash check, stored on the
 * session and read by the attestation summary (without the YAML payload).
 */
enum class ModelLayerVerification {
    /** Recipe hash matched the action-log pin, verified on this device. */
    VERIFIED,
    /** Recipe hash did NOT match — the running config is unverified. Fail-closed. */
    HASH_MISMATCH,
    /** Could not reach the source to check — transient, advisory only. */
    FETCH_FAILED,
}

/** One image reference and its digest, parsed from a compose manifest. */
data class ImageRef(
    /** e.g. "nearaidev/cloud-api" */
    val image: String,
    /** hex sha256, no prefix */
    val digest: String,
    /**
     * The GitHub repository the image's Sigstore certificate names as its
     * build source (e.g. "https://github.com/nearai/private-ml-sdk").
     * Populated by verification; null before/without it.
     */
    val sourceRepo: String? = null,
    /** The git ref the attested build ran on (e.g. "refs/tags/v0.5.1"). */
    val sourceRef: String? = null,
    /** The exact source commit the attested build resolved. */
    val sourceCommit: String? = null,
    /** Which attested machines run this image ("gateway" / "model"). */
    val hosts: List<String> = emptyList(),
)

/** One image that failed provenance, with why. */
data class ProvenanceFailure(val ref: ImageRef, val reason: ImageProvenance)

/**
 * The provenance outcome for a whole manifest.
 *
 * Only near.ai-built images (`nearaidev/…` / `nearai/…`) are held to the
 * provenance requirement — that is the check's claim. Third-party sidecars in
 * the compose (e.g. `datadog/agent`, `alpine`) have no near.ai attestation to
 * verify; they are digest-pinned by the attested manifest itself (which code
 * identity binds to MRCONFIGID), so they are reported distinctly rather than
 * counted as failures. near.ai's own verifier makes the same distinction.
 */
sealed interface ManifestProvenance {
    /** Every near.ai image digest verified against a pinned workflow. */
    data class AllVerified(val verified: List<ImageRef>, val thirdParty: List<ImageRef>) : ManifestProvenance
    /** At least one near.ai image is unverified (fail-closed). */
    data class Incomplete(val verified: List<ImageRef>, val failures: List<ProvenanceFailure>, val thirdParty: List<ImageRef>) : ManifestProvenance

    val isFullyVerified: Boolean get() = this is AllVerified

    /**
     * True when the only thing standing between this result and [AllVerified]
     * is transient fetch trouble (rate limits, network) — no image produced
     * actual negative evidence. Callers should treat such a result as
     * still-pending rather than degraded: fail-closed on evidence, in-flight
     * on no evidence.
     */
    val isInconclusive: Boolean
        get() {
            val failures = (this as? Incomplete)?.failures?.takeIf { it.isNotEmpty() } ?: return false
            return failures.all { (it.reason as? ImageProvenance.Unverified)?.reason is ImageProvenance.Reason.FetchTransient }
        }

    /**
     * True when every failure is a definitive unpublished attestation (clean
     * GitHub 404). Not a bad signature, wrong identity, or digest mismatch —
     * and not an empty failure list. The send gate must not treat this as an
     * encryption failure; the chip must not say verified.
     */
    val isUnpublishedOnly: Boolean
        get() {
            val failures = (this as? Incomplete)?.failures?.takeIf { it.isNotEmpty() } ?: return false
            return failures.all { (it.reason as? ImageProvenance.Unverified)?.reason is ImageProvenance.Reason.FetchFailed }
        }
}
