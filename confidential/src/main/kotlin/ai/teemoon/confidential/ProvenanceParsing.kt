// ports: teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (the pure manifest parsers only: imageRefs,
//        tagPinnedImageRefs, fullImageRef, dockerfileBaseImage, hasInEnclaveEngineBuild, githubRepo,
//        isNearAIImage. The GitHub-attestation fetch, verifyManifests and the caches are NOT here.)
// ports: teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (fallbackRepos, githubRepos(forImage:))
package ai.teemoon.confidential

/**
 * The parts of ProvenanceService that read an attested compose manifest
 * without a network: which digests it declares, which tags, which in-enclave
 * build base, and which GitHub repo publishes an image's attestations.
 * iOS memoizes these per document (the sheet re-derives them per mutation);
 * Android's callers are expected to hold results in state instead.
 */
object ProvenanceParsing {

    /**
     * Match "<image>[:<tag>]@sha256:<64 hex>" where <image> is the token before
     * '@' (or before an OPTIONAL `:tag`). The tag is discarded: a fully-pinned
     * ref like `lmsysorg/sglang:v0.5.12-cu129@sha256:…` names the image
     * `lmsysorg/sglang`, not the tag — without skipping the tag the `:` (absent
     * from the image class) would truncate the capture to just `v0.5.12-cu129`,
     * mislabeling the image (Qwen 3.6's tagged sglang did exactly this).
     */
    private val imageRefRegex = Regex("([A-Za-z0-9._/\\-]+)(?::[A-Za-z0-9._\\-]+)?@sha256:([0-9a-f]{64})")

    /** Extracts `@sha256:<digest>` image references from a compose manifest, deduplicated by digest. */
    fun imageRefs(manifest: String): List<ImageRef> {
        val seen = HashSet<String>()
        val refs = ArrayList<ImageRef>()
        for (m in imageRefRegex.findAll(manifest)) {
            // Strip the "-" that compose shell-default syntax leaks into the match:
            // `${VAR:-nearaidev/img@sha256:…}` — image names can't legitimately start
            // with a dash. Without this, near.ai images behind a default would be
            // misclassified as third-party.
            val image = m.groupValues[1].trimStart('-')
            val digest = m.groupValues[2]
            if (seen.add(digest)) refs.add(ImageRef(image, digest))
        }
        return refs
    }

    /**
     * Image refs across several host-labeled manifests, deduplicated by digest
     * — an image in both the gateway's and the model node's manifest yields one
     * ref carrying both hosts.
     */
    fun imageRefs(manifests: List<Pair<String, String>>): List<ImageRef> {
        val order = ArrayList<String>()
        val byDigest = LinkedHashMap<String, ImageRef>()
        for ((host, manifest) in manifests) {
            for (ref in imageRefs(manifest)) {
                val existing = byDigest[ref.digest]
                if (existing != null) {
                    if (host.isNotEmpty() && host !in existing.hosts) byDigest[ref.digest] = existing.copy(hosts = existing.hosts + host)
                } else {
                    byDigest[ref.digest] = if (host.isNotEmpty()) ref.copy(hosts = listOf(host)) else ref
                    order.add(ref.digest)
                }
            }
        }
        return order.mapNotNull { byDigest[it] }
    }

    /**
     * TAG-pinned (digestless) image references declared by a compose manifest's
     * services — e.g. `nvcr.io/nvidia/k8s/dcgm-exporter:4.5.2-…` with no
     * `@sha256`. These are REAL action-log-declared containers that [imageRefs]
     * (digest-only by design) can never surface; without this, a near-root
     * sidecar renders nowhere at all.
     *
     * Parsed per-service via the same anchor-aware block parsing the plaintext
     * analysis uses — never a doc-wide regex, which would match image-shaped
     * strings inside label VALUES and comments. Flood guards: `:local`
     * in-enclave builds are excluded (already the local-engine row), and
     * replicas dedupe by full image ref. Call ONLY with the hash-verified inner
     * compose — a tag-pinned row's facts must come from the document the action
     * log actually pinned.
     */
    fun tagPinnedImageRefs(manifest: String): List<String> {
        val yaml = PlaintextExposure.dockerComposeYaml(manifest)
        val anchors = PlaintextExposure.anchorBlocks(yaml)
        val refs = ArrayList<String>()
        val seen = HashSet<String>()
        for (rawBlock in PlaintextExposure.serviceBlocks(yaml)) {
            val ref = fullImageRef(PlaintextExposure.expand(rawBlock, anchors)) ?: continue
            if (ref.contains("@sha256:")) continue   // digest-pinned → imageRefs
            if (ref.endsWith(":local")) continue      // in-enclave build → local-engine row
            if (seen.add(ref)) refs.add(ref)
        }
        return refs
    }

    /** The full `image:` value of a service block — registry host, path, and tag intact. `${VAR:-default}` unwraps to the default. */
    fun fullImageRef(block: String): String? {
        for (rawLine in block.split("\n")) {
            val line = rawLine.trim { it == ' ' || it == '\t' }
            if (!line.startsWith("image:")) continue
            var v = line.removePrefix("image:").trim { it == ' ' || it == '"' || it == '\'' }
            if (v.startsWith("\${")) {
                val dash = v.indexOf(":-"); val end = v.lastIndexOf('}')
                if (dash >= 0 && end > dash) v = v.substring(dash + 2, end)
            }
            return v.ifEmpty { null }
        }
        return null
    }

