// ports: teemoon/Confidential/ModelArtifact.swift @ v1.0.4 (canonicalRepo: the HF-cache path names the repo, not the snapshot)
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog

/**
 * The pinned model artifact, parsed from the hash-verified model-layer compose
 * YAML (the inner `*.yaml` the compose-manager launches inside the model
 * enclave). This is the difference between what the app is *told* it's talking
 * to — the served alias, which the provider controls and can misstate — and
 * what is *actually* loaded: a specific HuggingFace repo at an immutable
 * revision, with a real quantization.
 *
 * The payoff is drift detection: when the served alias advertises a different
 * quantization than the pinned weights actually are (e.g. served as `…-FP8`
 * while the pinned path is `QuantTrio/GLM-5.1-AWQ`), the alias understates the
 * quantization — honest disclosure the user would otherwise have to read the
 * compose to discover.
 *
 * Pure and testable: [parse] takes text, returns a value.
 */
data class ModelArtifact(
    /** The pinned weights: a HuggingFace repo path, e.g. "QuantTrio/GLM-5.1-AWQ". */
    val modelPath: String,
    /** The immutable revision the weights are pinned to (HF commit), if given. */
    val revision: String?,
    /** The alias the provider serves this model under — the string apps see. Provider-controlled; may mislead. */
    val servedName: String?,
    /** Quantization derived from the *pinned model path* (never the alias), e.g. "AWQ". null when the path carries no recognizable tag. */
    val quant: String?,
) {
    /** The clean model name for display — the pinned path with its vendor prefix and quant suffix stripped: "QuantTrio/GLM-5.1-AWQ" → "GLM-5.1". */
    val baseModelName: String
        get() {
            val afterSlash = modelPath.split('/').lastOrNull { it.isNotEmpty() } ?: modelPath
            val q = quant ?: return afterSlash
            for (sep in listOf("-", "_")) {
                val suffix = "$sep$q"
                if (afterSlash.uppercase().endsWith(suffix.uppercase())) return afterSlash.dropLast(suffix.length)
            }
            return afterSlash
        }

    /** The quantization tag the *served alias* implies (may differ from the real one — that difference is the drift). */
    val servedQuant: String? get() = servedName?.let { quantTag(it) }

    /** True when the served alias advertises a different quantization than the pinned weights actually are. Informational, not a failure. */
    val quantDrift: Boolean
        get() {
            val q = quant ?: return false
            val served = servedQuant ?: return false
            return q.uppercase() != served.uppercase()
        }

    /** One-line disclosure of the drift, for display. */
    val driftNote: String?
        get() {
            if (!quantDrift) return null
            val q = quant ?: return null
            val served = servedName ?: return null
            return "served as `$served`, but the pinned weights are `$modelPath` ($q)."
        }

    companion object {
        /**
         * Known quantization tags, matched on token boundaries so "FP8" never
         * matches inside "FP16". Longer, more specific tokens are listed so a
         * boundary hit is unambiguous.
         */
        private val quantTokens = setOf(
            "AWQ", "GPTQ", "GGUF", "EETQ", "MXFP4",
            "FP8", "FP16", "FP4", "BF16",
            "INT8", "INT4", "W4A16", "W8A8", "W4A8",
        )

        // MARK: Parsing

        /**
         * Extracts the model artifact from a compose YAML by reading the inference
         * server's launch flags — `--model-path`, `--revision`, `--served-model-name`
         * — wherever they appear (inline command string or YAML list form, with or
         * without `=`). Returns null if no `--model-path` is present.
         */
        fun parse(composeYaml: String): ModelArtifact? {
            val tokens = flatten(composeYaml)
            val declared = value("--model-path", tokens) ?: return null
            val (modelPath, snapshot) = canonicalRepo(declared)
            return ModelArtifact(
                modelPath = modelPath,
                revision = value("--revision", tokens) ?: snapshot,
                servedName = value("--served-model-name", tokens),
                quant = quantTag(modelPath))
        }

        /**
         * Every model server declared in the compose — one [ModelArtifact] per
         * `--model-path`. near.ai runs several models through one reused node via
         * COMBINED composes (e.g. `dsv4-qwen36-gemma4.yaml` runs DeepSeek + Qwen +
         * Gemma), so a parse that took the first `--model-path` mis-identified the
         * model. Each declaration is paired with the `--served-model-name` /
         * `--revision` that follow it, up to the next declaration.
         */
        fun parseAll(composeYaml: String): List<ModelArtifact> {
            val tokens = flatten(composeYaml)
            // A model server declares its weights in one of two forms:
            //   • `--model-path <repo>`            (sglang / older vLLM), or
            //   • `vllm serve <repo>` (positional)  (newer vLLM — e.g. gemma-4).
            // Collect each declaration's (token index, repo path); a compose that
            // mixes forms (combined multi-model nodes do) yields one per server.
            val decls = ArrayList<Pair<Int, String>>()
            for (i in tokens.indices) {
                val t = tokens[i]
                when {
                    t == "--model-path" && i + 1 < tokens.size -> decls.add(i to tokens[i + 1])
                    t.startsWith("--model-path=") -> decls.add(i to t.removePrefix("--model-path="))
                    t == "serve" && i > 0 && tokens[i - 1] == "vllm" && i + 1 < tokens.size && !tokens[i + 1].startsWith("-") ->
                        decls.add(i to tokens[i + 1])
                }
            }
            return decls.mapIndexed { k, (start, path) ->
                val end = if (k + 1 < decls.size) decls[k + 1].first else tokens.size
                val slice = tokens.subList(start, end)
                val (modelPath, snapshot) = canonicalRepo(path)
                ModelArtifact(
                    modelPath = modelPath,
                    revision = value("--revision", slice) ?: snapshot,
                    servedName = value("--served-model-name", slice),
                    quant = quantTag(modelPath))
            }
        }

        /**
         * The artifact for the REQUESTED model on a (possibly multi-model) node: the
         * server whose `--served-model-name` is [requested] — exact first, then
         * same-vendor to tolerate alias spellings (`z-ai` vs `zai-org`). Falls back
         * to the first server when nothing matches or [requested] is null, so it is
         * never worse than [parse].
         */
        fun parse(composeYaml: String, servingModel: String?): ModelArtifact? {
            val all = parseAll(composeYaml)
            if (all.isEmpty()) return null
            val requested = servingModel ?: return all.first()
            all.firstOrNull { it.servedName?.equals(requested, ignoreCase = true) == true }?.let { return it }
            all.firstOrNull { a -> a.servedName?.let { !NearAIModelCatalog.differentVendor(it, requested) } ?: false }?.let { return it }
            return all.first()
        }

        /**
         * A model server may be pointed at a local Hugging Face cache instead of
         * a repo id: `…/hub/models--zai-org--GLM-5.3-Flash/snapshots/84c6a6aa…`.
         * The repo is the `models--<org>--<name>` component and the snapshot is
         * its revision; the last path component is a commit hash, which is what
         * the trust ladder named the model by (GLM-5.3 flash, 2026-09-09 on iOS;
         * the Android sheet still read "encrypted to 84c6a6aa…" on 2026-09-23).
         */
        fun canonicalRepo(path: String): Pair<String, String?> {
            val parts = path.split('/').filter { it.isNotEmpty() }
            val i = parts.indexOfFirst { it.startsWith("models--") }
            if (i < 0) return path to null
            val idParts = parts[i].removePrefix("models--").split("--")
            if (idParts.size < 2) return path to null
            val repo = idParts[0] + "/" + idParts.drop(1).joinToString("--")
            val s = parts.indexOf("snapshots")
            return repo to (if (s >= 0 && s + 1 < parts.size) parts[s + 1] else null)
        }

        /**
         * Flattens compose YAML into an ordered token stream: strips the leading
         * list marker (`- `) from each line, then splits on whitespace and trims
         * surrounding quotes/commas. Handles both the list form (flag and value on
         * separate items) and an inline command string.
         */
        private fun flatten(yaml: String): List<String> {
            val tokens = ArrayList<String>()
            for (rawLine in yaml.split('\n', '\r')) {
                var line = rawLine.trim { it == ' ' || it == '\t' }
                if (line.startsWith("- ")) line = line.substring(2)
                else if (line == "-") continue
                for (part in line.split(' ')) {
                    val t = part.trim { it == '"' || it == '\'' || it == ',' || it == '[' || it == ']' }
                    if (t.isNotEmpty()) tokens.add(t)
                }
            }
            return tokens
        }

        /** The value following [flag] in the token stream, supporting both `--flag value` and `--flag=value`. */
        private fun value(flag: String, tokens: List<String>): String? {
            for ((i, t) in tokens.withIndex()) {
                if (t == flag && i + 1 < tokens.size) return tokens[i + 1]
                if (t.startsWith("$flag=")) return t.substring(flag.length + 1)
            }
            return null
        }

        /** The recognized quantization tag inside a repo/alias string, matched on non-alphanumeric boundaries so "FP8" won't match inside "FP16". */
        fun quantTag(s: String): String? {
            val parts = s.uppercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
            // Prefer a deterministic order so multi-tag strings resolve stably.
            return quantTokens.sorted().firstOrNull { it in parts }
        }
    }
}
