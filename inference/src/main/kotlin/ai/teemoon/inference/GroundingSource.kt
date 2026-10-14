// ports: teemoon/Inference/BraveWebSearchTool.swift @ v1.0.4 (GroundingSource, readableSnippet,
//        displaySnippet, isStructuredData, citedOrAll, encodedJSON)
package ai.teemoon.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A single web source returned by grounding — one row of the sources rail.
 *
 * Fed by two paths that must land in the same place: the web-search tool's
 * results, and a self-grounding provider's inline citations
 * ([BraveAnswersFormat]). `snippet` defaults to "" for backward-compatible
 * decoding of stored JSON that predates the field.
 */
@Serializable
data class GroundingSource(
    val url: String,
    val domain: String,
    val title: String,
    val snippet: String = "",
) {
    val id: String get() = url

    val displaySnippet: String
        get() = if (isStructuredData(snippet)) "" else snippet

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(serializer())

        /** The persisted form on a message (iOS Message.sourcesJSON). */
        fun encodeList(sources: List<GroundingSource>): String = json.encodeToString(listSerializer, sources)
        /** Bad or missing JSON is an empty rail, never a crash (iOS Message.groundingSources). */
        fun decodeList(text: String?): List<GroundingSource> = text?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()

        private val skipKeys = setOf("@context", "@type", "@graph", "headers", "caption")

        private val salvagePattern =
            Regex("\"(?:headline|articleBody|description|name|title)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        fun isStructuredData(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            if (t.first() == '{' || t.first() == '[') return true
            if (t.contains("\"@type\":") || t.contains("\"@graph\":")) return true
            if (t.startsWith("\"@")) return true
            return false
        }

        /**
         * Prose for a passage that arrived as JSON-LD.
         *
         * `displaySnippet` hides structured data from the citation UI, which is
         * right for a preview — but the model was being handed the blob verbatim,
         * and that is worst for the queries that need it most. The captured
         * "weather 10001" response carries the entire forecast as
         * `{"title":…,"table":[{"DEWPOINT":"40°"},{"HUMIDITY":"93%"},…]}`, so the
         * model had to parse JSON to read a temperature.
         *
         * Unpacks to `key: value` prose. Non-structured text is returned trimmed
         * and unchanged; anything that unpacks to nothing returns "" so the caller
         * can drop the passage rather than emit an empty `<content>`.
         */
        fun readableSnippet(text: String): String {
            val t = text.trim()
            if (t.isEmpty()) return ""
            if (!isStructuredData(t)) return t
            return try {
                flatten(json.parseToJsonElement(t))
            } catch (_: Exception) {
                // A truncated blob is still worth mining: Brave cuts passages mid-object,
                // so the prose-bearing keys are usually present even when the JSON is not
                // parseable as a whole.
                salvageFragments(t)
            }
        }

        /**
         * Depth-first `key: value` rendering. Keys are visited in sorted order —
         * JSON objects have no stable order, and without this the same
         * passage would render differently between runs.
         */
        private fun flatten(node: JsonElement): String {
            return when (node) {
                is JsonObject -> {
                    val parts = ArrayList<String>()
                    for (key in node.keys.sorted()) {
                        // Presentational scaffolding, not content.
                        if (key in skipKeys) continue
                        val child = flatten(node.getValue(key))
                        if (child.isNotEmpty()) parts.add("$key: $child")
                    }
                    parts.joinToString("; ")
                }
                is JsonArray -> {
                    val parts = ArrayList<String>()
                    for (item in node) {
                        val child = flatten(item)
                        if (child.isNotEmpty()) parts.add(child)
                    }
                    parts.joinToString("; ")
                }
                JsonNull -> ""
                is JsonPrimitive -> node.content.trim()
            }
        }

        /** Pulls the prose-bearing values out of JSON that will not parse. */
        private fun salvageFragments(text: String): String {
            val passages = salvagePattern.findAll(text)
                .map { match ->
                    match.groupValues[1]
                        .replace("\\/", "/")
                        .replace("\\\"", "\"")
                        .trim()
                }
                .filter { it.isNotEmpty() }
            return passages.joinToString("; ")
        }

        /**
         * Sources to persist with an assistant message: prefers links the model
         * explicitly cited in [output], enriched with snippets from the full
         * grounding results; falls back to all grounding sources when the model
         * cited nothing.
         */
        fun citedOrAll(output: String, grounding: List<GroundingSource>): List<GroundingSource> {
            val cited = BraveWebSearchTool.parseMarkdownLinks(output)
            if (cited.isEmpty()) return grounding
            val snippetsByUrl = LinkedHashMap<String, String>()
            for (s in grounding) snippetsByUrl.putIfAbsent(s.url, s.snippet)
            return cited.map { source ->
                val snippet = snippetsByUrl[source.url].orEmpty()
                if (snippet.isEmpty()) source
                else GroundingSource(source.url, source.domain, source.title, snippet)
            }
        }

        /** JSON encoding for Message.sourcesJSON; null when there is nothing to save. */
        fun encodedJSON(sources: List<GroundingSource>): String? {
            if (sources.isEmpty()) return null
            return try {
                json.encodeToString(sources)
            } catch (_: Exception) {
                null
            }
        }
    }
}
