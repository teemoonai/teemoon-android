// ports: teemoon/Inference/GenerationEngine.swift @ v1.0.4
package ai.teemoon.inference

import ai.teemoon.confidential.ResponseVerification

import kotlin.math.truncate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A tool the model may call. */
interface Tool {
    val name: String
    val description: String
    /** JSON Schema for the arguments object — the schema teemoon sends the model. */
    val parametersSchema: String
    suspend fun execute(argumentsJson: String): String
}

/**
 * What one model turn produced.
 *
 * This is deliberately *post-parse*: the transport has already turned bytes
 * (or tokens) into text and tool calls. What it must NOT do is execute those
 * tool calls or decide whether to loop — that belongs to the engine.
 */
data class TurnResult(
    val content: String,
    val reasoning: String = "",
    val toolCalls: Map<Int, ToolCallState> = emptyMap(),
    /** True when the calls came from text markup rather than the structured field. */
    val isTextBasedToolCall: Boolean = false,
    /**
     * True when generation stopped because it ran out of budget, not because
     * the model finished. Only the transport knows its own cap.
     */
    val wasTruncated: Boolean = false,
    val completionTokens: Int? = null,
    /** Transport-specific reporting and verification for this turn. */
    val report: TurnReport = TurnReport(),
)

/** One request/response turn. Implementations own the wire; the engine owns the loop. */
interface GenerationTransport {
    suspend fun runTurn(
        messages: List<Map<String, Any>>,
        includeTools: Boolean,
        tools: List<Tool>,
        onPartial: (String) -> Unit,
    ): TurnResult
}

/**
 * What the UI is told while a turn runs. Errors are NOT a callback — the
 * engine throws, and the stream's owner publishes; one channel, no dedup.
 */
interface StreamCallbacks {
    fun onText(text: String)
    /** "Working, nothing to show yet" — true from request to this turn's first visible char. */
    fun onAwaitingModel(turn: Int, awaiting: Boolean) {}
    /** Android-only: one executed call for the transcript's tool-round list. */
    fun onToolRound(call: ToolCallState, result: String) {}
    /** Where iOS writes a warning/error log line. Never a silent drop. */
    fun onWarning(message: String) {}
    /**
     * The turn's text is FINAL — the stream ended and every tool round ran —
     * but the reply-signature check has not yet run (it waits up to six
     * seconds for the TEE to store the signature). Persist the answer here:
     * an app closed inside that window otherwise loses a reply it already
     * showed (seen live 2026-09-03). iOS persists on stream end likewise.
     */
    fun onTurnText(text: String) {}
    /** Sources for the rail — from tool results and from self-grounding providers alike. */
    fun onSourcesFound(sources: List<GroundingSource>) {}
    /** The `query` argument of each tool call about to run, in call order. */
    fun onQueriesFound(queries: List<String>) {}
    fun onToolExecutionEnded() {}
    /**
     * The developer-panel payload. Delivered once per tool round (so the panel
     * shows the round even if a later one fails) and once at the end with the
     * answer and the verification result.
     */
    fun onSuccess(result: RequestResult) {}
}

/**
 * The transport-agnostic generation loop: ask a [GenerationTransport] for one
 * turn, execute any tool calls it requested, feed the results back, repeat
 * until the model answers.
 *
 * Everything in here is the part that must NOT differ between a model running
 * on near.ai and the same weights running on the phone:
 *   - which tools are offered, and for how many rounds
 *   - `$ref` flattening of the argument schemas (small models can't resolve it)
 *   - string→scalar argument coercion against the schema we actually sent
 *   - the shape of the follow-up messages (structured vs. text-based)
 *   - the reasoning-only fallback
 *   - residual tool-markup containment
 *   - self-grounding providers' citation split
 *
 * The transport owns bytes/tokens and parsing; this owns meaning. Errors are
 * thrown, never side-channeled.
 *
 * This is the loop, and it lives HERE rather than in a view model on purpose:
 * iOS paid for every guard in it, and a second copy grown next to the UI is a
 * second engine to keep in step.
 */
