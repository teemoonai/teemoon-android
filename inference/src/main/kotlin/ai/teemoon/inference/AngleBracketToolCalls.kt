// ports: teemoon/Inference/SSEParser.swift @ v1.0.1
//   (parseAngleBracketToolCalls, recoverKwargCollapse, resolveParam,
//    extractKwargValue, stripTextToolCalls)
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `<tool_call>…</tool_call>` parser (Qwen/GLM + JSON / `<function=>` /
 * `<arg_key>` / kwarg-collapse-recovery variants).
 *
 * Four branches, tried in order, because a model below its own grammar emits
 * whichever it likes. The fourth is RECOVERY of a malformation the server's
 * strict parser cannot normalize — see [recoverKwargCollapse].
 */
object AngleBracketToolCalls {

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(content: String, tools: List<RecoveryToolSpec> = emptyList()): Map<Int, ToolCallState> {
        val result = LinkedHashMap<Int, ToolCallState>()
        var idx = 0
        var search = 0
        while (true) {
            val open = content.indexOf(OPEN, search)
            if (open < 0) break
            val close = content.indexOf(CLOSE, open + OPEN.length)
            if (close < 0) break
            val inner = content.substring(open + OPEN.length, close).trim()

            // The index advances per <tool_call> BLOCK, not per successful parse —
            // Swift does this with `defer`, so a skipped block still consumes an
            // index. Keep it: the ids are positional.
            parseOne(inner, idx, tools)?.let { result[idx] = it }
            idx += 1
            search = close + CLOSE.length
        }
        return result
    }

    private fun parseOne(inner: String, idx: Int, tools: List<RecoveryToolSpec>): ToolCallState? {
        jsonVariant(inner, idx)?.let { return it }
        xmlAttributeVariant(inner, idx)?.let { return it }
        argKeyValueVariant(inner, idx)?.let { return it }
        return recoverKwargCollapse(inner, idx, tools)
    }

