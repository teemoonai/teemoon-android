// ports: teemoon/Confidential/PlaintextExposure.swift @ v1.0.1
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Identifies which images in the model enclave's compose ever see the user's
 * message *decrypted* — the confidentiality question, distinct from the
 * integrity question (is all the code published) that provenance answers.
 *
 * There is no attested "sees plaintext" field, so this reads it off the
 * compose the model quote measured, using two grounded signals:
 *   • the E2EE / OHTTP terminator — the service that decrypts the request
 *     sealed to the model key, identified by `OHTTP_ENABLED` / `TLS_CERT_PATH`.
 *   • the model server — the service that runs the model over plaintext
 *     tokens, identified by the inference-engine launch command
 *     (`--model-path` / `launch_server`).
 * Everything else (gateway relay, ingress, telemetry sidecars) only ever
 * handles ciphertext or content-free metrics and is deliberately NOT named.
 *
 * Fail-soft: if the compose can't be parsed into these roles, callers fall
 * back to honest generic copy rather than naming the wrong images. Pure and
 * testable.
 */
data class PlaintextExposure(
    /** The images that ever see plaintext, terminator first. Empty when the compose didn't yield a confident answer. */
    val touchers: List<Toucher>,
    /**
     * Capability-based accessors from BOTH attested documents (the harness
     * carries most of them: deployer, watchdog, telemetry). Independent of
     * [touchers] — an image is never listed in both.
     */
    val accessors: List<Accessor> = emptyList(),
) {
    enum class Role(
        val rawValue: String,
        /** Short human phrase for what this component does with plaintext. */
        val whatItDoes: String,
        /** Capsule label for the known-code list — working-as-intended, not a warning: these images seeing your message is the design. */
        val capsule: String,
    ) {
        E2EE_TERMINATOR("e2eeTerminator", "decrypts the request sealed to the model key", "decrypts your request"),
        MODEL_SERVER("modelServer", "runs the model over your plaintext tokens", "runs the model"),
    }

    /** [image] is the short image name, e.g. "vllm-proxy-rs". */
    data class Toucher(val image: String, val role: Role)

    /**
     * Plaintext access *by capability* rather than by role: images whose
     * attested privileges could reach the touchers' plaintext even though
     * they never handle the data path. The scope rule of the audits repo,
     * derived live from the same composes the hardware attests.
     */
    enum class Capability(val rawValue: String, val capsule: String, val whatItDoes: String) {
        /**
         * Container-control privileges — a docker socket (any mount mode: the API
         * is full-duplex over an :ro socket) or host-pid visibility. Could read
         * plaintext straight out of process memory.
         */
        PROCESS_ACCESS("processAccess", "can reach enclave processes",
            "holds container-control privileges (docker socket / host pid) that could reach plaintext in process memory — control-plane by design, and why it's in audit scope"),
        /**
         * Elevated device privileges — `cap_add: SYS_ADMIN`, `privileged: true`,
         * or the nvidia runtime with GPU device reservations (the dcgm-exporter
         * shape). Not on the data path and no container control, but real
         * kernel/device-level privilege inside the enclave — which is exactly
         * why it must carry a tag rather than read as "safe to ignore".
         */
        DEVICE_PRIVILEGE("devicePrivilege", "GPU-privileged",
            "runs with SYS_ADMIN and GPU device access to scrape hardware metrics — an elevated privilege, which is why it's in audit scope"),
        /** Container-log mounts — sees whatever the plaintext handlers choose to log (content-free at the audited flags). */
        LOG_ACCESS("logAccess", "reads container logs",
            "tails other containers' logs — sees whatever the plaintext handlers log (content-free at the attested flags)"),
    }

    /** [image] is the short image name, e.g. "compose-manager". */
    data class Accessor(val image: String, val capability: Capability)

    /**
     * The role of the named image, matched on the short image name — so
     * "nearaidev/vllm-proxy-rs@sha256:…" (a provenance ref) matches the toucher
     * "vllm-proxy-rs" (parsed from the compose). null = this image was not
     * identified as seeing plaintext.
     */
    fun role(forImage: String): Role? = touchers.firstOrNull { it.image == shortName(forImage) }?.role

    /** The capability of the named image, matched like [role]. null = no attested privilege that reaches plaintext (or unanalyzed). */
    fun capability(forImage: String): Capability? = accessors.firstOrNull { it.image == shortName(forImage) }?.capability

    /**
     * The one caption under the model-enclave group's image list. It states
     * the counterpart of the role tags *positively* when the toucher set is
     * known — and MUST flip to "couldn't determine…" when it's empty.
     *
     * INVARIANT: absence-of-badge must never read as "safe". An untagged image
     * is only known-ciphertext-only when the analysis actually produced the
     * toucher set; an empty set is an analysis gap, not an all-clear.
     */
    val groupCaption: String
        get() = if (touchers.isEmpty()) {
            "couldn't determine which images see your message — the attested compose didn't yield the plaintext-handling set, so nothing here is tagged. that's an analysis gap, not an all-clear."
        } else {
            "images without a tag only handle encrypted data or content-free telemetry, per the attested compose."
        }

    companion object {
        private fun shortName(name: String): String {
            var short = name.split('/').lastOrNull { it.isNotEmpty() } ?: name
            short = short.substringBefore('@')
            short = short.substringBefore(':')
            return short
        }

        private fun String.trimSpaces() = trim { it == ' ' || it == '\t' }
        private fun indentOf(line: String) = line.takeWhile { it == ' ' }.length
        private fun isAnchorChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

        /**
         * Analyzes plaintext exposure across the two attested documents.
         *
         * In production the engine and proxy live in the **inner** model-layer
         * compose (the YAML compose-manager fetches at the attested commit, whose
         * SHA256 matches the attested `file_sha256`) — the outer measured
         * `app_compose` is the management harness only and carries NO toucher
         * signals (verified live against glm-5-2.completions.near.ai,
         * 2026-07-18). So the inner document is authoritative when it yields an
         * answer; the outer manifest remains a fallback for older deployments
         * that inlined the engine services there.
         */
        fun analyze(innerComposeYaml: String?, outerComposeYaml: String?): PlaintextExposure {
            // Capability accessors live mostly in the OUTER harness (deployer,
            // watchdog, telemetry), touchers in the inner stack — so accessors are
            // the union of BOTH documents, while touchers keep inner-first
            // precedence (the outer is a fallback for legacy inlined deployments).
            val accessors = ArrayList<Accessor>()
            val seen = HashSet<String>()
            for (doc in listOfNotNull(innerComposeYaml, outerComposeYaml)) {
                for (a in accessors(doc)) if (seen.add(a.image)) accessors.add(a)
            }
            fun without(touchers: List<Toucher>) = accessors.filter { acc -> touchers.none { it.image == acc.image } }
            if (innerComposeYaml != null) {
                val x = analyze(innerComposeYaml)
                if (x.touchers.isNotEmpty()) return PlaintextExposure(x.touchers, without(x.touchers))
            }
            if (outerComposeYaml != null) {
                val x = analyze(outerComposeYaml)
                return PlaintextExposure(x.touchers, without(x.touchers))
            }
            return PlaintextExposure(emptyList(), accessors)
        }

        /**
         * Scans a compose document for images whose attested privileges could
         * reach plaintext: docker-socket / host-pid container control, or
         * container-log mounts. Same anchor-aware block parsing as the toucher
         * analysis; fail-soft to the empty set.
         */
        fun accessors(rawYaml: String): List<Accessor> {
            val yaml = dockerComposeYaml(rawYaml)
            val found = ArrayList<Accessor>()
            val seen = HashSet<String>()
            val anchors = anchorBlocks(yaml)
            for (rawBlock in serviceBlocks(yaml)) {
                val block = expand(rawBlock, anchors)
                val image = shortImageName(block) ?: continue
                // Severity-ordered: container control subsumes device privilege
                // (compose-manager carries SYS_ADMIN *and* a docker socket — it
                // must classify as process access, the stronger claim), and
                // device privilege outranks a log mount.
                val capability = when {
                    hasProcessAccessSignal(block) -> Capability.PROCESS_ACCESS
                    hasDevicePrivilegeSignal(block) -> Capability.DEVICE_PRIVILEGE
                    hasLogAccessSignal(block) -> Capability.LOG_ACCESS
                    else -> null
                } ?: continue
                if (!seen.add(image)) continue
                found.add(Accessor(image, capability))
            }
            // Severity order (process → device → log), stable within each band.
            return found.sortedBy { it.capability.ordinal }
        }

        /** Container-control signals: a docker socket mount (any mode — the API is full-duplex over an :ro socket) or host-pid namespace visibility. */
        fun hasProcessAccessSignal(block: String): Boolean {
            val l = block.lowercase()
            return l.contains("docker.sock") || l.contains("pid: host")
        }

        /** Log-stream signals: the docker container-log directory or journald. */
        fun hasLogAccessSignal(block: String): Boolean {
            val l = block.lowercase()
            return l.contains("/var/lib/docker/containers") || l.contains("/run/log/journal")
        }

        /**
         * Device-privilege signals — the dcgm-exporter shape: `cap_add` with
         * SYS_ADMIN, `privileged: true`, or the nvidia runtime combined with a GPU
         * device reservation. (Comment lines never reach here — the block splitter
         * drops them — so a prose mention of SYS_ADMIN can't match.)
         */
        fun hasDevicePrivilegeSignal(block: String): Boolean {
            val l = block.lowercase()
            if (l.contains("privileged: true")) return true
            if (l.contains("sys_admin")) return true
            if (l.contains("runtime: nvidia") &&
                (l.contains("driver: nvidia") || l.contains("capabilities: [gpu]") || l.contains("devices:"))) return true
            return false
        }

        /**
         * Analyzes a model-enclave compose YAML. Returns an empty set (not a
         * guess) when the structure isn't recognized.
         *
         * Production composes use YAML anchors for shared config (verified live
         * against `prod/GLM-5.1-SGL-AWQ-TP4.yaml` @ c545c95, 2026-07-19): the
         * proxy's `image:` lives in an `x-vllm-proxy-common: &vllm-proxy-common`
         * block referenced via `<<: *vllm-proxy-common`, and the engine's launch
         * flags live in `x-awq-cmd: &awq-cmd` referenced via `command: *awq-cmd`.
         * Each service block is therefore expanded with the text of every anchor
         * it references before image/signal extraction — a targeted alias
         * resolution, not a YAML engine; unresolvable structure still fails soft
         * to the empty set.
         */
        fun analyze(modelComposeYaml: String): PlaintextExposure {
            val yaml = dockerComposeYaml(modelComposeYaml)
            val found = ArrayList<Toucher>()
            val seen = HashSet<String>()
            val anchors = anchorBlocks(yaml)
            // The terminators name the containers they hand decrypted plaintext to.
            // That is a stronger signal than matching engine image names, and it is
            // the only one that catches an engine which is neither vLLM nor sglang:
            // production `small-models.yaml` runs `model-privacy-filter`, an
            // in-enclave build `FROM pytorch/pytorch:…` serving `openai/privacy-filter`
            // over a FastAPI server, with no engine image name and no otel labels.
            // Every image-shaped heuristic missed it, so it rendered untagged — under
            // a group caption that says untagged images only handle encrypted data.
            val backends = backendServiceNames(yaml, anchors)
            for (rawBlock in serviceBlocks(yaml)) {
                val block = expand(rawBlock, anchors)
                val image = shortImageName(block) ?: continue
                val role = when {
                    hasTerminatorSignal(block) -> Role.E2EE_TERMINATOR
                    hasModelServerSignal(block) -> Role.MODEL_SERVER
                    isNamedAsProxyBackend(rawBlock, backends) -> Role.MODEL_SERVER
                    else -> null
                } ?: continue
                // Collapse replicas (r1/r2 of the same model image).
                if (seen.add("$image·${role.rawValue}")) found.add(Toucher(image, role))
            }
            // Terminator before model server, for a natural read.
            return PlaintextExposure(found.sortedBy { if (it.role == Role.E2EE_TERMINATOR) 0 else 1 })
        }

        // MARK: Parsing

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The docker-compose YAML to parse, unwrapped from its transport form.
         * The model-layer INNER doc arrives as raw YAML, but near.ai's GPU-node
         * OUTER `app_compose` (teemoon's `gpuNodeComposeManifest`) is a dstack
         * manifest **JSON** carrying the compose in a `docker_compose_file` string
         * field with escaped newlines. The block parser needs real `services:`
         * lines, so pull that field out when the input is that JSON; otherwise
         * pass the text through unchanged. Fail-soft: anything that isn't the
         * expected JSON shape is returned as-is.
         */
        fun dockerComposeYaml(input: String): String {
            val trimmed = input.trim()
            if (!trimmed.startsWith("{") || !trimmed.contains("\"docker_compose_file\"")) return input
            val obj = runCatching { json.parseToJsonElement(input) as? JsonObject }.getOrNull() ?: return input
            val compose = (obj["docker_compose_file"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return input
            return compose
        }

        /**
         * Collects every `&anchor` definition in the document: the defining line
         * plus all following deeper-indented lines (covers `x-…: &name` mapping
         * blocks and `x-…: &name >` folded scalars alike). Keyed by anchor name.
         */
        fun anchorBlocks(yaml: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            val lines = yaml.split("\n")
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val name = anchorName(line)
                if (name != null) {
                    val defIndent = indentOf(line)
                    val block = arrayListOf(line)
                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j]
                        if (next.trimSpaces().isEmpty()) { j++; continue }
                        if (indentOf(next) <= defIndent) break
                        block.add(next); j++
                    }
                    map[name] = block.joinToString("\n")
                }
                i++
            }
            return map
        }

        /** The `&name` an (uncommented) line defines, if any. */
        private fun anchorName(line: String): String? {
            val trimmed = line.trimSpaces()
            if (trimmed.startsWith("#")) return null
            val amp = trimmed.indexOf('&'); if (amp < 0) return null
            val name = trimmed.substring(amp + 1).takeWhile { isAnchorChar(it) }
            return name.ifEmpty { null }
        }

        /**
         * Appends the text of every anchor a block references (`<<: *name`,
         * `command: *name`, …) so image/signal extraction sees through the
         * indirection. The block's own lines stay first, so an inline `image:`
         * wins over an anchor's.
         */
        fun expand(block: String, anchors: Map<String, String>): String {
            if (anchors.isEmpty()) return block
            val out = StringBuilder(block)
            val appended = HashSet<String>()
            for (rawLine in block.split("\n")) {
                val line = rawLine.trimSpaces()
                if (line.startsWith("#")) continue
                val star = line.indexOf('*'); if (star < 0) continue
                val name = line.substring(star + 1).takeWhile { isAnchorChar(it) }
                val anchor = anchors[name]
                if (name.isNotEmpty() && anchor != null && appended.add(name)) out.append("\n").append(anchor)
            }
            return out.toString()
        }

        /** Splits the `services:` mapping into one text block per service, keyed by the indentation of the first service. */
        fun serviceBlocks(yaml: String): List<String> {
            val lines = yaml.split("\n")
            val start = lines.indexOfFirst { it.trimSpaces() == "services:" }
            if (start < 0) return emptyList()
            val blocks = ArrayList<String>()
            var current = ArrayList<String>()
            var serviceIndent: Int? = null
            fun flush() { if (current.isNotEmpty()) blocks.add(current.joinToString("\n")); current = ArrayList() }
            for (line in lines.drop(start + 1)) {
                val trimmed = line.trimSpaces()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val indent = indentOf(line)
                if (indent == 0) break               // next top-level key ends services
                if (serviceIndent == null) serviceIndent = indent
                if (indent == serviceIndent) flush()  // a new service header
                current.add(line)
            }
            flush()
            return blocks
        }

        /**
         * The `image:` value in a service block, reduced to its short name — no
         * registry host, no `@sha256` digest, no tag: "…/vllm-proxy-rs@sha256:…"
         * → "vllm-proxy-rs"; "glm51-sgl-awq-tp4-patched:local" → same, tag dropped.
         */
        fun shortImageName(block: String): String? {
            for (rawLine in block.split("\n")) {
                val line = rawLine.trimSpaces()
                if (!line.startsWith("image:")) continue
                var v = line.removePrefix("image:").trim { it == ' ' || it == '"' || it == '\'' }
                // strip ${VAR:-default} wrapper, keeping the default
                if (v.startsWith("\${")) {
                    val dash = v.indexOf(":-"); val end = v.lastIndexOf('}')
                    if (dash >= 0 && end > dash) v = v.substring(dash + 2, end)
                }
                v = v.substringBefore('@')
                val lastPath = v.split('/').lastOrNull { it.isNotEmpty() } ?: v
                val colon = lastPath.indexOf(':')
                if (colon >= 0) return lastPath.substring(0, colon)
                return lastPath.ifEmpty { null }
            }
            return null
        }

        fun hasTerminatorSignal(block: String): Boolean {
            val u = block.uppercase()
            return u.contains("OHTTP_ENABLED") || u.contains("TLS_CERT")
        }

        /**
         * Every container an E2EE terminator forwards decrypted requests to, taken
         * from its `VLLM_BASE_URL` / `VLLM_BACKEND_URLS` env (comma-separated
         * `http://<service>:<port>` list). This is the data path stated by the
         * component that holds the plaintext, so it identifies a model server even
         * when the image name says nothing — the `model-privacy-filter` case.
         * Fail-soft: no terminator, or no backend env, yields the empty set.
         */
        fun backendServiceNames(yaml: String, anchors: Map<String, String>): Set<String> {
            val names = HashSet<String>()
            for (rawBlock in serviceBlocks(yaml)) {
                val block = expand(rawBlock, anchors)
                if (!hasTerminatorSignal(block)) continue
                for (rawLine in block.split("\n")) {
                    val line = rawLine.trim { it == ' ' || it == '-' || it == '"' || it == '\'' }
                    if (!line.startsWith("VLLM_BASE_URL=") && !line.startsWith("VLLM_BACKEND_URLS=")) continue
                    val eq = line.indexOf('='); if (eq < 0) continue
                    for (url in line.substring(eq + 1).split(',')) {
                        var host = url.trim { it == ' ' || it == '"' || it == '\'' }
                        host = host.substringAfter("://", host)
                        host = host.substringBefore(':')
                        host = host.substringBefore('/')
                        if (host.isNotEmpty()) names.add(host)
                    }
                }
            }
            return names
        }

        /**
         * Whether this service block IS one of those backends — matched on the
         * compose service key (the block's first line) or an explicit
         * `container_name:`, since a terminator's URL may name either.
         */
        fun isNamedAsProxyBackend(rawBlock: String, backends: Set<String>): Boolean {
            if (backends.isEmpty()) return false
            val lines = rawBlock.split("\n")
            val header = lines.firstOrNull()?.trimSpaces()
            if (header != null && header.endsWith(":") && header.dropLast(1) in backends) return true
            for (rawLine in lines) {
                val line = rawLine.trimSpaces()
                if (!line.startsWith("container_name:")) continue
                val v = line.removePrefix("container_name:").trim { it == ' ' || it == '"' || it == '\'' }
                if (v in backends) return true
            }
            return false
        }

        /**
         * Signal drift (verified live): SGLang engine blocks use `launch_server` or
         * `sglang serve … --model-path …`; **vLLM** engine blocks (gemma-4, gpt-oss,
         * Qwen3-VL) use `vllm serve <repo>` OR a bare positional under the
         * `vllm/vllm-openai` entrypoint — no `--model-path`. Recognize the engine
         * IMAGES too so a vLLM server on a combined node is tagged (and scoped)
         * like an SGLang one, instead of the co-located SGLang server standing in.
         */
        fun hasModelServerSignal(block: String): Boolean {
            val l = block.lowercase()
            return l.contains("launch_server") || l.contains("--model-path") ||
                l.contains("vllm serve") || l.contains("vllm/vllm-openai") || l.contains("lmsysorg/sglang")
        }

        /**
         * The model id a model-server block serves — for scoping a COMBINED node's
         * compose. Priority: `--served-model-name`, then `--model-path` (weights
         * repo; its vendor matches the served id), then the `nearai.otel.model`
         * label (present on servers whose model is a bare positional, e.g. gpt-oss).
         * null for shared infra (proxy, telemetry, mesh) that serves no model.
         */
        fun servedModel(block: String): String? =
            flagValue("--served-model-name", block) ?: flagValue("--model-path", block) ?: labelValue("nearai.otel.model", block)

        /**
         * A COMBINED multi-model node's compose scoped to the REQUESTED model:
         * removes the service blocks of OTHER models' inference servers (and the
         * `&anchor` blocks only they reference — where their engine `image:` lives,
         * which the provenance service would otherwise regex-grep). Left standing:
         * the requested model's server plus ALL shared infrastructure (E2EE proxy,
         * compose-manager, telemetry, mesh). So the plaintext tiers and the image
         * list reflect only the model you're talking to — the co-located servers
         * never receive your plaintext (the proxy routes by `model`). A single-model
         * compose, a null request, or no other-model server → returned unchanged.
         */
        fun scoped(rawYaml: String, toModel: String?): String {
            val requested = toModel?.takeIf { it.isNotEmpty() } ?: return rawYaml
            val yaml = dockerComposeYaml(rawYaml)
            val anchors = anchorBlocks(yaml)
            val droppedBlocks = ArrayList<String>()
            val droppedAnchors = HashSet<String>()
            val keptAnchors = HashSet<String>()
            for (rawBlock in serviceBlocks(yaml)) {
                val expanded = expand(rawBlock, anchors)
                val refs = anchorRefs(rawBlock)
                val served = if (hasModelServerSignal(expanded)) servedModel(expanded) else null
                if (served != null && NearAIModelCatalog.differentVendor(served, requested)) {
                    droppedBlocks.add(rawBlock); droppedAnchors.addAll(refs)
                } else {
                    keptAnchors.addAll(refs)   // requested server + shared infra
                }
            }
            if (droppedBlocks.isEmpty()) return yaml
            var out = yaml
            for (b in droppedBlocks) out = out.replace(b, "")
            for (name in droppedAnchors - keptAnchors) anchors[name]?.let { out = out.replace(it, "") }
            return out
        }

        /** The `&name` anchors a block references (`<<: *name`, `command: *name`, …). */
        private fun anchorRefs(block: String): Set<String> {
            val refs = HashSet<String>()
            for (rawLine in block.split("\n")) {
                val line = rawLine.trimSpaces()
                if (line.startsWith("#")) continue
                val star = line.indexOf('*'); if (star < 0) continue
                val name = line.substring(star + 1).takeWhile { isAnchorChar(it) }
                if (name.isNotEmpty()) refs.add(name)
            }
            return refs
        }

        /** Value after a `--flag` (or `--flag=value`) in a block's whitespace/newline token stream — the flags live in folded-scalar commands. */
        private fun flagValue(flag: String, block: String): String? {
            val tokens = block.split(' ', '\n', '\r', '\t').filter { it.isNotEmpty() }
                .map { it.trim { c -> c == '"' || c == '\'' || c == ',' || c == '[' || c == ']' } }
            for ((i, t) in tokens.withIndex()) {
                if (t == flag && i + 1 < tokens.size && tokens[i + 1].isNotEmpty()) return tokens[i + 1]
                if (t.startsWith("$flag=")) return t.substring(flag.length + 1)
            }
            return null
        }

        /** Value of a `key: "value"` label line within a block. */
        private fun labelValue(key: String, block: String): String? {
            for (rawLine in block.split("\n")) {
                val line = rawLine.trimSpaces()
                if (!line.startsWith("$key:")) continue
                return line.substring(key.length + 1).trim { it == ' ' || it == '"' || it == '\'' }
            }
            return null
        }
    }
}
