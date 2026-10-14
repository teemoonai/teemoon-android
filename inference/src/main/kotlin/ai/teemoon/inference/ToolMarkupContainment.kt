// ports: teemoon/Inference/SSEParser.swift @ v1.0.1 (sanitizeToolMarkup)
package ai.teemoon.inference

/**
 * Final CONTAINMENT pass, run on the user-visible answer AFTER recovery.
 *
 * This is the guarantee the original leak lacked: it removes residual tool-call
 * markup independent of whether any parse succeeded, across every known model
 * family, so an unrecognised or unrecoverable form — a malformed call for an
 * unregistered tool, a family with no handler yet, a truncated call — can never
 * reach the user. Returns the cleaned text AND what was removed, because the
 * caller must log a non-empty removal: containment stays observable, never a
 * silent drop.
 */
object ToolMarkupContainment {

    private val pairedBlocks = listOf(
        "<tool_call>.*?</tool_call>",                        // GLM / Qwen
        "<｜tool▁calls▁begin｜>.*?<｜tool▁calls▁end｜>",         // DeepSeek V3/V3.1 (Unicode)
        "<｜DSML｜tool_calls>.*?</｜DSML｜tool_calls>",          // DeepSeek V4 DSML
        "<｜DSML｜function_calls>.*?</｜DSML｜function_calls>",  // DeepSeek V3.2 DSML
        "```tool_code.*?```",                                // Gemma 3
        "```tool_output.*?```",                              // Gemma 3
    )

    private val openerTags = listOf(
        "<function=[^>]*>", "<parameter=[^>]*>",
        "<｜DSML｜invoke[^>]*>", "<｜DSML｜parameter[^>]*>",
    )

    private val orphanTokens = listOf(
        "<tool_call>", "</tool_call>",
        "<arg_key>", "</arg_key>", "<arg_value>", "</arg_value>",
        "</function>", "</parameter>",
        "<｜tool▁calls▁begin｜>", "<｜tool▁calls▁end｜>",
        "<｜tool▁call▁begin｜>", "<｜tool▁call▁end｜>", "<｜tool▁sep｜>",
        "</｜DSML｜invoke>", "</｜DSML｜parameter>",
        "</｜DSML｜tool_calls>", "</｜DSML｜function_calls>",
        "<|start|>", "<|end|>", "<|message|>", "<|channel|>",
        "<|constrain|>", "<|call|>", "<|return|>",
    )

    data class Result(val clean: String, val removed: List<String>)

    fun sanitize(text: String): Result {
        var s = text
        val removed = ArrayList<String>()

        for (p in pairedBlocks) {
            s = removeMatches(Regex(p, setOf(RegexOption.DOT_MATCHES_ALL)), s, removed)
        }
        for (p in openerTags) {
            s = removeMatches(Regex(p), s, removed)
        }
        for (t in orphanTokens) {
            if (s.contains(t)) {
                removed.add(t)
                s = s.replace(t, "")
            }
        }
        return Result(s.trim(), removed)
    }

    private fun removeMatches(re: Regex, s: String, into: MutableList<String>): String {
        val hits = re.findAll(s).map { it.value }.toList()
        if (hits.isEmpty()) return s
        into.addAll(hits)
        return re.replace(s, "")
    }
}