    /** `{"name": "...", "arguments": {...}}` */
    private fun jsonVariant(inner: String, idx: Int): ToolCallState? {
        val obj = runCatching { json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        val name = runCatching { obj["name"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        if (name.isEmpty()) return null
        val args = obj["arguments"]?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
        return ToolCallState(id = "text_tool_$idx", name = name, arguments = args)
    }

    /** `<function=name><parameter=key>value</parameter></function>` */
    private fun xmlAttributeVariant(inner: String, idx: Int): ToolCallState? {
        val fnPrefix = inner.indexOf("<function=")
        if (fnPrefix < 0) return null
        val nameStart = fnPrefix + "<function=".length
        val nameEnd = inner.indexOf('>', nameStart)
        if (nameEnd < 0) return null
        val funcName = inner.substring(nameStart, nameEnd)
        if (funcName.isEmpty()) return null

        val bodyEnd = inner.indexOf("</function>").let { if (it < 0) inner.length else it }
        val body = inner.substring(minOf(nameEnd + 1, bodyEnd), bodyEnd)

        val args = LinkedHashMap<String, String>()
        var paramSearch = 0
        while (true) {
            val p = body.indexOf("<parameter=", paramSearch)
            if (p < 0) break
            val keyStart = p + "<parameter=".length
            val keyEnd = body.indexOf('>', keyStart)
            if (keyEnd < 0) break
            var paramName = body.substring(keyStart, keyEnd)
            // Swift strips a doubled "parameter=" prefix here; keep the quirk.
            if (paramName.startsWith("parameter=")) paramName = paramName.removePrefix("parameter=")
            val valueStart = keyEnd + 1
            val valueEnd = body.indexOf("</parameter>", valueStart)
            if (valueEnd < 0) break
            args[paramName] = body.substring(valueStart, valueEnd).trim()
            paramSearch = valueEnd + "</parameter>".length
        }
        return ToolCallState(id = "text_tool_$idx", name = funcName, arguments = encode(args))
    }

    /** `name<arg_key>k</arg_key><arg_value>v</arg_value>…` */
    private fun argKeyValueVariant(inner: String, idx: Int): ToolCallState? {
        val nameEnd = inner.indexOf("<arg_key>").let { if (it < 0) inner.length else it }
        val funcName = inner.substring(0, nameEnd).trim()
        if (funcName.isEmpty()) return null

        val args = LinkedHashMap<String, String>()
        var search = 0
        while (true) {
            val keyOpen = inner.indexOf("<arg_key>", search)
            if (keyOpen < 0) break
            val keyClose = inner.indexOf("</arg_key>", keyOpen + "<arg_key>".length)
            if (keyClose < 0) break
            val valOpen = inner.indexOf("<arg_value>", keyClose + "</arg_key>".length)
            if (valOpen < 0) break
            val valClose = inner.indexOf("</arg_value>", valOpen + "<arg_value>".length)
            if (valClose < 0) break
            val k = inner.substring(keyOpen + "<arg_key>".length, keyClose).trim()
            val v = inner.substring(valOpen + "<arg_value>".length, valClose).trim()
            if (k.isNotEmpty()) args[k] = v
            search = valClose + "</arg_value>".length
        }
        // Empty args is NOT a parse — Swift falls through to kwarg recovery.
        if (args.isEmpty()) return null
        return ToolCallState(id = "text_tool_$idx", name = funcName, arguments = encode(args))
    }

    /**
     * Recovers a kwarg-collapse malformed call (`NAME_ARGKEY="value"`) — function
     * name and arg key fused into one token, Python kwarg syntax, no
     * `<arg_key>`/`<arg_value>` tags, often a dangling `</arg_value>`. The
     * server's strict `glm47` regex cannot normalize this; teemoon can, because
     * it knows the registered tool schema.
     *
     * Returns null (skip) when no registered tool prefix-matches or no value is
     * extractable — the low-confidence tail is left to containment, not to
     * guessing.
     */
    fun recoverKwargCollapse(inner: String, idx: Int, tools: List<RecoveryToolSpec>): ToolCallState? {
        if (tools.isEmpty()) return null
        val eq = inner.indexOf('=')
        if (eq < 0) return null
        val head = inner.substring(0, eq).trim()
        if (head.isEmpty()) return null
        // Longest-prefix match: web_search wins over web_search_query_simple,
        // which is not a registered tool.
        val tool = tools.filter { head == it.name || head.startsWith(it.name) }
            .maxByOrNull { it.name.length } ?: return null
        var remainder = head.removePrefix(tool.name)
        while (remainder.isNotEmpty() && (remainder[0] == '_' || remainder[0] == ' ' || remainder[0] == '.')) {
            remainder = remainder.substring(1)
        }
        val key = resolveParam(remainder, tool)
        val value = extractKwargValue(inner.substring(eq + 1)) ?: return null
        return ToolCallState(id = "text_tool_$idx", name = tool.name, arguments = encode(mapOf(key to value)))
    }

    /**
     * Maps a (possibly hallucinated) arg-key token to a real parameter: exact
     * match → the token; else the single required param; else the first declared
     * param; else the token itself.
     */
    internal fun resolveParam(token: String, tool: RecoveryToolSpec): String {
        if (tool.paramNames.contains(token)) return token
        if (tool.requiredParams.size == 1) return tool.requiredParams[0]
        tool.paramNames.firstOrNull()?.let { return it }
        return if (token.isEmpty()) "input" else token
    }

    /**
     * Extracts a kwarg value: a double-quoted string if present, else the run up
     * to the first stray XML tag / end. Strips a dangling `</arg_value>` and
     * surrounding quotes.
     */
    internal fun extractKwargValue(tail: String): String? {
        val s = tail.trim()
        val open = s.indexOf('"')
        if (open >= 0) {
            val close = s.indexOf('"', open + 1)
            if (close >= 0) return s.substring(open + 1, close)
        }
        val cut = s.indexOf('<').let { if (it < 0) s else s.substring(0, it) }
        val v = cut.trim('"', ' ', '\n')
        return v.ifEmpty { null }
    }

    /** Removes every `<tool_call>…</tool_call>` region, then trims. */
    fun strip(content: String): String {
        var result = content
        while (true) {
            val open = result.indexOf(OPEN)
            if (open < 0) break
            val close = result.indexOf(CLOSE)
            if (close < 0) break
            result = result.removeRange(open, close + CLOSE.length)
        }
        return result.trim()
    }

    private fun encode(args: Map<String, String>): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject {
            for ((k, v) in args) put(k, JsonPrimitive(v))
        })
}
