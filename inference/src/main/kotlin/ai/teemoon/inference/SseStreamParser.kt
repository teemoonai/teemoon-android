// ports: teemoon/Inference/SSEParser.swift @ v1.0.1 (the event/delta half)
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** One thing the wire said. */
sealed interface SseEvent {
    /** Visible answer text. */
    data class Content(val text: String) : SseEvent
    /** Thinking/CoT tokens, under whichever key the server used. */
    data class Reasoning(val key: String, val text: String) : SseEvent
    /**
     * A fragment of a STRUCTURED tool call. The OpenAI path streams these
     * piecewise: the name arrives once, the arguments JSON arrives in slices
     * that must be concatenated in order. Text-marker recovery is the FALLBACK
     * for models that emit calls as prose; this is the primary path.
     */
    data class ToolCallFragment(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?,
    ) : SseEvent

    /** `data: [DONE]` — the stream is semantically over. */
    data object Done : SseEvent

    /** The server said why it stopped. "tool_calls" means a round is expected. */
    data class Finish(val reason: String) : SseEvent
}

/**
 * Incremental SSE parser: bytes in, typed events out.
 *
 * Buffers across chunk boundaries on purpose. A `data:` event is NOT guaranteed
 * to arrive whole — the network splits wherever it likes, and a parser that
 * assumes one read equals one event drops or corrupts the delta that happened
 * to straddle the boundary.
 *
 * Divergence from Swift, deliberate: the iOS parser sits in a proxy position and
 * forwards raw bytes to AnyLanguageModel, so it re-serializes events. Here the
 * client is ours, so the parser emits typed events instead of bytes. The
 * *parsing* rules are the port; the forwarding machinery has no counterpart.
 */
class SseStreamParser(private val now: () -> Instant = Instant::now) {

    private val json = Json { ignoreUnknownKeys = true }
    private var buffer = StringBuilder()
    var sawDone: Boolean = false
        private set

    /** `id` of the completion, for the response-signature lookup (`/v1/signature/{id}`). */
    var chatCompletionId: String? = null
    /** `usage.completion_tokens` / `usage.prompt_tokens`, when the server reports them. */
    var completionTokens: Int? = null
    var promptTokens: Int? = null
    /**
     * `firstDeltaAt` is the honest time-to-first-token; `firstVisibleAt` is when the user could first see
     * anything, and the gap between them is the model thinking. Both read the raw delta, before decryption.
     */
    var firstDeltaAt: Instant? = null
        private set
    var firstVisibleAt: Instant? = null
        private set
    /**
     * Set when the E2EE decrypt hook failed on any field. The transport treats
     * this as fatal and fails the stream — ciphertext is never rendered as
     * content, never handed to a tool, never shown as thinking.
     */
    var decryptFailed: Boolean = false
        private set
    /** The first field that failed to decrypt (ciphertext, truncated) — for the error report, never for display. */
    var decryptFailureSample: String? = null
        private set

    /** Structured calls, assembled across fragments. Keyed by wire index. */
    private val assembling = LinkedHashMap<Int, ToolCallState>()
    val toolCalls: Map<Int, ToolCallState> get() = assembling

    /**
     * [decrypt] is the E2EE hook: applied to every delta field the model sealed
     * (content, reasoning, tool name and argument slices) BEFORE assembly, so
     * argument slices concatenate as plaintext. A null answer marks
     * [decryptFailed]; the field is dropped, never passed through as ciphertext.
     */
    fun consume(chunk: String, decrypt: ((String) -> String?)? = null): List<SseEvent> {
        this.decrypt = decrypt
        buffer.append(chunk)
        val out = ArrayList<SseEvent>()
        while (true) {
            val sep = buffer.indexOf("\n\n")
            if (sep < 0) break
            val event = buffer.substring(0, sep)
            buffer.delete(0, sep + 2)
            // Keep the RAW line before any interpretation. iOS's response
            // signature binds over the raw SSE lines rejoined with "\n",
            // blank separators included — reconstructing them later from parsed
            // values does not reproduce the signed bytes.
            parseEvent(event.trim())?.let(out::addAll)
        }
        return out
    }

