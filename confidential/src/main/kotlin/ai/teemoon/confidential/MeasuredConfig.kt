// ports: teemoon/Confidential/MeasuredConfig.swift @ v1.0.1
package ai.teemoon.confidential

/**
 * The measured-configuration panel for each plaintext-touching image: the
 * safety-relevant launch flags / env, live-parsed from the hash-verified INNER
 * model-layer compose (the document compose-manager actually launched; its
 * SHA256 equals the attested `file_sha256` from the compose-manager action
 * log). Per the audit's principle that "sight is context, not a finding", the
 * payload here is what the measured config *forbids*.
 *
 * Copy rules (from the plaintext-surfacing audit):
 *  • never "can't log" — sglang exposes an unauthenticated /configure_logging
 *    endpoint, so "can't" would be false. The true claim is "not configured to
 *    log — and the operator can't reconfigure it without a measurement change
 *    or a signed action-log trace."
 *  • the binding is "pinned by the attested action log and hash-checked" —
 *    NOT a hardware measurement (mr_config measures the outer harness compose;
 *    the inner document is bound via the compose-manager action log, which
 *    teemoon verifies by hash).
 *
 * Every line renders only what was actually parsed: a "holds" line requires
 * the flag's absence to be established in a recognized service block, and
 * anything found instead is named literally. Pure and testable.
 *
 * TODO(digest-gated audit links): cross-check the measured config against
 * published per-component audits once available. The seam is [Line.links]
 * (populated only with the compose YAML link today — do not fabricate audit URLs).
 */
data class MeasuredConfig(val lines: List<Line>) {

    /** One parsed fact about the toucher's measured configuration. */
    data class Line(
        val id: String,
        val state: State,
        val title: String,
        /** Sentence with backtick-marked literal tokens. */
        val detail: String,
        val links: List<RunLink> = emptyList(),
    ) {
        enum class State {
            /** The safety property holds as configured — soft verified tint. */
            HOLDS,
            /** Something is present that the reader should see named. */
            ATTENTION,
        }
    }

