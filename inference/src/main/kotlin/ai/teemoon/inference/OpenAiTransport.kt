// ports: teemoon/Inference/HTTPTransport.swift @ v1.0.1 — REBUILD (§5.3) over OkHttp
//
// The remote transport: one OpenAI-compatible chat/completions round-trip.
//
// Per turn, in order:
//   1. wire-format request construction (messages, tools, extraParams)
//   2. auth (Bearer or provider-specific header)
//   3. optional field-level E2EE sealing (E2EEPeer/E2EEStreamCodec)
//   4. one plain request; SSE parsing via SseStreamParser (with E2EE
//      decryption — decrypt failures FAIL the stream, they are never rendered
//      as message content), or direct JSON handling when the server responds
//      non-streaming
//   5. a `verify` closure that will check the TEE response signature
//
// Everything past that — executing tool calls, chaining rounds, coercing
// arguments — is GenerationEngine.
package ai.teemoon.inference

import ai.teemoon.providers.CredentialHeaders
import ai.teemoon.providers.TeemoonHttp

import ai.teemoon.confidential.AttestationService
import ai.teemoon.confidential.E2EEError
import ai.teemoon.confidential.E2EEStreamCodec
import ai.teemoon.confidential.ResponseVerification
import ai.teemoon.confidential.SignedExchange
import ai.teemoon.confidential.TEESignatureVerifier
import ai.teemoon.confidential.TeeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * One OpenAI-compatible turn.
 *
 * No okhttp-sse, permanently: the response-signature check binds over the RAW
 * SSE lines rejoined with "\n", blank separators included. A library that
 * swallows framing makes that unverifiable, so the framing stays ours.
 *
 * TIMEOUTS ARE AN IDLE BOUND, NOT A TOTAL ONE. iOS is one line —
 * `URLRequest(url: endpoint, timeoutInterval: 240)` (HTTPTransport.swift:74) —
 * and URLSession's timeoutInterval measures the gap BETWEEN packets: a model
 * thinking for two minutes is slow, not dead, but a stream that keeps producing
 * never expires. OkHttp splits that in two, and this had them the wrong way
 * round: `readTimeout(0)` + `callTimeout(240)` bounds the WHOLE call, so a long
 * answer still streaming at four minutes died here and finished on iOS, while a
 * server that accepted and then went silent hung here forever and errored on iOS
 * (2026-09 port review, B5). readTimeout carries the 240; callTimeout is off.
 */
