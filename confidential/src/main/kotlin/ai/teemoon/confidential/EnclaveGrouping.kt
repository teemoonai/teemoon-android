// ports: teemoon/Confidential/EnclaveGrouping.swift @ v1.0.4
// ports: teemoon/Confidential/EnclaveGrouping.swift @ v1.0.4 (unverifiedEntry: one attestation-api link per repo asked)
package ai.teemoon.confidential

/**
 * Running images grouped by the enclave whose quote measures them. The view
 * draws the groups; this decides membership, order, and which audit links
 * are in scope. See EnclaveGroupingTest.
 */
class EnclaveGrouping(
    val attestation: AttestationRecord?,
    val imageProvenance: ManifestProvenance?,
    val modelLayerManifest: String?,
    val modelLayerVerification: ModelLayerVerification?,
    val audit: AuditIndex = AuditIndex.shared,
) {
    val exposure: PlaintextExposure
        get() = PlaintextExposure.analyze(modelLayerManifest, attestation?.gpuNodeComposeManifest)

    /** Model enclave first (primary — key bound + plaintext), then gateway. Empty when there's no provenance data yet. */
    fun groups(): List<EnclaveGroup> {
        val (verified, thirdParty) = provenanceLists
        val failures = provenanceFailures
        if (verified.isEmpty() && failures.isEmpty() && attestation?.gpuNodeComposeHash == null) return emptyList()
        return listOfNotNull(
            group(host = "model", role = "model enclave", primary = true,
                mrConfig = attestation?.gpuNodeComposeHash, quoteVerified = attestation?.modelQuoteVerification?.isVerified,
                verified = verified, thirdParty = thirdParty, failures = failures, includeLocalEngine = true, exposure = exposure),
            group(host = "gateway", role = "gateway enclave", primary = false,
                mrConfig = attestation?.composeHash, quoteVerified = attestation?.quoteVerification?.isVerified,
                verified = verified, thirdParty = thirdParty, failures = failures, includeLocalEngine = false, exposure = null),
        )
    }

    val provenanceLists: Pair<List<ImageRef>, List<ImageRef>>
        get() = when (val p = imageProvenance) {
            is ManifestProvenance.AllVerified -> p.verified to p.thirdParty
            is ManifestProvenance.Incomplete -> p.verified to p.thirdParty
            null -> emptyList<ImageRef>() to emptyList()
        }

    val provenanceFailures: List<ProvenanceFailure>
        get() = (imageProvenance as? ManifestProvenance.Incomplete)?.failures ?: emptyList()

    fun group(
        host: String, role: String, primary: Boolean,
        mrConfig: String?, quoteVerified: Boolean?,
        verified: List<ImageRef>, thirdParty: List<ImageRef>, failures: List<ProvenanceFailure>,
        includeLocalEngine: Boolean, exposure: PlaintextExposure?,
    ): EnclaveGroup? {
        fun belongs(r: ImageRef) = belongs(r, host, primary)
        var entries = ArrayList<ImageEntry>()
        entries.addAll(failures.filter { belongs(it.ref) }.map(::unverifiedEntry))
        if (includeLocalEngine) localEngineEntry()?.let { entries.add(it) }
        entries.addAll(verified.filter(::belongs).map(::registryEntry))
        entries.addAll(thirdParty.filter(::belongs).map(::thirdPartyEntry))
        // In-enclave build (`:local`) is `FROM <base>@sha256:…`; drop the standalone
        // base row so its audit stays on the engine row.
        if (includeLocalEngine && entries.any { it.kind == ImageEntry.Kind.LOCAL }) {
            modelLayerManifest?.let { ProvenanceParsing.dockerfileBaseImage(it) }?.let { base ->
                entries.removeAll { it.kind != ImageEntry.Kind.LOCAL && it.digestFull == "sha256:${base.digest}" }
            }
        }
        // Tag-pinned images come only from the hash-verified inner compose.
        if (exposure != null && modelLayerManifest != null) {
            entries.addAll(ProvenanceParsing.tagPinnedImageRefs(modelLayerManifest).map(::tagPinnedEntry))
        }
        // Image audits are model-enclave only: the same digest on the gateway is a
        // different deployment the review does not cover.
        for (e in entries) {
            if (e.kind == ImageEntry.Kind.LOCAL) continue
            e.auditURL = audit.scopedImageAuditURL(inScope = exposure != null, image = e.name, digestFull = e.digestFull)
            if (exposure != null && e.auditURL == null && e.kind == ImageEntry.Kind.TAG_PINNED) {
                e.tag?.let { e.auditURL = audit.tagAuditURL(e.name, it) }
            }
            if (exposure != null) {
                e.digestFull?.let { df -> audit.imageSourceLink(e.name, df)?.let { src -> e.links = e.links + RunLink(src.title, src.url) } }
                if (e.kind == ImageEntry.Kind.THIRD_PARTY || e.kind == ImageEntry.Kind.TAG_PINNED) {
                    audit.projectPointer(e.name)?.let { p -> e.projectPointer = ProjectLink(p.name, p.repo, p.status, p.url) }
                }
            }
        }
        if (exposure != null) {
            for (e in entries) e.plaintextRole = exposure.role(e.name)
            if (exposure.touchers.any { it.role == PlaintextExposure.Role.MODEL_SERVER } &&
                entries.none { it.plaintextRole == PlaintextExposure.Role.MODEL_SERVER }) {
                entries.firstOrNull { it.kind == ImageEntry.Kind.LOCAL }?.plaintextRole = PlaintextExposure.Role.MODEL_SERVER
            }
            for (e in entries) if (e.plaintextRole == null) e.capability = exposure.capability(e.name)
            entries = ArrayList(entries.sortedBy { band(it) })
            modelLayerManifest?.let { inner ->
                for (e in entries) {
                    val touchRole = e.plaintextRole ?: continue
                    val link = if (touchRole == PlaintextExposure.Role.MODEL_SERVER) modelComposeYamlLink() else null
                    e.configLines = MeasuredConfig.forRole(touchRole, inner, link)?.lines ?: emptyList()
                }
            }
        }
        if (host == "model") attestation?.modelOSImageHash?.takeIf { it.isNotEmpty() }?.let { entries.add(0, guestOSEntry(it)) }
        for (e in entries) {
            val tiered = e.plaintextRole != null || e.capability != null || e.kind == ImageEntry.Kind.GUEST_OS
            val url = e.auditURL
            if (!tiered && url != null) {
                val cls = audit.verdictClass(url)
                e.links = e.links + RunLink(cls?.let { "audit · ${it.label}" } ?: "audit", url)
            }
        }
        if (entries.isEmpty() && mrConfig == null) return null
        val measurementCaption = if (exposure != null) attestation?.gpuNodeComposeManifest?.let { MeasuredConfig.allowedEnvsCaption(it) } else null
        val configAuditURL = mrConfig?.let { audit.measuredAuditURL(it) }
        val recipeCard: EnclaveGroup.RecipeCard?
        if (exposure != null) {
            val inner = modelLayerManifest
            for (e in entries) {
                e.isRecipe = isRecipeImage(
                    digestFull = e.digestFull,
                    tagRef = if (e.kind == ImageEntry.Kind.TAG_PINNED) (e.tag?.let { "${e.name}:$it" } ?: e.name) else null,
                    isLocalEngine = e.kind == ImageEntry.Kind.LOCAL,
                    isGuestOS = e.kind == ImageEntry.Kind.GUEST_OS,
                    innerComposeYaml = inner)
            }
            recipeCard = EnclaveGroup.RecipeCard(
                fileSHA = attestation?.modelFileHash,
                yamlLink = modelComposeYamlLink(),
                verification = modelLayerVerification,
                auditURL = attestation?.modelComposePath?.let { p -> attestation.modelFileHash?.let { audit.manifestAuditURL(p, it) } })
        } else recipeCard = null
        val egress = ArrayList<NodeFinding>()
        if (exposure != null) {
            val pages = ArrayList<Pair<String, String>>()
            for (e in entries) {
                val u = e.auditURL ?: continue
                if (e.kind == ImageEntry.Kind.GUEST_OS) { pages.add(u to "guest OS"); continue }
                val short = e.name.split('/').lastOrNull { it.isNotEmpty() } ?: e.name
                val suffix = e.digestFull?.let { " @${AuditIndex.bareDigest(it).take(8)}" } ?: ""
                pages.add(u to short + suffix)
            }
            recipeCard?.auditURL?.let { pages.add(it to "recipe") }
            configAuditURL?.let { pages.add(it to "deployment config") }
            val seen = HashSet<String>()
            for ((url, source) in pages) {
                val path = AuditIndex.pagePath(url) ?: continue
                if (!seen.add(path)) continue
                for (f in audit.findings(url)) {
                    egress.add(NodeFinding(id = "$path#${f.anchor}", severity = f.severity, deployed = f.deployed,
                        qualifier = f.qualifier, title = f.title, source = source, url = "$url#${f.anchor}"))
                }
            }
        }
        return EnclaveGroup(
            id = role, role = role, primary = primary,
            binding = "the hardware seals a fingerprint (mr_config) of this enclave's compose file into its quote — proof it booted exactly the images below, and the operator can't swap in anything else without changing that fingerprint.",
            deployment = if (primary) attestation?.deploymentCaption else null,
            mrConfig = mrConfig, quoteVerified = quoteVerified, images = entries,
            plaintextCaption = exposure?.groupCaption,
            measurementCaption = measurementCaption,
            configAuditURL = configAuditURL,
            recipe = recipeCard,
            egressFindings = egress)
    }

    fun modelComposeYamlLink(): RunLink? {
        val path = attestation?.modelComposePath ?: return null
        val commit = attestation.modelComposeCommit ?: return null
        return RunLink("yaml @ ${commit.take(7)}", "https://github.com/$MODEL_COMPOSE_REPO/blob/$commit/$path")
    }

    fun localEngineEntry(): ImageEntry? {
        val path = attestation?.modelComposePath ?: return null
        val commit = attestation.modelComposeCommit ?: return null
        val inner = modelLayerManifest ?: return null
        if (!ProvenanceParsing.hasInEnclaveEngineBuild(inner)) return null
        val base = ProvenanceParsing.dockerfileBaseImage(inner)
        val name = base?.image ?: (path.split('/').lastOrNull { it.isNotEmpty() } ?: path)
        val composeURL = "https://github.com/$MODEL_COMPOSE_REPO/blob/$commit/$path"
        val versionLabel = attestation.modelComposeTag?.let { "$it → ${commit.take(7)}" } ?: commit.take(7)
        val links = ArrayList<RunLink>()
        base?.let { b -> audit.imageSourceLink(b.image, b.digest)?.let { links.add(RunLink(it.title, it.url)) } }
        links.add(RunLink("$versionLabel · patch", composeURL))
        val auditURL = base?.let { audit.imageAuditURL(it.image, it.digest) }
        val note = if (base != null)
            "the running engine — this base image built in-enclave with the compose's visible patch (the yaml above). No registry digest of its own; its source is the base at the pinned commit plus that patch."
        else "built in-enclave from the attested compose (pinned base + visible patch) — no registry digest."
        return ImageEntry(id = "local·$name", name = name, kind = ImageEntry.Kind.LOCAL, digest = null, note = note, links = links, auditURL = auditURL)
    }

    fun guestOSEntry(osHash: String) = ImageEntry(
        id = "os·${osHash.take(8)}", name = "nearai/private-ml-sdk", kind = ImageEntry.Kind.GUEST_OS,
        digest = "os_image sha256:${osHash.take(12)}…", digestFull = "sha256:$osHash",
        note = "the confidential-VM guest image (kernel + rootfs) this enclave boots on — near.ai’s private-ml-sdk build, measured into the quote as os_image_hash.",
        auditURL = audit.osAuditURL(osHash))

    companion object {
        /** Empty-host images fall to the model group so they're shown once, not dropped. */
        fun belongs(r: ImageRef, host: String, primary: Boolean): Boolean = host in r.hosts || (r.hosts.isEmpty() && primary)

        /** Unverified first, then plaintext touchers, then capability tiers. Stable within a band. */
        fun band(e: ImageEntry): Int {
            if (e.kind == ImageEntry.Kind.UNVERIFIED) return 0
            when (e.plaintextRole) {
                PlaintextExposure.Role.E2EE_TERMINATOR -> return 1
                PlaintextExposure.Role.MODEL_SERVER -> return 2
                null -> Unit
            }
            return when (e.capability) {
                PlaintextExposure.Capability.PROCESS_ACCESS -> 3
                PlaintextExposure.Capability.DEVICE_PRIVILEGE -> 4
                PlaintextExposure.Capability.LOG_ACCESS -> 5
                null -> 6
            }
        }

        fun registryEntry(r: ImageRef): ImageEntry {
            val links = ArrayList<RunLink>()
            val repo = r.sourceRepo; val commit = r.sourceCommit
            if (repo != null && commit != null) {
                val tag = r.sourceRef?.replace("refs/tags/", "")?.replace("refs/heads/", "")
                links.add(RunLink(tag?.let { "$it → ${commit.take(7)}" } ?: commit.take(7), "$repo/tree/$commit"))
            }
            links.add(RunLink("sigstore", "https://search.sigstore.dev/?hash=${r.digest}"))
            return ImageEntry(id = "reg·${r.image}·${r.digest.take(8)}", name = r.image, kind = ImageEntry.Kind.REGISTRY,
                digest = "sha256:${r.digest.take(12)}…", digestFull = "sha256:${r.digest}", note = null, links = links)
        }

        fun unverifiedEntry(f: ProvenanceFailure): ImageEntry {
            val why = f.reason.failureReason ?: "could not be traced to a published near.ai build"
            val links = arrayListOf(RunLink("sigstore", "https://search.sigstore.dev/?hash=${f.ref.digest}"))
            // One link per repo the app asked, so the reader can see the same
            // answers it saw.
            ProvenanceParsing.githubRepos(f.ref.image).forEachIndexed { i, repo ->
                links.add(i, RunLink("attestation api · $repo", "https://api.github.com/repos/$repo/attestations/sha256:${f.ref.digest}"))
            }
            return ImageEntry(id = "unv·${f.ref.image}·${f.ref.digest.take(8)}", name = f.ref.image, kind = ImageEntry.Kind.UNVERIFIED,
                digest = "sha256:${f.ref.digest.take(12)}…", digestFull = "sha256:${f.ref.digest}", note = "couldn’t verify: $why.", links = links)
        }

        fun thirdPartyEntry(r: ImageRef) = ImageEntry(
            id = "3p·${r.image}·${r.digest.take(8)}", name = r.image, kind = ImageEntry.Kind.THIRD_PARTY,
            digest = "sha256:${r.digest.take(12)}…", digestFull = "sha256:${r.digest}",
            note = "digest-pinned by the attested manifest — no first-party build attestation.")

        fun tagPinnedEntry(ref: String): ImageEntry {
            var name = ref; var tag: String? = null
            val colon = ref.lastIndexOf(':')
            if (colon >= 0 && !ref.substring(colon + 1).contains('/')) { name = ref.substring(0, colon); tag = ref.substring(colon + 1) }
            return ImageEntry(id = "tag·$ref", name = name, kind = ImageEntry.Kind.TAG_PINNED, tag = tag,
                note = "the action log pins this image’s tag, not its contents — the registry can serve different bytes under the same tag, so what’s actually running can drift with no trace in this attestation chain. not verifiable on this device.")
        }
    }
}
