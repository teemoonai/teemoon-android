// ports: teemoon/Confidential/TrustVerdict.swift @ c5e329f2 (ModelNodeAudit.onPath's wholeHost, 1.1.1 audit A5); @ b3d90fac (ModelNodeAudit.onPath)
package ai.teemoon.confidential

/** The model node's audit pages, narrowed to the services this chat's requests pass through (audit 2026-11-06). */
object ModelNodeAuditPath {
    /**
     * The pages that judge this chat (chat_path): add the pages of every revision and image on the path, and drop a
     * recipe LEAKS page whose services the path never touches. Ambiguous: every running revision's page counts, so a leak
     * anywhere on the CVM counts. A page without `services` always counts.
     */
    fun onPath(urls: List<String>, chatPath: ChatPath.Result?, runningSources: List<DeployLog.Source>, audit: AuditIndex, wholeHost: Boolean = false): List<String> {
        var out = urls.toMutableList()
        // [wholeHost] (a listed enclave, 1.1.1 audit A5): every running revision's page counts too, before the off-path filter below.
        if (wholeHost) for (source in runningSources) audit.manifestAuditURL(source.file, source.fileSha256)?.let { out += it }
        when (chatPath) {
            is ChatPath.Result.Path -> {
                for (source in chatPath.hops.map { it.source }.toSet()) audit.manifestAuditURL(source.file, source.fileSha256)?.let { out += it }
                for (image in chatPath.hops.mapNotNull { it.image }) audit.imageAuditURL(image.image, "sha256:${image.digest}")?.let { out += it }
                val path = chatPath.hops.map { it.service }.toSet()
                out = out.filter { url ->
                    val e = audit.exposure(url)
                    if (audit.verdictClass(url) != AuditVerdictClass.LEAKS || e?.scopeKind != "recipe" || e.services == null) true
                    else e.services.any { it in path }
                }.toMutableList()
            }
            is ChatPath.Result.Ambiguous ->
                for (source in runningSources.toSet()) audit.manifestAuditURL(source.file, source.fileSha256)?.let { out += it }
            null -> Unit
        }
        return out.distinct()
    }
}