    private var decrypt: ((String) -> String?)? = null

    /** Runs the decrypt hook when one is set; a failure is recorded and the field withheld. */
    private fun opened(field: String?): String? {
        if (field == null) return null
        val d = decrypt ?: return field
        val plain = d(field)
        if (plain == null) { decryptFailed = true; if (decryptFailureSample == null) decryptFailureSample = field.take(120) }
        return plain
    }

    private fun parseEvent(line: String): List<SseEvent>? {
        if (!line.startsWith("data: ")) return null   // comments, keep-alives
        val payload = line.removePrefix("data: ").trim()

        if (payload == "[DONE]") {
            sawDone = true
            return listOf(SseEvent.Done)
        }

        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        obj.str("id")?.let { chatCompletionId = it }
        runCatching { obj["usage"]?.jsonObject }.getOrNull()?.let { usage ->
            runCatching { usage["completion_tokens"]?.jsonPrimitive?.content?.toInt() }.getOrNull()?.let { completionTokens = it }
            runCatching { usage["prompt_tokens"]?.jsonPrimitive?.content?.toInt() }.getOrNull()?.let { promptTokens = it }
        }
        val delta = runCatching {
            obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
        }.getOrNull() ?: return null

        val out = ArrayList<SseEvent>()
        // Timestamps, at the one place every delta of every kind passes.
        val reasoning = reasoningChunk(delta)
        val rawContent = delta.str("content")
        if (firstDeltaAt == null && (reasoning != null || !rawContent.isNullOrEmpty())) firstDeltaAt = now()
        if (firstVisibleAt == null && !rawContent.isNullOrEmpty()) firstVisibleAt = now()
        reasoning?.let { (key, text) -> opened(text)?.let { out.add(SseEvent.Reasoning(key, it)) } }
        val content = rawContent?.let { opened(it) }
        if (!content.isNullOrEmpty()) out.add(SseEvent.Content(content))

        // Structured tool calls arrive as fragments: the name once, the
        // arguments JSON in slices that must be concatenated IN ORDER.
        runCatching { delta["tool_calls"]?.jsonArray }.getOrNull()?.forEach { el ->
            val tc = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            val index = runCatching { tc["index"]?.jsonPrimitive?.content?.toInt() }.getOrNull() ?: 0
            val id = tc.str("id")
            val fn = runCatching { tc["function"]?.jsonObject }.getOrNull()
            val name = fn?.str("name")?.let { opened(it) }
            val argsDelta = fn?.str("arguments")?.let { opened(it) }

            val prior = assembling[index] ?: ToolCallState(id = "call_$index")
            assembling[index] = prior.copy(
                id = id?.takeIf { it.isNotEmpty() } ?: prior.id,
                name = name?.takeIf { it.isNotEmpty() } ?: prior.name,
                arguments = prior.arguments + (argsDelta ?: ""),
            )
            out.add(SseEvent.ToolCallFragment(index, id, name, argsDelta))
        }

        runCatching {
            obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("finish_reason")?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }
            ?.let { out.add(SseEvent.Finish(it)) }

        return out
    }

    companion object {
        /**
         * Reasoning arrives under different keys by server: DeepSeek and most
         * OpenAI-compatible servers use `reasoning_content`; **Ollama (0.32+)
         * uses `reasoning`**. Reading only one key silently drops the entire
         * chain of thought for the other family.
         */
        val reasoningKeys = listOf("reasoning_content", "reasoning")

        fun reasoningChunk(delta: JsonObject): Pair<String, String>? {
            for (key in reasoningKeys) delta.str(key)?.let { return key to it }
            return null
        }

        /**
         * A non-empty STRING field, or null. A JSON `null` is absent, not the
         * word "null": reasoning models send `"content": null` on thinking
         * deltas, and reading it as text put a literal "null" into the answer —
         * and, under E2EE, a field that could never decrypt (observed live
         * against near.ai, 2026-09-03).
         */
        fun JsonObject.str(key: String): String? {
            val v = this[key] as? JsonPrimitive ?: return null
            if (v is JsonNull || !v.isString) return null
            return v.content.takeIf { it.isNotEmpty() }
        }
    }
}
