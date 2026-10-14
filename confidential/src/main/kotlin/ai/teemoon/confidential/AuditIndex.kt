// ports: teemoon/Confidential/AuditIndex.swift @ v1.0.4 (VerdictClass stays the top-level AuditVerdictClass; refresh()
//        revalidates with the saved ETag, and a snapshot younger than the TTL answers a cold launch)
package ai.teemoon.confidential

import ai.teemoon.providers.TeemoonHttp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Digest-gated links to the published source audits (teemoonai/audits). The
 * repo's path layout is a frozen contract: every code-addressed path is a
 * pure function of ATTESTED fields (image ref + digest, manifest path +
 * file_sha256, measured compose_hash) — see the repo README's path spec.
 * Links are shown ONLY when the exact running identity appears in the fetched
 * index.json, so an audit link can never overclaim: no entry in the index, no
 * link.
 *
 * A main-scope state holder. [fixedIndex] is the test seam (skips network and
 * persistence); [fetch] and [store] are the network and persistence seams.
 */
class AuditIndex(
    fixedIndex: Index? = null,
    private val fetch: suspend (ifNoneMatch: String?) -> Reply? = { liveFetch(it) },
    private val store: Store? = null,
    /** Previews and capture harnesses must not fetch. */
    private val skipLive: Boolean = false,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Where the fetched index and its fetch time persist between launches (UserDefaults on iOS). */
    interface Store {
        fun loadIndex(): ByteArray?
        fun loadFetchedAtMs(): Long?
        fun save(index: ByteArray, fetchedAtMs: Long)
        /** The ETag the saved index was served with, for If-None-Match (385948e). */
        fun loadETag(): String? = null
        fun saveETag(etag: String?) {}
        /** A 304: the saved bytes are still current as of [fetchedAtMs]. */
        fun touch(fetchedAtMs: Long) {}
    }

    /** One answer from the index URL: status, body (empty on a 304), and its ETag. */
    class Reply(val status: Int, val body: ByteArray, val etag: String? = null)

    /**
     * The upstream source a third-party image build was compiled from — the
     * exact repo+commit the RUNNING digest maps to (from its audit). Lets the app
     * pin a source link to the version actually on the server, even though
     * third-party images carry no near.ai build attestation to derive it from.
     */
    @Serializable
    data class SourceRef(val repo: String, val commit: String)

    /**
     * A CAVEATED upstream pointer for a third-party image — deliberately NOT a
     * [SourceRef]. A SourceRef is a VERIFIED digest→commit pin. A Project is the
     * opposite — a third-party image with NO digest→commit binding and, per its
     * audit, an un-reviewed (or proprietary, or tag-pinned) source. It carries
     * its own [status] caveat so the row can never read as verified. Keyed by
     * image ref (not digest), so it works for a tag-pinned image too.
     */
    @Serializable
    data class Project(val name: String, val repo: String, val status: String)

    /** One machine-read finding heading from a published review page. All fields are the reviewer's own words, extracted (not summarized). */
    @Serializable
    data class Finding(
        /** "critical" | "high" | "medium" | "low" | "info" */
        val severity: String,
        /** The reviewer's deployed-state: "on", "off", "armed" — null when the heading carried none. */
        val deployed: String? = null,
        /** The raw heading qualifier, e.g. "deployed: ON, unconditional". */
        val qualifier: String? = null,
        val title: String,
        /** GitHub anchor of the finding's own heading — deep-links the row to its evidence. */
        val anchor: String,
    )

    @Serializable
    data class Index(
        val schema: Int,
        /** normalized image ref → assessed digests (hex, no prefix) */
        val images: Map<String, List<String>>,
        /** normalized image ref → digest → upstream source pin. Optional so an older index.json still decodes. */
        val sources: Map<String, Map<String, SourceRef>>? = null,
        /** normalized image ref → caveated upstream project pointer. Optional. */
        val projects: Map<String, Project>? = null,
        /** normalized image ref → assessed TAGS, for images pinned by tag. Optional. */
        val tagAudits: Map<String, List<String>>? = null,
        /** "<owner>/<repo>/<path>.yaml" → assessed file_sha256 values */
        val manifests: Map<String, List<String>>,
        /** assessed measured compose_hash values */
        val measured: List<String>,
        /** assessed dstack guest-OS image hashes. Optional → no OS link. */
        val os: List<String>? = null,
        /**
         * repo-relative page path → that page's own `## verdict:` line, verbatim.
         * A link on its own cannot say what a review FOUND; rendering every
         * review under one fixed reassuring subtitle tells a user on a build with
         * a live CRITICAL exactly what it tells a user on a clean one. Never
         * synthesized here — the string is the reviewer's own wording.
         */
        val verdicts: Map<String, String>? = null,
        /** ISO date the index was last regenerated upstream — the staleness line. */
        val updated: String? = null,
        /** page path → verdict class token, derived mechanically by the repo's indexer. An unlisted page renders neutral. */
        val verdictClass: Map<String, String>? = null,
        /** page path → the page's own finding headings, machine-read (never synthesized). */
        val findings: Map<String, List<Finding>>? = null,
    )

    /** null until an index has loaded (this session or from the persisted snapshot); all link accessors return null until then — fail closed. */
    var index: Index? = fixedIndex
        private set(value) { field = value; _revision.value++ }

    /**
     * Bumps whenever [index] changes. iOS's AuditIndex is @Observable, so the ladder re-renders when the fetch lands;
     * a Compose snapshot built once from a plain field would keep the empty index it was built from — which is how the
     * Android sheet showed no miniaudit at all (2026-09-23): the fetch was never started, and nothing would have redrawn.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** When the CONTENT currently in [index] was fetched from the network. null = unknown (treated as stale, never as fresh). */
    var loadedAtMs: Long? = null
        private set

    private var lastLoadMs: Long? = if (fixedIndex != null) Long.MAX_VALUE else null
    private var loadJob: Job? = null

    /**
     * Loads/refreshes the index (bounded, failure-tolerant). Persisted snapshot
     * seeds offline launches; a fetch failure keeps whatever we had.
     */
    fun loadIfNeeded(scope: CoroutineScope) {
        if (skipLive) return
        val last = lastLoadMs
        if (last != null && nowMs() - last < TTL_MS && index != null) return
        if (index == null) {
            store?.loadIndex()?.let { saved -> decodeIndex(saved)?.let { index = it; loadedAtMs = store.loadFetchedAtMs() } }
            // A snapshot younger than the TTL is this launch's index; without
            // this, every cold launch refetched the whole file (~375 KB each
            // time the trust sheet opened — iOS 385948e).
            if (index != null) {
                lastLoadMs = loadedAtMs
                loadedAtMs?.let { if (nowMs() - it < TTL_MS) return }
            }
        }
        if (loadJob != null) return
        loadJob = scope.launch {
            try { refresh() } finally { loadJob = null }
        }
    }

    /**
     * One fetch. Nothing in the app caches HTTP (OkHttp has no cache here, as
     * iOS's shared URL cache stores nothing), so revalidation is done here: the
     * ETag is kept beside the snapshot and a 304 costs no body.
     */
    suspend fun refresh() {
        val etag = if (index != null) store?.loadETag() else null
        val reply = runCatching { fetch(etag) }.getOrNull() ?: return
        val now = nowMs()
        if (reply.status == 304 && index != null) {
            lastLoadMs = now; loadedAtMs = now
            store?.touch(now)
            return
        }
        if (reply.status != 200) return
        val parsed = decodeIndex(reply.body) ?: return
        index = parsed
        lastLoadMs = now
        loadedAtMs = now
        store?.save(reply.body, now)
        store?.saveETag(reply.etag)
    }

    // MARK: gated link accessors (null = not assessed → no link, never overclaim)

    /** Audit page for an image build. [image] as it appears in the attested compose (tag allowed); [digest] hex with or without "sha256:". */
    fun imageAuditURL(image: String, digest: String): String? {
        val index = index ?: return null
        val ref = normalizedRef(image)
        val hex = bareDigest(digest)
        if (index.images[ref]?.contains(hex) != true) return null
        return "$WEB_BASE/images/$ref/sha256-$hex.md"
    }

    /**
     * [imageAuditURL] with the ENCLAVE SCOPE GATE in front of it.
     *
     * The teemoonai/audits review covers an image as deployed in the MODEL
     * compose, which is the plaintext-side, in-scope enclave. The identical
     * digest also runs gateway-side over ciphertext (datadog/agent and
     * otel-collector-contrib do this today), and that deployment is NOT what
     * the review assessed. Surfacing the same link there would overclaim.
     * [inScope] is a parameter rather than ambient state so the gate stays a
     * pure function — the whole reason it is testable.
     */
    fun scopedImageAuditURL(inScope: Boolean, image: String, digestFull: String?): String? {
        if (!inScope || digestFull == null) return null
        return imageAuditURL(image, digestFull)
    }

    data class SourceLink(val title: String, val url: String)

    /**
     * Pinned upstream source for a running image digest — repo + commit the
     * build was compiled from (per its audit). Title names the upstream repo so a
     * third-party engine reads honestly. null when not recorded → no link, never a guess.
     */
    fun imageSourceLink(image: String, digest: String): SourceLink? {
        val index = index ?: return null
        val s = index.sources?.get(normalizedRef(image))?.get(bareDigest(digest)) ?: return null
        return SourceLink("${s.repo} @ ${s.commit.take(7)}", "https://github.com/${s.repo}/tree/${s.commit}")
    }

    data class ProjectPointer(val name: String, val repo: String, val status: String, val url: String)

    /**
     * Caveated upstream pointer for a third-party image — a link to the project
     * it comes from, plus the audit's honest [Project.status] caveat. NOT
     * [imageSourceLink]: there is no digest→commit binding here, so it must never
     * render as a verified source. Keyed by image ref, so it resolves for a
     * tag-pinned image with no digest. null when not recorded.
     */
    fun projectPointer(image: String): ProjectPointer? {
        val index = index ?: return null
        val p = index.projects?.get(normalizedRef(image)) ?: return null
        return ProjectPointer(p.name, p.repo, p.status, "https://github.com/${p.repo}")
    }

    /**
     * Audit page for a TAG-pinned image, which has no digest to key on. An
     * additive sibling to the frozen digest path spec, gated by `tagAudits` so it
     * only ever resolves for an assessed tag. The review it lands on is
     * config-level and says so — the running bytes under a tag can still drift.
     */
    fun tagAuditURL(image: String, tag: String): String? {
        val index = index ?: return null
        val ref = normalizedRef(image)
        if (index.tagAudits?.get(ref)?.contains(tag) != true) return null
        return "$WEB_BASE/images/$ref/tag-$tag.md"
    }

    /** Audit page for an inner-stack manifest version, keyed by the action log's repo-relative path and file_sha256. */
    fun manifestAuditURL(path: String, fileSha256: String): String? {
        val index = index ?: return null
        val key = "$MODEL_COMPOSE_REPO/$path"
        val hex = bareDigest(fileSha256)
        if (index.manifests[key]?.contains(hex) != true) return null
        return "$WEB_BASE/manifests/${stripYamlExtension(key)}/sha256-$hex.md"
    }

    /** Audit page for a hardware-measured compose (gateway or node harness). */
    fun measuredAuditURL(composeHash: String): String? {
        val index = index ?: return null
        val hex = bareDigest(composeHash)
        if (hex !in index.measured) return null
        return "$WEB_BASE/manifests/measured/sha256-$hex.md"
    }

    /** Audit page for the dstack guest-OS image measured into the boot. */
    fun osAuditURL(osImageHash: String): String? {
        val index = index ?: return null
        val hex = bareDigest(osImageHash)
        if (index.os?.contains(hex) != true) return null
        return "$WEB_BASE/os/sha256-$hex.md"
    }

    /**
     * The reviewer's own verdict line for the page an audit URL points at, or
     * null when the index records none. Callers MUST fail closed on null: show
     * neutral copy, never copy that implies the review came back clean. "A page
     * exists" and "the review found nothing" are different facts.
     */
    fun verdict(url: String): String? = pagePath(url)?.let { index?.verdicts?.get(it) }

    /**
     * The verdict CLASS for the page an audit URL points at. null when
     * unrecorded, or when the recorded token is one this build doesn't know (a
     * future class must render neutral, never a guessed badge).
     */
    fun verdictClass(url: String): AuditVerdictClass? =
        pagePath(url)?.let { index?.verdictClass?.get(it) }?.let { AuditVerdictClass.fromRaw(it) }

    /**
     * The machine-read findings of the page an audit URL points at, in the page's
     * own order. Empty when the index records none — which means "the page's
     * findings weren't machine-readable", NOT "the review found nothing".
     */
    fun findings(url: String): List<Finding> = pagePath(url)?.let { index?.findings?.get(it) } ?: emptyList()

    companion object {
        /** iOS's `AuditIndex.shared`; the app installs a persisted instance. */
        @Volatile var shared: AuditIndex = AuditIndex()

        /** Permanent coordinates (org/repo/branch are API — see the repo README). */
        const val WEB_BASE = "https://github.com/teemoonai/audits/blob/main"
        const val INDEX_URL = "https://raw.githubusercontent.com/teemoonai/audits/main/index.json"
        private const val TTL_MS = 3_600_000L

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Decode + validate. `schema` is a compatibility promise: this reader
         * implements schema 1, and the repo evolves it only by ADDITIVE optional
         * keys — so an unknown key is fine, but a different schema NUMBER means the
         * layout changed in a way this code predates. Fail closed to "no index"
         * (no links) rather than render links from a layout we can't read.
         */
        fun decodeIndex(data: ByteArray): Index? {
            val idx = runCatching { json.decodeFromString(Index.serializer(), data.decodeToString()) }.getOrNull() ?: return null
            return if (idx.schema == 1) idx else null
        }

        /** Repo-relative page path for a URL under [WEB_BASE] (the key every per-page index map uses). null for foreign URLs — never matched. */
        fun pagePath(url: String): String? {
            val prefix = "$WEB_BASE/"
            return if (url.startsWith(prefix)) url.substring(prefix.length) else null
        }

        // MARK: the frozen path spec (pure; mirrors the repo README exactly)

        /**
         * docker-ref normalization: strip any tag (the digest is the identity),
         * qualify bare Docker Hub refs with "docker.io/", official single-name
         * images with "library/". Registries are recognized by a dot or port in
         * the first path component.
         */
        fun normalizedRef(image: String): String {
            var ref = image
            // strip a trailing ":tag" — only when the colon is in the LAST path
            // component (a colon in the first component would be a registry port)
            val colon = ref.lastIndexOf(':')
            if (colon >= 0 && !ref.substring(colon).contains('/')) ref = ref.substring(0, colon)
            val first = ref.split('/').firstOrNull { it.isNotEmpty() } ?: ref
            val hasRegistry = first.contains('.') || first.contains(':')
            if (!hasRegistry) ref = if (ref.contains('/')) "docker.io/$ref" else "docker.io/library/$ref"
            return ref
        }

        fun bareDigest(digest: String): String = digest.removePrefix("sha256:")

        fun stripYamlExtension(path: String): String = path.removeSuffix(".yaml")

        private val client by lazy { TeemoonHttp.builder().callTimeout(15, TimeUnit.SECONDS).build() }

        private suspend fun liveFetch(ifNoneMatch: String?): Reply? = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(INDEX_URL).get().apply { ifNoneMatch?.let { header("If-None-Match", it) } }.build()
                client.newCall(request).execute().use { r -> Reply(r.code, r.body.bytes(), r.header("ETag")) }
            }.getOrNull()
        }
    }
}
