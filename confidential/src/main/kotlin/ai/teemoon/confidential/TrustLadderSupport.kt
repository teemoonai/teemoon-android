// ports: teemoon/Views/Chat/TrustLadderSupport.swift @ v1.0.1 (the value types and pure rules only —
//        EnclaveGroup, ImageEntry, ProjectLink, NodeFinding, severityRank, splitNodeFindings, isRecipeImage,
//        partitionRecipeRows. The SwiftUI rows and cards are Compose's to rebuild; URLs are Strings.)
package ai.teemoon.confidential

/** One enclave's code: the quote/mr_config that measures it, and its images. */
data class EnclaveGroup(
    val id: String,
    val role: String,
    val primary: Boolean,
    val binding: String,
    /** Deployment-recency caption from the signed action log (model enclave only). null = no timestamps. */
    val deployment: String? = null,
    val mrConfig: String?,
    val quoteVerified: Boolean?,
    val images: List<ImageEntry>,
    /** The one caption stating the counterpart of the plaintext-role tags. Only the model-enclave group carries it. */
    val plaintextCaption: String? = null,
    /** The allowed_envs invariant from the OUTER measured compose. null = not derivable. */
    val measurementCaption: String? = null,
    /** Digest-gated audit of this enclave's measured app_compose. null when no published assessment covers it. */
    val configAuditURL: String? = null,
    /** Recipe-card metadata — model enclave only (the enclave with a signed action log). */
    val recipe: RecipeCard? = null,
    /** The per-node plaintext-egress rollup. Model enclave only; empty = no machine-readable findings indexed (NOT "nothing found"). */
    val egressFindings: List<NodeFinding> = emptyList(),
) {
    /** Header evidence for the recipe card — the exact inner compose the signed action log pinned, and whether it checked out. */
    data class RecipeCard(
        val fileSHA: String?,
        val yamlLink: RunLink?,
        val verification: ModelLayerVerification?,
        val auditURL: String? = null,
    )
}

/**
 * Classifies a running image as *recipe* (pinned by the model enclave's signed
 * action log — belongs inside the recipe card) vs *measured/other* (the
 * hardware-measured harness sidecars + guest OS, which sit above the seam).
 *
 * - The in-enclave engine build is ALWAYS recipe: the inner compose is
 *   literally its build recipe.
 * - The guest OS is NEVER recipe: it's the measured substrate the enclave boots on.
 * - Everything else is recipe exactly when its DIGEST is declared in the
 *   hash-verified inner compose. NEVER a name substring — near.ai's compose is
 *   saturated with telemetry LABELS on the model/proxy services, so a substring
 *   match falsely pulled the otel collector into the recipe card while datadog
 *   stayed out (observed live on Qwen-3.6).
 * - A DIGESTLESS entry is recipe when its full tag ref is DECLARED as a service
 *   `image:` in that document.
 */
fun isRecipeImage(digestFull: String?, tagRef: String? = null, isLocalEngine: Boolean, isGuestOS: Boolean, innerComposeYaml: String?): Boolean {
    if (isLocalEngine) return true   // the :local engine has no registry digest; it IS the recipe
    if (isGuestOS) return false      // measured at boot, never action-log-pinned
    val yaml = innerComposeYaml ?: return false
    if (!digestFull.isNullOrEmpty()) {
        val bare = digestFull.removePrefix("sha256:")
        return ProvenanceParsing.imageRefs(yaml).any { it.digest.equals(bare, ignoreCase = true) }
    }
    if (!tagRef.isNullOrEmpty()) return tagRef in ProvenanceParsing.tagPinnedImageRefs(yaml)
    return false
}

/**
 * Partitions the recipe card's rows by DATA PATH — never by name: handlers are
 * the rows on the message's path; telemetry is EVERYTHING else — including rows
 * with no capability tag (an untagged telemetry row is a signal-detection gap
 * that must still render; suppressing it would read as "safe"). Returns null
 * when no handler was identified: a toucher-analysis parse failure, so the
 * caller falls back to the flat list rather than dump the engine into
 * "telemetry sidecars".
 */
