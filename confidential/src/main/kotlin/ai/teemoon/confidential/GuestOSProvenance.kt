// ports: teemoon/Confidential/GuestOSProvenance.swift @ v1.0.1
package ai.teemoon.confidential

/**
 * Maps a model node's measured guest-OS hash (`os_image_hash`) to the exact
 * published `nearai/private-ml-sdk` release it was proven to come from.
 *
 * Unlike container images — whose digest→commit provenance the app resolves
 * live via GitHub build attestations — the guest OS has no such attestation:
 * `os_image_hash` is a boot measurement, not an OCI digest. The only way to
 * tie it to source is the reproduce-and-match check: download a release, read
 * the `digest.txt` it ships (which IS the `os_image_hash`), and compare.
 *
 * So this is a **small, verified, fail-closed** table: an entry exists only
 * for a hash whose match to a published release was actually confirmed. An
 * unknown hash yields no source link (never a looks-like-proof link to an
 * unpinned repo). When near.ai ships a new guest image, the row shows the new
 * hash with no source link until someone re-runs the match and adds it here.
 */
object GuestOSProvenance {

    /** A published private-ml-sdk release a measured `os_image_hash` maps to. */
    data class Source(
        /** Release tag, e.g. `v0.5.5`. */
        val tag: String,
        /** Full git revision the release was built from (`metadata.json` `git_revision` / `reproduce.sh` checkout). */
        val commit: String,
        /** The release page — carries the prebuilt image (whose `digest.txt` equals this hash), `reproduce.sh`, and the commit reference. */
        val releaseURL: String,
    )

    /**
     * Verified `os_image_hash` (bare sha256 hex) → published release.
     *
     * Each entry was confirmed by downloading the release's prod image and
     * matching its `digest.txt` to this exact hash (2026-07-20). Add a row
     * only after actually running that match — never by assumption.
     */
    val bySHA: Map<String, Source> = mapOf(
        // GLM-5.1 fleet model node, live 2026-07-20.
        "9b69bb1698bacbb6985409a2c272bcb892e09cdcea63d5399c6768b67d3ff677" to
            Source(tag = "v0.5.5",
                commit = "25c25025c556ab2f797eeda3bab433f38a8ffb7a",
                releaseURL = "https://github.com/nearai/private-ml-sdk/releases/tag/v0.5.5"),
    )

    /** The verified source for a measured guest-OS hash, or null (fail-closed). Accepts a bare hex or a `sha256:`-prefixed value, case-insensitively. */
    fun source(osImageHash: String): Source? =
        bySHA[osImageHash.replace("sha256:", "", ignoreCase = true).trim().lowercase()]
}
