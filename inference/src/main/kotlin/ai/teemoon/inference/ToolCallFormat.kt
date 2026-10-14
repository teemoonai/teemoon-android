// ports: teemoon/Inference/ToolCallFormat.swift @ v1.0.1
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The pluggable text tool-call formats: marker dispatch plus the Gemma
 * ```tool_code python-call parser (the format teemoon could not read before,
 * which made Gemma look incapable of tools).
 *
 * Which text format a model emits is the MODEL's choice, not the server's, so
 * teemoon cannot assume one. Each entry declares the marker(s) that signal it
 * and how its region closes; adding a model family means adding an entry, and
 * the `when` over this enum is exhaustive so the compiler flags what else must
 * learn about it.
 */
enum class ToolCallFormat {
    /** `<tool_call>` — Qwen / GLM (+ JSON / `<function=>` / `<arg_key>` / kwarg-collapse). */
    ANGLE_BRACKET,

    /** ```tool_code — Gemma python calls. */
    GEMMA_TOOL_CODE;

    /**
     * Substrings whose presence signals this format — used both to suppress the
     * raw syntax from the visible reply and to route parsing.
     */
    val markers: List<String>
        get() = when (this) {
            ANGLE_BRACKET -> listOf("<tool_call>")
            GEMMA_TOOL_CODE -> listOf("```tool_code")
        }

    /**
     * The marker that CLOSES a region one of this format's [markers] opened.
     *
     * Streaming elision suppresses from an opening marker through this close and
     * then RESUMES forwarding — the fix for the sticky suppression that withheld
     * the whole second half of a prose→call→prose turn until [DONE]. Exhaustive
     * on purpose: adding a format without declaring how its region ends must not
     * compile, because a family the elision cannot close would silently regress
     * to end-of-turn batching.
     */
    val closingMarker: String
        get() = when (this) {
            ANGLE_BRACKET -> "</tool_call>"
            // Gemma's fence closes with a bare ``` — the elision only searches for
            // it AFTER the opening ```tool_code, so the opener cannot self-close.
            GEMMA_TOOL_CODE -> "```"
        }

    /** Parse tool calls out of the full accumulated content in this format. */
    fun parse(content: String, tools: List<RecoveryToolSpec>): Map<Int, ToolCallState> =
        when (this) {
            ANGLE_BRACKET -> AngleBracketToolCalls.parse(content, tools)
            GEMMA_TOOL_CODE -> GemmaToolCode.parse(content)
        }

    companion object {
        /** Every marker across formats — for the (cheap) suppression scan. */
        val allMarkers: List<String> = entries.flatMap { it.markers }

        /**
         * Every (open, close) pair across formats, so the streaming elision knows
         * both where a markup region starts and where prose resumes.
         */
        val markerPairs: List<Pair<String, String>> =
            entries.flatMap { fmt -> fmt.markers.map { it to fmt.closingMarker } }

        /** Longest marker, so the streaming boundary scan keeps a large-enough tail. */
        val maxMarkerLength: Int = allMarkers.maxOfOrNull { it.length } ?: 1

        /** Parse using the first format whose marker appears and yields at least one call. */
        fun parseAny(content: String, tools: List<RecoveryToolSpec>): Map<Int, ToolCallState> {
            for (fmt in entries) {
                if (fmt.markers.none { content.contains(it) }) continue
                val calls = fmt.parse(content, tools)
                if (calls.isNotEmpty()) return calls
            }
            return emptyMap()
        }
    }
}

/** Gemma's ```tool_code fenced python calls. */
object GemmaToolCode {

    private val json = Json

    fun parse(content: String): Map<Int, ToolCallState> {
        val result = LinkedHashMap<Int, ToolCallState>()
        var idx = 0
        var search = 0
        while (true) {
            val open = content.indexOf("```tool_code", search)
            if (open < 0) break
            val after = open + "```tool_code".length
            val close = content.indexOf("```", after)
            val body = content.substring(after, if (close >= 0) close else content.length)
            search = if (close >= 0) close + "```".length else content.length
            for (call in pythonCalls(body)) {
                result[idx] = ToolCallState(id = "text_tool_$idx", name = call.first, arguments = call.second)
                idx += 1
            }
            if (search >= content.length) break
        }
        return result
    }

    /**
     * Extract `name(args…)` calls (stripping a `print(…)` wrapper) and JSON-encode
     * the kwargs. One call per line / semicolon.
     */
    fun pythonCalls(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (raw in body.split('\n', ';')) {
            var line = raw.trim()
            if (line.startsWith("print(") && line.endsWith(")")) {
                line = line.removePrefix("print(").dropLast(1)
            }
            val openParen = line.indexOf('(')
            if (openParen < 0 || !line.endsWith(")")) continue
            val name = line.substring(0, openParen).trim()
            if (name.isEmpty()) continue
            if (!name.all { it.isLetter() || it.isDigit() || it == '_' || it == '.' }) continue
            val argStr = line.substring(openParen + 1, line.length - 1)
            val encoded = json.encodeToString(JsonObject.serializer(), parseKwargs(argStr))
            // Names can be dotted (functions.web_search) — take the last segment.
            out.add(name.substringAfterLast('.') to encoded)
        }
        return out
    }

    /** `k="v", k2=3, k3=true` → JSON object, splitting on top-level commas only. */
    fun parseKwargs(s: String): JsonObject = buildJsonObject {
        for (part in splitTopLevel(s, ',')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (key.isNotEmpty()) put(key, pythonLiteral(value))
        }
    }

    /** Split on [sep] when not inside quotes or brackets. */
    fun splitTopLevel(s: String, sep: Char): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var inStr: Char? = null
        for (ch in s) {
            val q = inStr
            if (q != null) {
                cur.append(ch)
                if (ch == q) inStr = null
                continue
            }
            when {
                ch == '"' || ch == '\'' -> { inStr = ch; cur.append(ch) }
                ch == '(' || ch == '[' || ch == '{' -> { depth += 1; cur.append(ch) }
                ch == ')' || ch == ']' || ch == '}' -> { depth -= 1; cur.append(ch) }
                ch == sep && depth == 0 -> { parts.add(cur.toString()); cur.clear() }
                else -> cur.append(ch)
            }
        }
        if (cur.toString().trim().isNotEmpty()) parts.add(cur.toString())
        return parts
    }

    /**
     * A Python literal → JSON value: quoted → String, true/false → Bool,
     * int/float → number, else the raw token as a string.
     */
    fun pythonLiteral(v: String): JsonPrimitive {
        if (v.length >= 2 &&
            ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
        ) {
            return JsonPrimitive(v.substring(1, v.length - 1))
        }
        when (v) {
            "true", "True" -> return JsonPrimitive(true)
            "false", "False" -> return JsonPrimitive(false)
        }
        v.toIntOrNull()?.let { return JsonPrimitive(it) }
        v.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(v)
    }
}