    companion object {
        // MARK: Pinned copy (tested — the two rules the design fixes)

        /** The operator-reconfiguration qualifier. Never "can't log": the honest claim is about configuration + the cost of changing it. */
        const val RECONFIGURE_QUALIFIER =
            "not configured to log — and the operator can't reconfigure it without a measurement change or a signed action-log trace."

        /** How the parsed document is bound. Deliberately not "hardware-measured": the CPU's measurement covers the outer harness compose only. */
        const val BINDING_NOTE =
            "parsed live from the inner compose — pinned by the attested action log and hash-checked on this device. the CPU's boot measurement alone does not cover this file."

        // MARK: Building the panel

        /**
         * The measured configuration for one toucher role, parsed from the
         * hash-verified inner compose. [yamlLink] (the compose YAML at the attested
         * commit) is attached to the engine's logging line so the reader can check
         * the flags themselves. null when the compose has no block matching the
         * role — the panel simply doesn't render rather than claiming anything.
         */
        fun forRole(role: PlaintextExposure.Role, innerComposeYaml: String, yamlLink: RunLink? = null): MeasuredConfig? = when (role) {
            PlaintextExposure.Role.MODEL_SERVER -> engineConfig(innerComposeYaml, yamlLink)
            PlaintextExposure.Role.E2EE_TERMINATOR -> proxyConfig(innerComposeYaml)
        }

        private fun expandedBlocks(yaml: String): List<String> {
            val anchors = PlaintextExposure.anchorBlocks(yaml)
            return PlaintextExposure.serviceBlocks(yaml).map { PlaintextExposure.expand(it, anchors) }
        }

        /** sglang: request logging off, no crash/disk-dump flags, weights pinned. */
        fun engineConfig(innerComposeYaml: String, yamlLink: RunLink? = null): MeasuredConfig? {
            // Anchor expansion first: in production the engine's launch flags live
            // in an `x-…-cmd: &cmd` anchor referenced via `command: *cmd` — the raw
            // block carries no signal.
            val block = expandedBlocks(innerComposeYaml).firstOrNull { PlaintextExposure.hasModelServerSignal(it) } ?: return null
            val tokens = flagTokens(block)
            val lines = ArrayList<Line>()
            val links = listOfNotNull(yamlLink)

            // Request logging. `--log-requests` must be matched as an exact flag —
            // `--log-requests-level` shares the prefix and means something else.
            val logRequestsOn = tokens.any { it == "--log-requests" || it.startsWith("--log-requests=") }
            val level = value("--log-requests-level", tokens)
            if (!logRequestsOn && (level == null || level == "0")) {
                val evidence = if (level == "0") "`--log-requests` absent, `--log-requests-level 0` (the minimum)" else "`--log-requests` absent"
                lines.add(Line("logging", Line.State.HOLDS, "request logging off", "$evidence — $RECONFIGURE_QUALIFIER", links))
            } else {
                val found = listOfNotNull(if (logRequestsOn) "--log-requests" else null, level?.let { "--log-requests-level $it" }).joinToString(", ")
                lines.add(Line("logging", Line.State.ATTENTION, "request-logging flags present",
                    "the launch command carries `$found` — request logging is configured on.", links))
            }

            // Crash / disk dump flags — named literally when present.
            val dumpFlags = tokens.filter { t -> t.startsWith("--") && (t.lowercase().contains("dump") || t.lowercase().contains("crash")) }
            if (dumpFlags.isEmpty()) {
                lines.add(Line("dumps", Line.State.HOLDS, "no crash or disk-dump flags",
                    "no launch flag writes requests or crash state to disk (`dump`/`crash` flags absent from the command)."))
            } else {
                lines.add(Line("dumps", Line.State.ATTENTION, "dump flags present", "the launch command carries `${dumpFlags.joinToString(" ")}`."))
            }

            // Weights pinned to an immutable revision.
            val rev = value("--revision", tokens)
            if (rev != null) {
                lines.add(Line("revision", Line.State.HOLDS, "weights pinned to an immutable revision",
                    "`--revision ${rev.take(12)}…` — the exact weight files are fixed; a swapped model is a different document hash."))
            } else {
                lines.add(Line("revision", Line.State.ATTENTION, "weights not revision-pinned",
                    "the launch command carries no `--revision` — the model path could serve moving weights."))
            }
            return MeasuredConfig(lines)
        }

        /** vllm-proxy-rs: OHTTP on — E2EE terminates here, by literal env. */
        fun proxyConfig(innerComposeYaml: String): MeasuredConfig? {
            val block = expandedBlocks(innerComposeYaml).firstOrNull { PlaintextExposure.hasTerminatorSignal(it) } ?: return null
            val ohttp = envValue("OHTTP_ENABLED", block)
            val line = when {
                ohttp?.lowercase() == "true" -> Line("ohttp", Line.State.HOLDS, "end-to-end encryption terminates here",
                    "`OHTTP_ENABLED=true` is literal in this compose — your sealed request is decrypted only inside this service, next to the model.")
                ohttp != null -> Line("ohttp", Line.State.ATTENTION, "OHTTP not enabled",
                    "this block sets `OHTTP_ENABLED=$ohttp` — the E2EE terminator role isn't backed by this value.")
                // Terminator signal came from TLS_CERT only; say what's known.
                else -> Line("ohttp", Line.State.ATTENTION, "OHTTP flag not found",
                    "this block terminates TLS but sets no `OHTTP_ENABLED` — the E2EE claim rests on the key-binding check above, not this compose.")
            }
            return MeasuredConfig(listOf(line))
        }

        // MARK: Group-level invariant — allowed_envs (outer measured compose)

        /**
         * Env names the operator can set without a new measurement, parsed from the
         * outer app_compose's `allowed_envs` (JSON array or YAML list form). null
         * when no `allowed_envs` section is present — no claim then.
         */
        fun allowedEnvs(outerManifest: String): List<String>? {
            val key = outerManifest.indexOf("allowed_envs"); if (key < 0) return null
            val after = outerManifest.substring(key + "allowed_envs".length)
            // JSON form: "allowed_envs": ["A", "B", …] — only when the bracket
            // directly follows the key (just quote/colon/whitespace between).
            val open = after.indexOf('[')
            if (open >= 0 && after.substring(0, open).all { it in "\": \n\t" }) {
                val close = after.indexOf(']', open)
                if (close >= 0) {
                    return after.substring(open + 1, close).split(',')
                        .map { it.trim { c -> c in " \"'\n\t" } }.filter { it.isNotEmpty() }
                }
            }
            // YAML list form:
            //   allowed_envs:
            //     - NAME
            val names = ArrayList<String>()
            var seenList = false
            for (rawLine in after.split("\n").drop(1)) {
                val line = rawLine.trim { it == ' ' || it == '\t' }
                when {
                    line.startsWith("- ") -> { seenList = true; names.add(line.substring(2).trim { it in " \"'" }) }
                    line.isEmpty() || line.startsWith("#") -> continue
                    else -> break   // next key ends the list
                }
            }
            return if (seenList) names else null
        }

        /** The allowed envs that look like logging/debug switches — the invariant is that this comes back empty. */
        fun loggingSuspects(allowedEnvs: List<String>): List<String> = allowedEnvs.filter { name ->
            val u = name.uppercase()
            listOf("LOG", "DEBUG", "TRACE", "DUMP", "VERBOSE").any { u.contains(it) }
        }

        /**
         * The group-level caption for the model enclave: whether any operator-
         * settable env (no measurement change needed) is a logging/debug switch.
         * null when the outer manifest exposes no allowed_envs to check — teemoon
         * then makes no claim.
         */
        fun allowedEnvsCaption(outerManifest: String): String? {
            val envs = allowedEnvs(outerManifest) ?: return null
            val suspects = loggingSuspects(envs)
            if (suspects.isEmpty()) {
                return "none of the ${envs.size} operator-settable env vars (`allowed_envs`, outer measured compose) is a logging or debug switch — every log knob in the launch config is a literal the operator can't reach without a new measurement or a signed action-log trace."
            }
            return "operator-settable env vars include ${suspects.joinToString(", ") { "`$it`" }} — a logging/debug-shaped switch reachable without a measurement change."
        }

        // MARK: Egress (disclosed limit)

        /**
         * Whether the compose shows NO network-layer egress confinement: no
         * `internal: true` network anywhere in the document. true = the absence is
         * established (disclose it); false = some confinement is declared. This
         * derives a *disclosed limit*, not a finding — confinement of the
         * plaintext-handling containers rests on their audited code behavior.
         */
        fun lacksEgressConfinement(composeYaml: String): Boolean =
            composeYaml.split("\n").none { it.trim { c -> c == ' ' || c == '\t' }.startsWith("internal: true") }

        // MARK: Token helpers (launch-command parsing, list or inline form)

        /** Flattens a service block into whitespace tokens with YAML list markers and quotes stripped — the same normalization ModelArtifact uses, local so the two parsers stay independently testable. */
        private fun flagTokens(block: String): List<String> {
            val tokens = ArrayList<String>()
            for (rawLine in block.split('\n', '\r')) {
                var line = rawLine.trim { it == ' ' || it == '\t' }
                if (line.startsWith("- ")) line = line.substring(2) else if (line == "-") continue
                for (part in line.split(' ')) {
                    val t = part.trim { it == '"' || it == '\'' || it == ',' || it == '[' || it == ']' }
                    if (t.isNotEmpty()) tokens.add(t)
                }
            }
            return tokens
        }

        /** The value following [flag], supporting `--flag value` and `--flag=value`. */
        private fun value(flag: String, tokens: List<String>): String? {
            for ((i, t) in tokens.withIndex()) {
                if (t == flag && i + 1 < tokens.size) return tokens[i + 1]
                if (t.startsWith("$flag=")) return t.substring(flag.length + 1)
            }
            return null
        }

        /** The literal value of `NAME=` in the block's environment entries (or the `NAME: value` mapping form). */
        private fun envValue(name: String, block: String): String? {
            for (rawLine in block.split("\n")) {
                var line = rawLine.trim { it == ' ' || it == '\t' }
                if (line.startsWith("- ")) line = line.substring(2)
                line = line.trim { it == '"' || it == '\'' }
                if (line.startsWith("$name=")) return line.substring(name.length + 1).trim { it in " \"'" }
                if (line.startsWith("$name:")) return line.substring(name.length + 1).trim { it in " \"'" }
            }
            return null
        }
    }
}
