// ports: teemoon/Views/Chat/RequestDebugView.swift @ v1.0.1 (toolCallSignature, toolCallSubtitle,
//        sourcesJSONString, prettyJSONString, compactRequestBody — the pure formatters; the card is app UI)
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How the developer panel labels a tool call. The query is a SUBTITLE, never part of the signature, so a row stays one line. */
object ToolCallDebugFormat {
    private val pretty = Json { prettyPrint = true }

    private fun parseObject(arguments: String): JsonObject? =
        runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    fun toolCallSignature(name: String, arguments: String, result: String = ""): String {
        val json = parseObject(arguments) ?: return "$name($arguments)"
        if (name == "web_search") {
            val parts = arrayListOf(name)
            json.string("freshness")?.let { parts += "fresh:$it" }
            json.string("contextThresholdMode")?.let { if (it != "balanced") parts += "mode:$it" }
            json.int("count")?.let { if (it != 20) parts += "n:$it" }
            val fetched = BraveWebSearchTool.parseSources(from = result).size
            val base = parts.joinToString(" · ")
            return if (fetched > 0) "$base -> $fetched" else base
        }
        if (json.size == 1) {
            val value = json.values.first()
            if (value is JsonPrimitive && value.isString) return "$name(\"${value.content}\")"
        }
        return "$name($arguments)"
    }

    fun toolCallSubtitle(name: String, arguments: String): String? {
        if (name != "web_search") return null
        val query = parseObject(arguments)?.string("query") ?: return arguments.ifEmpty { null }
        return "\"$query\""
    }

    /** The sources a result carried, re-expressed as the Brave response shape the raw-JSON row shows. */
    fun sourcesJSONString(result: String): String? {
        val sources = BraveWebSearchTool.parseSources(from = result)
        if (sources.isEmpty()) return null
        val generic = JsonArray(sources.map { s ->
            JsonObject(sortedMapOf("snippet" to JsonPrimitive(s.snippet), "title" to JsonPrimitive(s.title), "url" to JsonPrimitive(s.url)))
        })
        return pretty.encodeToString(JsonElement.serializer(), JsonObject(mapOf("grounding" to JsonObject(mapOf("generic" to generic)))))
    }

    fun prettyJSONString(string: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), sortKeys(Json.parseToJsonElement(string))) }.getOrDefault(string)

    private fun sortKeys(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
        is JsonArray -> JsonArray(e.map(::sortKeys))
        else -> e
    }

    /** A sealed body is hex blobs: show their edges and size, not 4 KB of ciphertext per field. */
    fun compactRequestBody(json: String): String = HEX_BLOB.replace(json) { m ->
        val hex = m.value.drop(1).dropLast(1)
        "\"${hex.take(8)}…${hex.takeLast(8)}\" (${hex.length / 2} bytes, encrypted)"
    }

    private val HEX_BLOB = Regex("\"[0-9a-f]{64,}\"")
}