class OpenAiTransport(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String? = null,
    /** null → "Authorization: Bearer <key>"; set → the key is sent as-is under this header (e.g. "X-Subscription-Token"). */
    private val authHeaderName: String? = null,
    private val client: OkHttpClient = TeemoonHttp.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)      // the gap between packets, as URLRequest.timeoutInterval
        .callTimeout(0, TimeUnit.MILLISECONDS)   // no total bound: a long stream must be allowed to finish
        .build(),
    /** The attested context, when this turn promised sealing. null for every non-attested provider. */
    private val teeContext: TeeContext? = null,
    /** Provider extras, auto-coerced: "true"/"false" → Boolean, numbers → Double, else String. */
    private val extraParams: Map<String, String> = emptyMap(),
    /** For error attribution. */
    private val providerName: String = endpoint,
    /** Sleep seam for the signature verifier's waits. */
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
) : GenerationTransport {
    // the header this key goes out under, registered before any turn, so a card copied before the first request masks it
    init { if (!apiKey.isNullOrBlank()) authHeaderName?.let(CredentialHeaders::register) }


    private val json = Json { ignoreUnknownKeys = true }

    /** The raw response text of the last turn, exactly as received, for signature binding. */
    var lastRawTranscript: String = ""
        private set
    /** The last turn's completion id — the key under which the gateway stores its signature. */
    var lastChatCompletionId: String? = null
        private set

    override suspend fun runTurn(
        messages: List<Map<String, Any>>,
        includeTools: Boolean,
        tools: List<Tool>,
        onPartial: (String) -> Unit,
    ): TurnResult = withContext(Dispatchers.IO) {
        val codec = teeContext?.e2eePeer?.let { E2EEStreamCodec(it) }

        // 1. Wire-format request body (plaintext).
        val plaintextBody = makeBody(messages, includeTools, tools)

        // 2. Auth.
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        CredentialHeaders.attach(headers, apiKey.orEmpty(), authHeaderName)

        // 3. E2EE sealing. `codec != null` means THIS turn promised sealing
        // (attested provider with an established E2EE peer), so a sealing
        // failure must fail CLOSED: throw before anything touches the wire.
        // The plaintext path below exists ONLY for `codec == null`
        // (non-attested providers, which must keep sending normally).
        var isE2EEActive = false
        var bodyToSend = plaintextBody
        if (codec != null) {
            val (sealed, e2eeHeaders) = e2eeSealedBody(plaintextBody, codec, headers)
            bodyToSend = sealed; headers.putAll(e2eeHeaders); isE2EEActive = true
        }
        val prettyBody = String(plaintextBody, Charsets.UTF_8)
        // What ACTUALLY went out. Only meaningful when sealing changed the bytes.
        val sealedBody = if (isE2EEActive) String(bodyToSend, Charsets.UTF_8) else null

        // 4. One plain request.
        val request = Request.Builder().url(endpoint).post(bodyToSend.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()

        client.newCall(request).execute().use { response ->
            if (response.code >= 400) {
                val errorBody = runCatching { response.body.string() }.getOrNull()
                throw LlmError(LlmError.Source.Provider(providerName), LlmError.apiErrorMessage(errorBody ?: "", response.code, providerName), httpStatus = response.code,
                    url = endpoint, requestHeaders = headers, requestBodyJson = prettyBody, responseBody = errorBody)
            }
            val source = response.body.source()
            val parser = SseStreamParser()
            // The decrypt hook is non-throwing; failure is recorded on the parser and thrown by the loop.
            val decrypt: ((String) -> String?)? = if (isE2EEActive) codec?.let { c -> { ciphertext: String -> c.decryptField(ciphertext) } } else null
            fun decryptionFailed() = LlmError(LlmError.Source.Provider(providerName),
                "The encrypted response could not be decrypted, so it was not displayed. Try again — if this keeps happening, re-verify the connection from the lock icon.",
                url = endpoint, requestHeaders = headers, requestBodyJson = prettyBody, responseBody = parser.decryptFailureSample?.let { "undecryptable field: $it" }, underlyingError = E2EEError.DecryptionFailed)

            // Elision runs HERE, on the stream, so raw markup never reaches the UI even for a beat.
            val elision = StreamingMarkupElision(enabled = tools.isNotEmpty())
            val raw = StringBuilder()
            val reasoning = StringBuilder()
            var finishReason: String? = null
            // Raw response text for signature content binding: near.ai signs sha256 over the response lines re-joined
            // with "\n" (streaming) or the whole raw body (non-streaming) — captured exactly as received, blank
            // SSE separator lines included.
            val rawResponseText = StringBuilder()
            val isSSE = (response.header("Content-Type") ?: "").contains("text/event-stream")

            if (isSSE) {
                // Lines, not fixed byte blocks: decoding an arbitrary slice as UTF-8 splits multi-byte codepoints.
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    rawResponseText.append(line).append('\n')
                    for (event in parser.consume(line + "\n", decrypt)) {
                        when (event) {
                            is SseEvent.Content -> {
                                raw.append(event.text)
                                val visible = elision.append(event.text)
                                if (visible.isNotEmpty()) onPartial(elision.visibleContent)
                            }
                            is SseEvent.Reasoning -> reasoning.append(event.text)
                            is SseEvent.Finish -> finishReason = event.reason
                            is SseEvent.ToolCallFragment -> Unit   // assembled by the parser
                            SseEvent.Done -> Unit
                        }
                    }
                    if (parser.decryptFailed) throw decryptionFailed()
                    // Stop after the [DONE] *event*, not the [DONE] line: the trailing blank line is in the signed
                    // payload. A keep-alive server never closes; once the terminator is in the text we can leave.
                    if (parser.sawDone && rawResponseText.endsWith("\n\n")) break
                }
                // Stream closed right after `data: [DONE]\n` with no terminator. The signed form still includes the event's blank line.
                if (parser.sawDone && !rawResponseText.endsWith("\n\n")) rawResponseText.append('\n')
                // Stream ended without [DONE]: flush whatever the parser still buffers.
                if (!parser.sawDone) for (event in parser.consume("\n\n", decrypt)) if (event is SseEvent.Content) raw.append(event.text)
                if (parser.decryptFailed) throw decryptionFailed()
            } else {
                // Non-streaming JSON (E2EE servers force stream=false; also any provider that ignores the stream flag).
                val body = source.readUtf8()
                rawResponseText.append(body)
                val out = consumeNonStreamingResponse(body, parser, decrypt, headers, prettyBody, ::decryptionFailed)
                out.content?.let { raw.append(it); elision.append(it) }
                out.reasoning?.let { reasoning.append(it) }
                finishReason = out.finishReason
                if (raw.isNotEmpty() && parser.toolCalls.isEmpty()) onPartial(elision.visibleContent)
            }
            elision.finish().takeIf { it.isNotEmpty() }?.let { onPartial(elision.visibleContent) }
            lastRawTranscript = rawResponseText.toString()

            // Structured calls are the primary path; text recovery is the fallback for models that answer with markup regardless.
            val structured = parser.toolCalls.filterValues { it.name.isNotEmpty() }
            val textCalls = if (structured.isEmpty()) ToolCallFormat.parseAny(raw.toString(), tools.map(GenerationEngine::recoverySpec)) else emptyMap()

            // 5. The signature covers this round's request/response pair. Under E2EE the signer may hash either
            // the wire (encrypted) or plaintext request body — offer both candidates.
            val exchange = SignedExchange(if (isE2EEActive) listOf(bodyToSend, plaintextBody) else listOf(bodyToSend), lastRawTranscript)
            val ctx = teeContext
            val chatID = parser.chatCompletionId
            lastChatCompletionId = chatID
            val verify: (suspend () -> ResponseVerification?)? = if (ctx == null || chatID == null) null else {
                { TEESignatureVerifier.verify(chatID, ctx.attestation.signingAddress, ctx.attestation.gpuSigningAddress,
                    gatewayQuoteVerified = ctx.attestation.quoteVerification?.isVerified == true, exchange = exchange,
                    fetchSignature = { id -> fetchSignature(ctx, id) },
                    refetchSigningAddress = { listOf(ctx.gpuNodeUrl?.let { AttestationService.fetchSigningAddress(it, ctx.apiKey) }) },
                    sleepMs = sleepMs) }
            }

            TurnResult(
                content = elision.visibleContent,
                reasoning = reasoning.toString(),
                toolCalls = structured.ifEmpty { textCalls },
                isTextBasedToolCall = structured.isEmpty() && textCalls.isNotEmpty(),
                wasTruncated = finishReason == "length",
                completionTokens = parser.completionTokens,
                report = TurnReport(url = endpoint, requestHeaders = headers, requestBodyJson = prettyBody, sealedBodyJson = sealedBody,
                    isE2eeActive = isE2EEActive, verify = verify,
                    firstTokenAt = parser.firstDeltaAt, firstVisibleTokenAt = parser.firstVisibleAt,
                    // Input-token accounting. Skipped under E2EE, where the sent-size estimate would be measuring ciphertext.
                    promptBudget = if (isE2EEActive) null else parser.promptTokens?.let { PromptBudget(PromptBudget.estimateSentTokens(messages), it) },
                    providerName = providerName),
            )
        }
    }

    /** `GET <gateway>/signature/{id}` — the gateway stores the GPU node's signature; direct nodes do not reliably sign. */
    private suspend fun fetchSignature(ctx: TeeContext, chatID: String): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(ctx.baseUrl.trimEnd('/') + "/signature/$chatID").get()
        if (ctx.apiKey.isNotEmpty()) b.header("Authorization", "Bearer ${ctx.apiKey}")
        client.newCall(b.build()).execute().use { it.code to it.body.bytes() }
    }

    // MARK: - Request construction

    private fun makeBody(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>): ByteArray {
        val body = LinkedHashMap<String, JsonElement>()
        body["model"] = JsonPrimitive(model)
        body["messages"] = JsonArray(messages.map { it.toJson() })
        body["stream"] = JsonPrimitive(true)
        body["stream_options"] = JsonObject(mapOf("include_usage" to JsonPrimitive(true)))
        if (includeTools && tools.isNotEmpty()) {
            // FLATTENED, as HTTPTransport does on iOS: a `$ref`/`$defs` schema rendered into a small model's prompt
            // makes it hallucinate the argument shape. See GenerationEngine.flattenedToolSchema.
            body["tools"] = JsonArray(tools.map { t ->
                JsonObject(mapOf("type" to JsonPrimitive("function"), "function" to JsonObject(mapOf(
                    "name" to JsonPrimitive(t.name), "description" to JsonPrimitive(t.description), "parameters" to GenerationEngine.flattenedToolSchema(t)))))
            })
            body["tool_choice"] = JsonPrimitive("auto")
        }
        // A whole number must serialize as an integer: JSONSerialization writes Double(512) as `512`, but a Kotlin
        // Double prints `512.0`, which near.ai rejects for max_tokens (HTTP 400, observed live 2026-09-03).
        for ((key, value) in extraParams) body[key] = when (value) {
            "true" -> JsonPrimitive(true); "false" -> JsonPrimitive(false)
            else -> value.toLongOrNull()?.let { JsonPrimitive(it) } ?: value.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(body)).toByteArray(Charsets.UTF_8)
    }

    // MARK: - Non-streaming responses

    private class NonStreaming(val content: String?, val reasoning: String?, val finishReason: String?)

    /** Feeds a complete (non-SSE) chat-completion JSON into the parser state so the finish path is identical to streaming. */
    private fun consumeNonStreamingResponse(body: String, parser: SseStreamParser, decrypt: ((String) -> String?)?, headers: Map<String, String>, prettyBody: String, decryptionFailed: () -> LlmError): NonStreaming {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val choice = root?.get("choices")?.let { runCatching { it.jsonArray.firstOrNull()?.jsonObject }.getOrNull() }
            ?: throw LlmError(LlmError.Source.Provider(providerName), "$providerName returned an unreadable response.", url = endpoint, requestHeaders = headers, requestBodyJson = prettyBody, responseBody = body)
        // The same fields the streaming path would have carried in deltas — ids, usage, and the sealed fields.
        (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parser.chatCompletionId = it }
        (root["usage"] as? JsonObject)?.let { u -> runCatching { u["completion_tokens"]?.jsonPrimitive?.content?.toInt() }.getOrNull()?.let { parser.completionTokens = it } }
        val message = choice["message"] as? JsonObject ?: JsonObject(emptyMap())
        fun open(field: String): String = if (decrypt == null) field else decrypt(field) ?: throw decryptionFailed()
        // Thinking tokens, under whichever key this server uses — the reasoning-only fallback has to work when the server ignores `stream`.
        val reasoning = SseStreamParser.reasoningChunk(message)?.let { (_, text) -> open(text) }
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { open(it) }
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finish == "tool_calls") {
            // Replay the structured calls through the parser as one delta, so assembly and decryption follow the streaming rules.
            val calls = (message["tool_calls"] as? JsonArray) ?: JsonArray(emptyList())
            val replay = JsonObject(mapOf("choices" to JsonArray(listOf(JsonObject(mapOf("delta" to JsonObject(mapOf("tool_calls" to JsonArray(calls.mapIndexed { i, tc ->
                val o = tc.jsonObject; JsonObject(o + ("index" to (o["index"] ?: JsonPrimitive(i)))) })))))))))
            parser.consume("data: " + json.encodeToString(JsonObject.serializer(), replay) + "\n\n", decrypt)
            if (parser.decryptFailed) throw decryptionFailed()
        }
        return NonStreaming(content, reasoning, finish)
    }

    // MARK: - E2EE sealing (fail-closed)

    /**
     * Seals one request body for an E2EE turn and returns it with the codec's
     * headers. Both failure modes — encryption THROWING, and
     * `encryptedBodyIfChanged` returning null because the body came back
     * unchanged (a parse failure inside `encryptRequestBody`) — become a thrown
     * [LlmError], so a caller can never fall back to sending the plaintext body
     * under an E2EE promise. Internal so the regression tests can drive each mode.
     */
    internal fun e2eeSealedBody(plaintextBody: ByteArray, codec: E2EEStreamCodec, headers: Map<String, String> = emptyMap()): Pair<ByteArray, Map<String, String>> {
        val sealed = try { codec.encryptedBodyIfChanged(plaintextBody) } catch (e: Exception) {
            // Mode 1: encryption itself threw (bad key, RNG failure, …).
            throw encryptionFailedError(headers, plaintextBody, e as? E2EEError ?: E2EEError.EncryptionFailed)
        }
        // Mode 2: the body came back byte-identical — nothing was sealed.
        if (sealed == null) throw encryptionFailedError(headers, plaintextBody, E2EEError.EncryptionFailed)
        return sealed to codec.headers
    }

    private fun encryptionFailedError(headers: Map<String, String>, plaintextBody: ByteArray, cause: E2EEError) = LlmError(
        LlmError.Source.Provider(providerName),
        "The request could not be encrypted, so it was not sent. Try again — if this keeps happening, re-verify the connection from the lock icon.",
        url = endpoint, requestHeaders = headers, requestBodyJson = String(plaintextBody, Charsets.UTF_8), underlyingError = cause)

    private fun Map<String, Any>.toJson(): JsonElement = JsonObject(mapValues { (_, v) -> v.toJson() })

    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
        is List<*> -> JsonArray(map { it.toJson() })
        else -> JsonPrimitive(toString())
    }
}