class GenerationEngine(
    private val transport: GenerationTransport,
    private val tools: List<Tool> = emptyList(),
    private val events: StreamCallbacks,
    /**
     * Tool-call follow-up rounds allowed after the initial request.
     *
     * Configurable because the right number depends on **whose GPU pays**. A
     * hosted model can afford 4 rounds; on device each round is a full
     * re-prefill plus a serial decode on the phone, so 4 rounds compounds into
     * minutes of saturated GPU — enough to heat the device and take the app
     * down with it.
     */
    private val maxToolRounds: Int = ToolRoundPolicy.DEFAULT_MAX_ROUNDS,
    /**
     * Whether the answering provider grounds its own answers and so interleaves
     * citation markup into `content` (Brave Answers). A single Boolean rather
     * than the whole Provider: it is the only thing the loop needs to know
     * about who is answering, and an on-device model has no Provider to hand
     * over.
     */
    private val groundsItsOwnAnswers: Boolean = false,
    /**
     * GenerationEngine.swift: `results.flatMap { BraveWebSearchTool.parseSources(from: $0.content) }` — every tool
     * result is read for `<source>` entries so the reply can carry its sources. This defaulted to nothing for a
     * month and no grounded reply on Android ever showed a source (found by GroundedTurnTest, 2026-09-08).
     */
    private val sourcesInToolResult: (String) -> List<GroundingSource> = BraveWebSearchTool::parseSources,
) {

    /**
     * Answer text as the user should see it: provider markup removed. Identity
     * for every other provider, so no shared path changes shape.
     */
    private fun displayText(raw: String): String =
        if (groundsItsOwnAnswers) BraveAnswersFormat.split(raw).visible else raw

    // MARK: - The loop

    suspend fun run(initialMessages: List<WireMessage>, prompt: String): String {
        val messages = ArrayList<Map<String, Any>>()
        initialMessages.forEach { messages.add(mapOf("role" to it.role, "content" to it.content)) }
        messages.add(mapOf("role" to "user", "content" to prompt))

        val rounds = ToolRoundPolicy(maxToolRounds)
        var textToolCallRoundHappened = false
        val allToolRecords = ArrayList<ToolCallRecord>()
        var textSoFar = ""
        var toldSearchesAreOver = false
        var turnIndex = 0

        while (true) {
            val includeTools =
                tools.isNotEmpty() && !textToolCallRoundHappened && !rounds.exhausted

            // The tools are about to disappear from the request. Say so, in the
            // turn that is already being sent — see SEARCHES_EXHAUSTED_NOTICE.
            //
            // roundsUsed > 0 because a budget of zero would otherwise open the
            // conversation by telling the model it had used up searches it never
            // got. Nothing ships with that today (4 hosted, 2 on device) and this
            // is the guard that keeps it from becoming a bug if something does.
            // The rest of the rule — and why a trailing user turn skips it — is
            // ToolRoundPolicy.shouldTellModelSearchesAreOver.
            if (rounds.shouldTellModelSearchesAreOver(includeTools, messages.lastOrNull()?.get("role") as? String, toldSearchesAreOver)) {
                messages.add(mapOf(
                    "role" to "user",
                    "content" to ToolRoundPolicy.SEARCHES_EXHAUSTED_NOTICE))
                toldSearchesAreOver = true
            }

            // Partial text is per-turn; everything before it is ours.
            val prefix = textSoFar
            // "Working, nothing to show yet" — from here to this turn's first
            // visible character. The UI cannot infer this: after a tool round
            // the transcript already holds text, so nothing changes on screen
            // while the follow-up request flies and its prompt prefills.
            turnIndex += 1
            val thisTurn = turnIndex
            var producedText = false
            events.onAwaitingModel(thisTurn, true)

            val turn = try {
                transport.runTurn(messages, includeTools, tools) { partial ->
                    if (partial.isNotEmpty() && !producedText) {
                        producedText = true
                        events.onAwaitingModel(thisTurn, false)
                    }
                    events.onText(prefix + displayText(partial))
                }
            } finally {
                events.onAwaitingModel(thisTurn, false)
            }

            // Tool round: execute, append follow-up messages, loop.
            //
            // The budget check is on the LOOP, not just on includeTools.
            // Withholding tools only stops teemoon ASKING — it does not stop a
            // model answering with a tool call anyway, and text recovery will
            // happily parse one out of raw output whether or not tools were
            // offered. Without this guard such a model loops forever, executing
            // real tool calls (real web searches) every round. Small on-device
            // models are likeliest to do it: no server-side template decides
            // when tool syntax is even legal.
            if (turn.toolCalls.isNotEmpty() && rounds.consume()) {
                val round = executeToolRound(
                    toolCalls = turn.toolCalls,
                    completionTokens = turn.completionTokens,
                    report = turn.report,
                    priorRecords = allToolRecords,
                )
                allToolRecords += round.records
                appendFollowUpMessages(
                    messages, turn.content, turn.toolCalls, round.results, turn.isTextBasedToolCall)
                textSoFar += turn.content
                if (textSoFar.isNotEmpty()) events.onText(textSoFar)
                if (turn.isTextBasedToolCall) textToolCallRoundHappened = true
                continue
            }
            if (turn.toolCalls.isNotEmpty()) {
                // Budget spent and the model is still asking. Stop here and
                // answer with what we have; the containment net below strips the
                // unexecuted call so it can't surface as visible markup.
                events.onWarning(
                    "round budget exhausted — model still requested ${turn.toolCalls.size} " +
                        "tool call(s); ending the generation")
            }

            // Finished. A self-grounding provider's answer carries its citations
            // inline; split them out so the saved text is prose and the citations
            // reach the same sources rail the web-search tool feeds.
            if (groundsItsOwnAnswers) {
                val split = BraveAnswersFormat.split(turn.content)
                textSoFar += split.visible
                if (split.sources.isNotEmpty()) events.onSourcesFound(split.sources)
            } else {
                textSoFar += turn.content
            }

            // Reasoning-model fallback: some models stream their entire visible
            // answer as reasoning — DeepSeek V4 Flash under reasoning_content,
            // and every *thinking* model on Ollama under reasoning — leaving
            // content empty for the whole stream. Without this the reply is silently
            // dropped: a blank message and no thread preview.
            //
            // The fallback also has to be EMITTED, not just persisted. Nothing
            // was streamed (there was no content to stream), so the UI is still
            // showing an empty answer at this point; onText is what puts the
            // text on screen. Observed live with gemma4:e2b-it-qat and
            // qwen3.5:4b, whose replies are 100% reasoning frames.
            //
            // UNLESS the turn was TRUNCATED. "The answer arrived as reasoning"
            // and "the model spiralled until it ran out of budget" both look like
            // empty content plus a full reasoning field, and only the second must
            // be withheld — its reasoning is a scratchpad cut off mid-thought,
            // not a reply.
            //
            // Measured on device: Qwen3-0.6B looping on the injected date
            // produced 3,616 characters over exactly 1024 tokens, ending
            // "...asking about the 2026 F". teemoon displayed that as the
            // assistant's answer. Showing nothing is better; showing a stated
            // failure is better still, which is what the empty reply becomes
            // once onSuccess sees no text.
            if (textSoFar.isBlank() && turn.reasoning.isNotBlank()) {
                if (turn.wasTruncated) {
                    events.onWarning(
                        "reasoning-only reply hit the token budget — withholding it: " +
                            "a truncated scratchpad is not an answer")
                } else {
                    textSoFar = turn.reasoning
                    events.onText(textSoFar)
                }
            }

            // Containment net: strip any residual tool-call markup recovery didn't
            // consume (unregistered tool, no handler yet, truncated call) so it can
            // never leak into the answer. Log removals — never a silent drop.
            val sanitized = ToolMarkupContainment.sanitize(textSoFar)
            if (sanitized.removed.isNotEmpty()) {
                events.onWarning(
                    "[containment] stripped ${sanitized.removed.size} residual tool-markup " +
                        "fragment(s) from the answer (unrecovered/unknown format): " +
                        sanitized.removed.take(4).joinToString(" | "))
            }
            val finalText = sanitized.clean
            // Emit the canonical text last, so the final snapshot the UI keeps
            // is exactly what gets persisted.
            //
            // Load-bearing for on-device: the local transport streams RAW text so
            // the live view can render its "thinking…" block, but a local model's
            // raw stream contains <think> and the saved message must not.
            // Without this the last partial would win and the tags would be
            // stored. It also means any residual tool markup containment
            // stripped can never survive in the visible answer.
            // Unconditional: textSoFar is already the clean accumulation, so
            // comparing against it would skip the emit precisely when the last
            // *partial* was the raw one — which is the case this exists for.
            events.onText(finalText)
            events.onTurnText(finalText)
            val report = turn.report
            // Await the check here so the turn's result includes it. A
            // detached task forced the view model to poll the debug info for
            // 8s after generate returned.
            val verification = report.verify?.invoke()
            when (verification) {
                null, is ResponseVerification.Verified -> Unit
                is ResponseVerification.Unverified -> when (val reason = verification.reason) {
                    is ResponseVerification.UnverifiedReason.GatewayTrustOnly -> events.onWarning(
                        "[TEE] Accepted via gateway trust only — signer ${reason.signingAddress} " +
                            "not individually attested")
                    is ResponseVerification.UnverifiedReason.SignatureMismatch -> events.onWarning(
                        "[TEE] Signature MISMATCH — expected ${reason.expected}, got ${reason.got}")
                    is ResponseVerification.UnverifiedReason.ContentMismatch -> events.onWarning(
                        "[TEE] Content binding MISMATCH — ${reason.detail}")
                    // "not yet available" is an info-level line on iOS; it is
                    // carried by the result, not raised as a warning.
                    ResponseVerification.UnverifiedReason.SignatureUnavailable -> Unit
                    is ResponseVerification.UnverifiedReason.FetchFailed -> events.onWarning(
                        "[TEE] Signature fetch failed: ${reason.error}")
                }
            }
            // iOS LOGS this (HTTPTransport.swift); here the warning rides the callback so the owner sees it without
            // a log — an Android divergence, and a deliberate one: see docs/ANDROID_NATIVE.md. The provider is named
            // as iOS names it, because a user with several places configured cannot act on "the server".
            report.promptBudget?.takeIf { it.looksTruncated }?.let {
                val who = report.providerName?.let { n -> "$n " }.orEmpty()
                events.onWarning("[context] ${who}evaluated only ${it.evaluated} input tokens of an estimated ${it.sentEstimate} — the server is discarding the front of the prompt (system prompt and question go first). Raise its context window; for Ollama that is OLLAMA_CONTEXT_LENGTH.")
            }
            events.onSuccess(RequestResult(
                url = report.url, requestHeaders = report.requestHeaders,
                requestBodyJson = report.requestBodyJson,
                sealedBodyJson = report.sealedBodyJson,
                responseBody = finalText.ifEmpty { null },
                // Plus anything the transport ran on its own behalf — see
                // TurnReport.executedToolCalls.
                toolCalls = allToolRecords + report.executedToolCalls,
                outputTokens = turn.completionTokens,
                firstTokenAt = report.firstTokenAt,
                firstVisibleTokenAt = report.firstVisibleTokenAt,
                promptBudget = report.promptBudget,
                teeVerification = verification,
                isE2eeActive = report.isE2eeActive,
            ))
            return finalText
        }
    }

    // MARK: - Tool execution

    private class RoundOutcome(
        val records: List<ToolCallRecord>,
        /** (tool_call_id, result content), in call order. */
        val results: List<Pair<String, String>>,
    )

    private suspend fun executeToolRound(
        toolCalls: Map<Int, ToolCallState>,
        completionTokens: Int?,
        report: TurnReport,
        priorRecords: List<ToolCallRecord>,
    ): RoundOutcome {
        val sortedIndices = toolCalls.keys.sorted()

        val queries = sortedIndices.mapNotNull { idx ->
            val normalized = normalizeToolArgsJson(toolCalls.getValue(idx).arguments)
            parseJsonObject(normalized)?.get("query")?.stringOrNull()
        }
        events.onQueriesFound(queries)

        val resultMap: Map<Int, Pair<String, String>> = try {
            coroutineScope {
                sortedIndices.map { idx ->
                    val tc = toolCalls.getValue(idx)
                    async { idx to (tc.id to executeToolCall(tc.name, tc.arguments)) }
                }.awaitAll().toMap()
            }
        } catch (e: Throwable) {
            events.onToolExecutionEnded()
            throw e
        }
        val results = sortedIndices.mapNotNull { resultMap[it] }

        val roundRecords = sortedIndices.mapNotNull { idx ->
            val tc = toolCalls.getValue(idx)
            resultMap[idx]?.let { ToolCallRecord(tc.name, tc.arguments, it.second) }
        }
        sortedIndices.forEach { idx ->
            resultMap[idx]?.let { events.onToolRound(toolCalls.getValue(idx), it.second) }
        }

        // Publish intermediate debug info so the developer panel shows the
        // tool round even if a later round fails.
        val allRecords = priorRecords + roundRecords
        if (allRecords.isNotEmpty()) {
            events.onSuccess(RequestResult(
                url = report.url, requestHeaders = report.requestHeaders,
                requestBodyJson = report.requestBodyJson,
                sealedBodyJson = report.sealedBodyJson, responseBody = null,
                toolCalls = allRecords, outputTokens = completionTokens, promptBudget = report.promptBudget,
                teeVerification = null, isE2eeActive = report.isE2eeActive,
            ))
        }

        val sources = results.flatMap { sourcesInToolResult(it.second) }
        if (sources.isNotEmpty()) events.onSourcesFound(sources)
        events.onToolExecutionEnded()
        return RoundOutcome(roundRecords, results)
    }

    private suspend fun executeToolCall(name: String, argsJson: String): String {
        val tool = tools.firstOrNull { it.name == name } ?: return "[Tool '$name' not found]"
        return executeTool(tool, normalizeToolArgsJson(argsJson))
    }

    private suspend fun executeTool(tool: Tool, argumentsJson: String): String {
        // Some models (notably GLM on near.ai) emit numeric/boolean arguments as
        // JSON strings — e.g. {"count": "15"} instead of {"count": 15}. Strict
        // typed decoding then throws a type mismatch. Coerce string scalars
        // back to the types declared in the tool's schema first. The reference
        // is the tool's own schema — the exact schema we sent the model — so
        // there is a single source of truth for argument types.
        val coercedJson = toolSchemaJson(tool)
            ?.let { coerceArgsToSchema(argumentsJson, it) } ?: argumentsJson
        if (runCatching { json.parseToJsonElement(coercedJson) }.isFailure) {
            return "[Failed to parse tool arguments JSON]"
        }
        return try {
            tool.execute(coercedJson)
        } catch (e: CancellationException) {
            // Kotlin-specific: cancellation is structured concurrency's signal,
            // not a tool failure, and must never be turned into result text.
            throw e
        } catch (e: LlmError) {
            if (e.isKeyRejection) throw e
            // The prefix names web_search for any tool: the model-facing wording was measured with it (iOS keeps it too).
            "[web_search error: ${e.userMessage} Try rephrasing the query or omitting the freshness filter.]"
        } catch (e: Exception) {
            "[Tool call failed: $e]"
        }
    }

    // MARK: - Follow-up construction

    private fun appendFollowUpMessages(
        messages: MutableList<Map<String, Any>>,
        assistantContent: String,
        toolCalls: Map<Int, ToolCallState>,
        toolResults: List<Pair<String, String>>,
        isTextBased: Boolean,
    ) {
        if (isTextBased) {
            if (assistantContent.isNotEmpty()) {
                messages.add(mapOf("role" to "assistant", "content" to assistantContent))
            }
            val combined = toolResults.joinToString("\n\n---\n\n") { it.second }
            messages.add(mapOf(
                "role" to "user",
                "content" to "Search results:\n\n$combined\n\nPlease answer based on these results."))
        } else {
            val openAiCalls = toolCalls.keys.sorted().map { idx ->
                val tc = toolCalls.getValue(idx)
                mapOf(
                    "id" to tc.id, "type" to "function",
                    "function" to mapOf("name" to tc.name, "arguments" to tc.arguments))
            }
            messages.add(mapOf(
                "role" to "assistant", "content" to assistantContent, "tool_calls" to openAiCalls))
            toolResults.forEach { (id, content) ->
                messages.add(mapOf("role" to "tool", "tool_call_id" to id, "content" to content))
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun parseJsonObject(text: String): JsonObject? =
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

        private fun JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * Swift's `.whitespaces` — space separators and tab, NOT newlines.
         * Kotlin's `trim()` would also eat newlines, and "15\n" is a different
         * (unparseable) argument on iOS.
         */
        private fun String.trimWhitespaces(): String = trim {
            it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt()
        }

        /**
         * A decimal number in the forms `Double(String)` accepts on iOS
         * ("20", "20.0", ".5", "5.", "1e5", signed). Java's parser also takes
         * `d`/`f` suffixes and hex floats, which Swift does not, so this is the
         * gate that keeps "15d" a string on both platforms.
         */
        private val decimal = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
        private fun parseDouble(s: String): Double? =
            if (decimal.matches(s)) s.toDoubleOrNull() else null

        /**
         * Distills a registered tool into the minimal schema the client-side
         * recovery parser needs (name + parameter names + required) so it can
         * disambiguate malformed tool calls the server parser couldn't
         * normalize. Derived from the same schema teemoon sends in the request.
         */
        fun recoverySpec(tool: Tool): RecoveryToolSpec {
            val schema = toolSchemaJson(tool)
            val params = (schema?.get("properties") as? JsonObject)?.keys?.toList() ?: emptyList()
            val required = (schema?.get("required") as? JsonArray)
                ?.mapNotNull { it.stringOrNull() } ?: emptyList()
            return RecoveryToolSpec(tool.name, params, required)
        }

        /** Models sometimes wrap real arguments in {"parameters": "<json string>"} — unwrap. */
        internal fun normalizeToolArgsJson(argsJson: String): String {
            val obj = parseJsonObject(argsJson) ?: return argsJson
            val parametersStr = obj["parameters"]?.stringOrNull() ?: return argsJson
            val inner = parseJsonObject(parametersStr) ?: return argsJson
            return inner.toString()
        }

        // MARK: - Tool schemas

        /**
         * The tool's argument schema as a plain JSON object — the same schema
         * teemoon sends the model in the request. Used as the type reference
         * for [coerceArgsToSchema]. null when the tool's schema is not a JSON
         * object.
         */
        fun toolSchemaJson(tool: Tool): JsonObject? = parseJsonObject(tool.parametersSchema)

        /**
         * Inlines the `$ref`/`$defs` indirection a generated schema carries
         * into a plain, self-contained JSON Schema for the wire. The raw form is
         * `{"$defs": {"Name": {…}}, "$ref": "#/$defs/Name"}`; a small model (or
         * the server's prompt template) can't resolve a top-level `$ref`
         * rendered into the prompt, so it can't see the real parameters and
         * hallucinates the argument shape — verified: with the `$ref` form
         * gemma4:e2b emits `{"queries": [...]}` 5/5 at temp 0; flattened, it
         * emits the declared `{"query": "..."}` 5/5. Cloud/vLLM servers tolerate
         * `$ref`, so this is a net-safe normalization: flat is more broadly
         * compatible for everyone.
         *
         * On-device this matters more, not less: there is no server-side
         * template to paper over it, and every model small enough to run on a
         * phone is in exactly the size class that gets this wrong.
         */
        fun flattenedToolSchema(tool: Tool): JsonObject {
            val raw = toolSchemaJson(tool)
                ?: return JsonObject(mapOf("type" to JsonPrimitive("object")))
            val defs = raw["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
            return inlineRefs(raw, defs)
        }

        /**
         * Resolves a node's `$ref` against `defs`, drops `$ref`/`$defs`, and
         * recurses into `properties` and array `items` so nested references are
         * inlined too.
         */
        private fun inlineRefs(node: JsonObject, defs: JsonObject): JsonObject {
            val schema = LinkedHashMap<String, JsonElement>(resolveRef(node, defs))
            schema.remove("\$ref")
            schema.remove("\$defs")
            (schema["properties"] as? JsonObject)?.let { props ->
                schema["properties"] = JsonObject(props.mapValues { (_, v) ->
                    (v as? JsonObject)?.let { inlineRefs(it, defs) } ?: v
                })
            }
            (schema["items"] as? JsonObject)?.let { items ->
                schema["items"] = inlineRefs(items, defs)
            }
            return JsonObject(schema)
        }

        /**
         * Coerces string-encoded scalar arguments to the types declared in the
         * tool's schema, tolerating models that emit numbers/booleans as strings
         * (`"count": "15"` → `"count": 15`). Recurses into object properties and
         * array items, resolving `$ref`/`$defs` indirection in the schema.
         * Unresolvable refs and unparseable values are left untouched, and the
         * input is returned unchanged if it isn't valid JSON.
         */
        fun coerceArgsToSchema(argsJson: String, schema: JsonObject): String {
            val obj = runCatching { json.parseToJsonElement(argsJson) }.getOrNull() ?: return argsJson
            val defs = schema["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
            val out = coerceValue(obj, schema, defs)
            // iOS re-serializes with JSONSerialization, which refuses a
            // top-level scalar and hands the input back unchanged.
            if (out !is JsonObject && out !is JsonArray) return argsJson
            return out.toString()
        }

        /**
         * Resolves a `{"$ref": "#/$defs/Name"}` node against `defs`; returns the
         * schema unchanged when there is no ref or the target is missing.
         */
        private fun resolveRef(schema: JsonObject, defs: JsonObject): JsonObject {
            val ref = schema["\$ref"]?.stringOrNull() ?: return schema
            val name = ref.substringAfterLast('/')
            return defs[name] as? JsonObject ?: schema
        }

        private fun coerceValue(value: JsonElement, rawSchema: JsonObject, defs: JsonObject): JsonElement {
            val schema = resolveRef(rawSchema, defs)
            return when (schema["type"]?.stringOrNull()) {
                "object" -> {
                    val dict = value as? JsonObject ?: return value
                    val props = schema["properties"] as? JsonObject ?: return value
                    val out = LinkedHashMap<String, JsonElement>(dict)
                    for ((key, propSchemaAny) in props) {
                        val v = dict[key] ?: continue
                        val propSchema = propSchemaAny as? JsonObject ?: continue
                        out[key] = coerceValue(v, propSchema, defs)
                    }
                    JsonObject(out)
                }
                "array" -> {
                    val arr = value as? JsonArray ?: return value
                    val itemSchema = schema["items"] as? JsonObject ?: return value
                    JsonArray(arr.map { coerceValue(it, itemSchema, defs) })
                }
                "integer" -> {
                    val s = value.stringOrNull()?.trimWhitespaces() ?: return value
                    s.toLongOrNull()?.let { return JsonPrimitive(it) }
                    // Some models emit whole numbers with a decimal point ("20.0"); accept
                    // when finite, integral, and representable as a 64-bit Int. Out-of-range
                    // values (e.g. Int.max + 1) fall through and decode fails loudly, unchanged.
                    val d = parseDouble(s) ?: return value
                    if (d.isFinite() && truncate(d) == d &&
                        d >= -9.223372036854775808E18 && d < 9.223372036854775808E18
                    ) {
                        JsonPrimitive(d.toLong())
                    } else {
                        value
                    }
                }
                "number" -> {
                    // The isFinite guard is load-bearing: "NaN"/"inf"/"-Infinity" all
                    // parse as a Double, but a non-finite number crashes the
                    // serializer — on iOS with an ObjC exception `try?` cannot catch,
                    // here with a SerializationException the tool would never see.
                    val s = value.stringOrNull() ?: return value
                    val d = parseDouble(s.trimWhitespaces())
                    if (d != null && d.isFinite()) JsonPrimitive(d) else value
                }
                "boolean" -> {
                    val s = value.stringOrNull() ?: return value
                    when (s.trimWhitespaces().lowercase()) {
                        "true" -> JsonPrimitive(true)
                        "false" -> JsonPrimitive(false)
                        else -> value
                    }
                }
                else -> value
            }
        }
    }
}