    private val buildKey = Regex("(?m)^\\s*build:")

    /**
     * The base image of an in-enclave build: the `FROM <ref>[:tag]@sha256:…` of
     * a compose `dockerfile_inline`. The engine that runs is `<name>:local` (no
     * registry digest), so its provenance and its audit live on this base.
     *
     * SCOPED TO THE SERVICE BLOCK, and fail-closed on ambiguity. A flat scan of
     * the whole document returns whichever `FROM` happens to come last, which on
     * a multi-build compose is another model's base entirely (production
     * `small-models.yaml` carries three in-enclave builds, so the flat scan
     * handed the whisper base to every model on that node). Now: consider only
     * service blocks that are BOTH an in-enclave `build:` AND a model server,
     * take that block's own last FROM, and return a base only when exactly one
     * such service exists. Several → null; the caller shows no base rather
     * than confidently the wrong one.
     */
    fun dockerfileBaseImage(manifest: String): ImageRef? {
        val yaml = PlaintextExposure.dockerComposeYaml(manifest)
        val anchors = PlaintextExposure.anchorBlocks(yaml)
        val candidates = ArrayList<ImageRef>()
        for (rawBlock in PlaintextExposure.serviceBlocks(yaml)) {
            val block = PlaintextExposure.expand(rawBlock, anchors)
            if (!buildKey.containsMatchIn(block) || !PlaintextExposure.hasModelServerSignal(block)) continue
            var base: ImageRef? = null
            for (rawLine in block.lines()) {
                val line = rawLine.trim { it == ' ' || it == '\t' }
                if (!line.uppercase().startsWith("FROM ")) continue
                imageRefs(line).firstOrNull()?.let { base = it }
            }
            base?.let { candidates.add(it) }
        }
        return if (candidates.size == 1) candidates[0] else null
    }

    /**
     * Whether this compose builds its inference engine in-enclave at all.
     * [dockerfileBaseImage] returns null both when there is no such build and
     * when there are several; a compose whose engine is a plain digest-pinned
     * registry image (most of the fleet) must not get a row claiming something
     * was "built in-enclave".
     */
    fun hasInEnclaveEngineBuild(manifest: String): Boolean {
        val yaml = PlaintextExposure.dockerComposeYaml(manifest)
        val anchors = PlaintextExposure.anchorBlocks(yaml)
        return PlaintextExposure.serviceBlocks(yaml).any { raw ->
            val block = PlaintextExposure.expand(raw, anchors)
            buildKey.containsMatchIn(block) && PlaintextExposure.hasModelServerSignal(block)
        }
    }

    /**
     * Images whose attestations are published by a different repo than the image
     * name suggests (verified empirically against GitHub's attestation API). Only
     * affects where we LOOK — the certificate's SAN identity still determines
     * the trusted source.
     */
    private val repoAliases = mapOf("compose-manager-launcher" to "compose-manager", "vllm-proxy-rs" to "inference-proxy")

    /**
     * Maps a Docker image name to the GitHub repo that publishes its attestations.
     * near.ai builds under `nearai/<name>` and publishes to Docker Hub as
     * `nearaidev/<name>`, so the trailing path component maps across.
     */
    fun githubRepo(image: String): String? {
        val name = image.split('/').lastOrNull { it.isNotEmpty() } ?: return null
        return "nearai/${repoAliases[name] ?: name}"
    }

    /**
     * Where to look after the guessed repo answers a clean 404. near.ai publishes
     * attestations for engine images it builds from the compose repo's own
     * workflows (GLM-5.3 flash's `nearaidev/sglang`, 2026-09-12: `nearai/sglang`
     * does not exist; the bundle's workflow is
     * `nearai/cvm-compose-files/.github/workflows/publish-glm53-….yaml`). The
     * identity policy is org-level ([BuildIdentityPolicy]), so a hit here verifies as-is.
     *
     * The cost of not asking, measured on a Pixel 10 Pro on 2026-09-22: every
     * glm-5.3-flash session read "encrypted — image unpublished" on Android while
     * the attestation sat one repo over.
     */
    val fallbackRepos: List<String> = listOf(MODEL_COMPOSE_REPO)

    /** The repos to ask for an image's attestation, the guessed one first. */
    fun githubRepos(image: String): List<String> {
        val guessed = githubRepo(image) ?: return emptyList()
        return listOf(guessed) + fallbackRepos.filter { it != guessed }
    }

    /**
     * Whether an image reference names a near.ai-published image (and must
     * therefore carry verifiable provenance). Strips a registry host prefix (a
     * first path component containing "." or ":") before checking the namespace.
     */
    fun isNearAIImage(image: String): Boolean {
        var components = image.split('/').filter { it.isNotEmpty() }
        if (components.firstOrNull()?.let { it.contains('.') || it.contains(':') } == true) components = components.drop(1)
        if (components.size < 2) return false   // bare images like "alpine"
        return components[0].lowercase() in setOf("nearai", "nearaidev")
    }
}