fun <T> partitionRecipeRows(items: List<T>, handlesMessage: (T) -> Boolean): Pair<List<T>, List<T>>? {
    val handlers = items.filter(handlesMessage)
    if (handlers.isEmpty()) return null
    return handlers to items.filterNot(handlesMessage)
}

/** One machine-read finding from a published review, resolved against the page it came from — the row unit of the per-node egress rollup. */
data class NodeFinding(
    val id: String,
    /** "critical" | "high" | "medium" | "low" | "info". Unknown values keep their text and sink to the bottom — never dropped. */
    val severity: String,
    /** "on" | "off" | "armed" | null — the reviewer's deployed-state. */
    val deployed: String?,
    val qualifier: String?,
    val title: String,
    /** Short provenance label ("sglang @8ece90ad" / "recipe" / "deployment config" / "guest OS"). */
    val source: String,
    /** The finding's own evidence section: `<page>#<anchor>`. */
    val url: String,
)

/** Severity order for the rollup (critical first). Unknown severities rank after info — visible at the bottom, never silently dropped. */
fun severityRank(severity: String): Int = when (severity) {
    "critical" -> 0; "high" -> 1; "medium" -> 2; "low" -> 3; "info" -> 4; else -> 5
}

data class NodeFindingSplit(val live: List<NodeFinding>, val latent: List<NodeFinding>, val info: List<NodeFinding>)

/**
 * Splits a node's findings for display: live = the reviewer marked the finding
 * ON at the deployed config; latent = every other non-info finding; info
 * collapses behind a count. Severity-sorted, stable within a band.
 */
fun splitNodeFindings(findings: List<NodeFinding>): NodeFindingSplit {
    val sorted = findings.sortedBy { severityRank(it.severity) }
    return NodeFindingSplit(
        live = sorted.filter { it.deployed == "on" && it.severity != "info" },
        latent = sorted.filter { it.deployed != "on" && it.severity != "info" },
        info = sorted.filter { it.severity == "info" })
}

/**
 * One image in an enclave's compose. [kind] distinguishes a pulled registry
 * image (has provenance) from one built in-enclave (`:local`) or a third-party
 * sidecar (digest-pinned only) — each honestly shows only what it can prove.
 * Mutable where iOS's struct is `var`: EnclaveGrouping fills the tiers in.
 */
data class ImageEntry(
    val id: String,
    val name: String,
    val kind: Kind,
    var digest: String? = null,
    /** Full sha256 for the copy affordance (display stays truncated). */
    var digestFull: String? = null,
    /** The tag of a TAG_PINNED image — plain text, never a value pill: a tag is a name, not a content hash. */
    var tag: String? = null,
    var note: String? = null,
    var links: List<RunLink> = emptyList(),
    /** Non-null when this image sees the user's message decrypted — a soft role capsule, working as intended. (tier 1) */
    var plaintextRole: PlaintextExposure.Role? = null,
    /** Non-null when this image can REACH plaintext by attested privilege though it never sits on the data path. Never both this and [plaintextRole]. (tiers 2 & 3) */
    var capability: PlaintextExposure.Capability? = null,
    /** Digest/manifest-gated source-audit page for this exact build. null = no published assessment (never a fabricated URL). */
    var auditURL: String? = null,
    /** A CAVEATED upstream-project pointer for a third-party sidecar — NOT a peer of the engine's verified source link. */
    var projectPointer: ProjectLink? = null,
    /** Measured-configuration lines for a plaintext toucher. Empty for non-touchers. */
    var configLines: List<MeasuredConfig.Line> = emptyList(),
    /** Whether this image is part of the action-log-pinned *recipe* vs the hardware-measured harness / guest OS. */
    var isRecipe: Boolean = false,
) {
    enum class Kind(val label: String) {
        REGISTRY("registry image"),
        LOCAL("built in-enclave"),
        THIRD_PARTY("third-party · digest-pinned"),
        UNVERIFIED("unverified"),
        GUEST_OS("guest OS"),
        /** ORANGE (cannot-check), never red: a tag names a build but pins no bytes — structurally uncheckable from here. */
        TAG_PINNED("pinned by tag · can drift"),
    }
}

/** A caveated upstream-project pointer for a third-party sidecar — the project it comes from plus the audit's honest review-status. */
data class ProjectLink(val name: String, val repo: String, val status: String, val url: String)
