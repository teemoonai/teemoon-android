// ports: teemoon/Inference/BraveWebSearchTool.swift @ v1.0.4 (the tool, GroundingBudget,
//        BraveGroundingResponse, parseSources, parseMarkdownLinks, contextXML, checkKey. AHEAD of iOS: the result
//        carries CITATION_ASK after the sources (2026-10-02; near.ai's GLM ignored the description-only ask).
//        NOT here: ChatGeneration offer lifetime; GenerationEngine.sourcesInToolResult now IS parseSources, as iOS —
//        the wiring seam lives in that file.)
package ai.teemoon.inference

import ai.teemoon.providers.TeemoonHttp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * How much web context to pull into the prompt.
 *
 * Grounding dwarfs everything else teemoon adds to a request: measured on
 * "weather 10001", 4096 tokens × 10 URLs costs ~2,600 prompt tokens, while the
 * persona is ~173 and the `web_search` tool definition ~79.
 *
 * There used to be a second, `compact` policy (3 pages / 1024 tokens) handed
 * to self-hosted endpoints, on the theory that a small model on a phone-sized
 * context pays for every extra prompt token again in local prefill. Measured,
 * that was wrong in all three of its claims — prefill went 0.02s → 0.02s for
 * 538 → 4038 input tokens on an M-series GPU (decode is the serial part, not
 * prefill); Brave bills per QUERY, not per source, so it saved nothing; and
 * the smaller payload lost 6 of 6 accuracy cells. It is deleted rather than
 * left switched off, because an unused policy still has to be read, understood
 * and kept correct by everyone who touches this file.
 *
 * If a smaller payload is ever needed again — a CPU-only server, where prefill
 * genuinely is expensive, or a model whose context window can't take 2,600
 * tokens on top of persona and history — reintroduce it keyed on the CONTEXT
 * WINDOW, which teemoon knows from the catalogue. Not on `isSelfHosted`, which
 * was a proxy for the wrong thing. `git show fec4207` has the old shape.
 */
object GroundingBudget {
    /** Pages Brave should return, capped at the model's requested `count`. */
    fun urlCap(forCount: Int): Int = minOf(forCount, 10)

    /**
     * The on-device cap. This is the "CPU-only server, where prefill genuinely
     * is expensive" case the note above anticipated — except it is a phone, and
     * the discriminator is not the context window (Qwen3 has 32k and the full
     * payload fits fine) but **who pays for prefill**.
     *
     * Measured on an iPhone 16 Pro, round-two prefill of a real-shaped payload:
     *
     *      1 source   3,375 chars →  0.63 s
     *      3 sources 10,127 chars →  1.79 s
     *      5 sources 16,879 chars →  3.63 s
     *     10 sources 33,762 chars → 12.98 s
     *
     * Superlinear — doubling from 5 to 10 sources costs 3.6× the time, which is
     * attention behaving exactly as advertised. The earlier "prefill is free"
     * measurement (538→4,038 tokens, 0.02 s→0.02 s) was taken on an M-series
     * GPU and does not transfer to this hardware.
     *
     * 5 rather than 3: it keeps most of the context the accuracy A/B favoured
     * while cutting ~9 s, and the knee in the curve is between 5 and 10.
     */
    const val onDeviceURLCap = 5

    fun urlCap(forCount: Int, onDevice: Boolean): Int =
        if (onDevice) minOf(forCount, onDeviceURLCap) else urlCap(forCount)

    /**
     * Token ceiling for the whole context payload, scaled to query breadth —
     * small lookups don't need 8 k tokens.
     */
    fun maxTokens(forCount: Int): Int = when {
        forCount <= 5 -> 2048
        forCount <= 10 -> 4096
        else -> 8192
    }

    /**
     * On-device ceiling. **2048, for HALF THE PREFILL at the same source count.**
     *
     * Measured across ten live queries, 2048 against 4096:
     *
     *     ask=2048: mean 3.9 sources, mean 1,809 tokens, single-source 2/10
     *     ask=4096: mean 3.9 sources, mean 3,547 tokens, single-source 2/10
     *
     * Same sources, ~half the tokens. On device that is the whole argument:
     * this payload is prefilled on the phone's own GPU, prefill is superlinear
     * (6,009 chars → 5.1 s, 12,022 chars → 13.7 s), and it shares an 8,192-token
     * KV cache with the persona, the history and the answer.
     *
     * AN EARLIER VERSION OF THIS COMMENT CLAIMED "asking for less returns MORE
     * sources", from a single query where 4096 returned one 17,716-character
     * page and 2048 returned five. That was n=1 and it does not generalise:
     * across ten queries both budgets average 3.9 sources and both collapse to
     * a single source twice — on DIFFERENT queries. Which budget gets unlucky
     * is a property of the query, not of the number.
     *
     * WORTH KNOWING: ~20% of queries return one source at any budget, because
     * Brave fills the allowance highest-ranked-page-first and some top results
     * are enormous. That is not fixed here and is not fixable by tuning this
     * constant.
     */
    const val onDeviceMaxTokens = 2048

    /** The window the 2048 was measured against: the general bundle's KV cache (LiteRTTransport.DEFAULT_CONTEXT_TOKENS). */
    const val measuredContextTokens = 8192

    /**
     * On device the payload is also bounded by the WINDOW: a quarter of it, never above the measured cap. The 2048
     * was tuned against an 8192 window. A Tensor NPU bundle's window is 4096, compiled in, and there 2048 of sources
     * plus the preface (~700), the question, a thinking round and the answer met the ceiling mid-sentence — LiteRT
     * stops decoding at the KV cache without a word (Pixel 10 Pro, 2026-10-12: last prefill 2405, decode 747 then
     * silence). iOS runs only the 8192 bundle, where this is the old number; it adopts the function unchanged if it
     * ever ships a smaller window.
     */
    fun maxTokens(forCount: Int, onDevice: Boolean, contextTokens: Int = measuredContextTokens): Int =
        if (onDevice) minOf(onDeviceMaxTokens, contextTokens / 4).coerceAtLeast(braveMinimumTokens) else maxTokens(forCount)

    /** Brave's floor for `maximum_number_of_tokens`: below it the API answers 422 "must be ≥ 1024" (measured 2026-10-12). */
    const val braveMinimumTokens = 1024
}

/**
 * The full response shape of `GET /res/v1/llm/context`.
 *
 * Captured from live responses (`teemoonTests/Fixtures/brave_llm_context*.json`).
 * `grounding.generic` carries the passages; the top-level `sources` map is keyed
 * by URL and carries the per-page metadata — title, hostname, age and a
 * page-level snippet — that `generic` does not.
 */
@Serializable
data class BraveGroundingResponse(
    val grounding: Grounding,
    /** Keyed by the page URL. */
    val sources: Map<String, Source>? = null,
) {
    @Serializable
    data class Grounding(
        val generic: List<Generic>,
        val map: List<MapEntry>? = null,
    ) {
        @Serializable
        data class Generic(
            val url: String,
            val title: String? = null,
            /** Discrete passages pulled from the page. Some are raw JSON-LD blobs. */
            val snippets: List<String>? = null,
        )

        /**
         * Local/business results. Empty in every observed response — including
         * explicit local-business queries — so the element shape is unknown.
         * Decoded permissively so a future payload cannot fail the whole parse.
         */
        @Serializable
        data class MapEntry(
            val url: String? = null,
            val title: String? = null,
        )
    }

    @Serializable
    data class Source(
        val title: String? = null,
        val hostname: String? = null,
        /**
         * Three renderings of the page's publish date —
         * `["Saturday, July 25, 2026", "2026-07-25", "0 days ago"]`.
         * Empty when Brave has no date for the page.
         */
        val age: List<String>? = null,
        /** Page-level summary. Cleaner prose than `snippets`, which can be JSON-LD. */
        val snippet: String? = null,
    ) {
        /** Human-readable publish date, or nil when Brave reported no date. */
        val displayDate: String?
            get() = age?.firstOrNull()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * A [Tool] that fetches live Brave web search context.
 * Attach to a generation turn to give the model on-demand web access.
 */
class BraveWebSearchTool(
    val apiKey: String,
    val onDevice: Boolean = false,
    /** The local model's KV window when [onDevice]; sizes the payload (GroundingBudget.maxTokens). Ignored for cloud models. */
    val contextTokens: Int = GroundingBudget.measuredContextTokens,
    /**
     * Injection point for tests. Production is OkHttp with an explicit timeout.
     * Returns HTTP status and body; throws on transport failure.
     */
    var fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray> = ::liveFetch,
    var onUnconfiguredSearch: ((String) -> Unit)? = null,
    clock: () -> Instant = { Instant.now() },
) : Tool {

    override val name = "web_search"

    override val description: String

    override val parametersSchema: String = ARGUMENTS_SCHEMA

    init {
        val today = DATE_FORMAT.format(clock().atZone(ZoneId.systemDefault()))
        description = """
            Search the web for current, real-time information. Use this when the user asks about \
            recent events, current news, live prices, or anything that may have changed since your \
            training cutoff. Today is $today — always use the specific date (not just the month \
            or year) in queries for time-sensitive information. Results are returned as XML \
            <source> elements with <url>, <title>, <content> and, when known, <published> fields. \
            Do not reproduce the XML tags in your response. When citing information, use a \
            markdown link: [Page Title](url).
            """.trimIndent().replace("\\\n", "")
    }

    override suspend fun execute(argumentsJson: String): String {
        val obj = try {
            argsJson.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return "[BraveWebSearchTool] Empty query — skipping search."
        }
        val query = obj["query"]?.jsonPrimitive?.content.orEmpty()
        val freshness = obj["freshness"]?.jsonPrimitive?.content
        val threshold = obj["contextThresholdMode"]?.jsonPrimitive?.content
        val count = obj["count"]?.jsonPrimitive?.intOrNull
        return call(query, freshness, threshold, count)
    }

    suspend fun call(
        query: String,
        freshness: String? = null,
        contextThresholdMode: String? = null,
        count: Int? = null,
    ): String {
        val q = query.take(400)
        if (q.isEmpty()) {
            return "[BraveWebSearchTool] Empty query — skipping search."
        }
        // NO KEY: the tool is attached anyway, so the model can TELL US it
        // wanted the web. That decision is the only honest trigger for the
        // offer card — the alternative is sniffing the prompt for words like
        // "today", which guesses at intent the model has already stated.
        //
        // Reports the refusal back to the model rather than throwing: a thrown
        // error aborts the turn, and the whole point is that the answer still
        // gets written. Told plainly that search is unavailable, models answer
        // from memory and usually say so.
        if (apiKey.isEmpty()) {
            onUnconfiguredSearch?.invoke(q)
            // DO NOT ask the model to announce that it couldn't search.
            //
            // The earlier wording ended with "say plainly that you could not
            // check current sources", and gemma e2b duly wrote "I do not have
            // access to real-time weather data." That sentence is then in the
            // thread forever — and tool results are NOT (there is no `tool`
            // role in `ChatModels`), so on the next turn the refusal is the only
            // thing the model sees about searching. It copies it, never calls
            // `web_search` again, and the offer card can never reappear.
            //
            // THREE THINGS THIS DELIBERATELY DOES NOT DO, each a fix for a
            // worse earlier version:
            //
            // It does not claim the search RAN and found nothing. It did not
            // run. A model told "no results" can conclude the web has nothing
            // on the subject, which is a bigger lie than the one it replaces.
            //
            // It does not ask the model to hide that it cannot check live
            // sources. That version was measured on device and gemma e2b
            // ignored it — rightly, since for "weather right now" there is
            // nothing else truthful to say. Asking a model to conceal a real
            // limitation is both ineffective and against what teemoon is for.
            //
            // NEVER REPORT A FAILURE HERE. This took five device runs and one
            // decisive probe to see, and two wrong theories died on the way.
            //
            // The probe returned fake DATA — "Tokyo is currently 17C ...
            // reference code ZEBRA-7" — and gemma e2b reproduced every part of
            // it, reference code included. So the tool result is delivered
            // verbatim and the model follows it closely. Delivery was never the
            // problem, and neither was negation (an earlier theory rested on
            // the model "obeying" an instruction to say it could not check —
            // which is simply what it says anyway).
            //
            // The variable is whether the result reads as a FAILED TOOL CALL.
            // Every string that opened with "[web_search did not run]" or
            // "unavailable" produced the same apologise-and-redirect reply,
            // because that is what a model does when a tool fails, and the
            // reflex outranks any instruction that follows it.
            //
            // So this frames the state as a SETTING. Nothing went wrong, there
            // is nothing to apologise for, and the instructions are read as
            // instructions rather than as consolation after an error.
            //
            // WRITTEN FOR THE MODELS THAT COMPLY, not for gemma. This tool
            // attaches to every provider except brave answers and non-tool
            // models, so a cloud model with no key reads this and does what it
            // says — which is why the wording still matters even though the
            // on-device case ignores it entirely.
            //
            // Which is why it does NOT ask the model to mention teemoon or web
            // answers. An earlier version did, to fight gemma's redirect; that
            // fight is lost, and on a compliant model the only effect left is
            // printing the offer in prose directly above the card making the
            // same offer. The card owns the offer, the model owns the answer.
            //
            // THE REDIRECT BAN IS BACK, and removing it was a reasoning error
            // worth naming: it was dropped because GEMMA ignores negations —
            // then applied to a string whose whole point is that it is written
            // for models that COMPLY. Measured on deepseek-v4-flash via
            // near.ai, which obeyed "say roughly when that knowledge is from"
            // ("my knowledge cutoff is April 2024") while listing weather.com,
            // accuweather.com and the JMA, plus suggesting the user run their
            // own search. A model that follows one instruction will follow the
            // one beside it. Evidence from a model that ignores everything
            // cannot be used to strip instructions aimed at models that do not.
            return SEARCH_POLICY
        }
        return fetchContext(q, freshness, contextThresholdMode, count)
    }

    /**
     * What one real request to Brave says about a key.
     *
     * EVERY CASE NEEDS DIFFERENT ADVICE, which is the point of not collapsing
     * this to a Bool. "didn't work" sends a user to re-check a key that is
     * fine but out of credit, or to re-type one when their wifi is off.
     */
    enum class KeyCheck {
        /** Brave answered. The key is good. */
        VALID,
        /** Brave rejected it — 401/403. A typo, or a key from the wrong product. */
        REJECTED,
        /**
         * The key is REAL but has no credit left, or is being throttled — 429.
         * Saving it is correct; searches will work again next month.
         */
        OUT_OF_CREDIT,
        /** Could not reach brave at all. Says nothing about the key. */
        UNREACHABLE,
        /** Brave answered with a server error. Also says nothing about the key. */
        BRAVE_UNAVAILABLE,
    }

    data class KeyCheckResult(val kind: KeyCheck, val status: Int? = null)

    companion object {
        const val ENDPOINT = "https://api.search.brave.com/res/v1/llm/context"
        /**
         * Where the tool actually talks. Production never changes it; a debuggable launch may point it at an in-process
         * fake (UiTestSeeding `seed_brave_endpoint`) so a grounded turn — the most used path — runs end to end on a
         * device without a key or a network. Not a new destination: §4's list is what shipping builds can reach.
         */
        @Volatile var endpoint: String = ENDPOINT
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val KEY_CHECK_TIMEOUT_MS = 15_000L

        /**
         * The keyless tool-result. Copy is data — a model that complies follows
         * it, and every earlier wording that read as a failed call sent the
         * model into apologise-and-redirect. See BraveWebSearchToolTest.
         */
        val SEARCH_POLICY = """
                SEARCH POLICY: live web search is turned off for this \
                conversation, so this query was not sent anywhere. This is a \
                setting, not a failure.
                Answer the question now from your own knowledge, and say \
                roughly when that knowledge is from. Do not recommend other \
                websites, apps, or services, and do not suggest that the user \
                run a search themselves.
                """.trimIndent().replace("\\\n", "")

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)

        private val argsJson = Json { ignoreUnknownKeys = true }
        private val wire = Json { ignoreUnknownKeys = true }

        private val sourcePattern = Regex(
            """<source[^>]*>\s*<url>(.*?)</url>\s*<title>(.*?)</title>(?:\s*<published>.*?</published>)?\s*<content>(.*?)</content>\s*</source>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        /** Appended to every search result; see fetchContext. */
        const val CITATION_ASK = "\n\nWhen citing information from these sources, use a markdown link: [Page Title](url)."

        private val markdownLinkPattern = Regex("""\[([^\]]+)\]\((https?://[^)]+)\)""")

        private val ARGUMENTS_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"The search query. Be specific and include key terms. For time-sensitive topics, include the date or year."},
              "freshness":{"type":"string","description":"Recency filter for news-style content. Use only when the user explicitly asks about recent events or breaking news. pd = past day; pw = past week; pm = past month; py = past year. Omit for weather, prices, general knowledge."},
              "contextThresholdMode":{"type":"string","description":"Relevance threshold. strict = factual/medical/legal; balanced = general (default); lenient = exploratory or niche."},
              "count":{"type":"integer","description":"Number of web pages to consider (1–50). Use 5–10 for simple lookups, 20–30 for research, 40–50 for rare information. Default is 20."}
            },"required":["query"]}
            """.trimIndent()

        /**
         * Spends one real search to find out, because there is no cheaper way to
         * know and a wrong key is otherwise SILENT: it saves, the chip lights, and
         * every later search 401s where only the model can see it.
         */
        suspend fun checkKey(
            apiKey: String,
            fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray> = ::liveFetch,
        ): KeyCheckResult {
            val trimmed = apiKey.trim()
            if (trimmed.isEmpty()) return KeyCheckResult(KeyCheck.REJECTED)
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("q", "teemoon key check")
                .addQueryParameter("maximum_number_of_urls", "1")
                .build()
                .toString()
            val headers = mapOf(
                "X-Subscription-Token" to trimmed,
                "Accept" to "application/json",
            )
            return try {
                val (status, body) = fetch(url, headers, KEY_CHECK_TIMEOUT_MS)
                when (status) {
                    in 200..299 -> KeyCheckResult(KeyCheck.VALID)
                    401, 403 -> KeyCheckResult(KeyCheck.REJECTED)
                    // A key Brave cannot even parse — too short, a stray character — is a 422 with
                    // SUBSCRIPTION_TOKEN_INVALID, not a 401. Measured 2026-09-17 with a 16-character key: the check
                    // said "⚠ Brave unavailable", the key was saved, and every search then failed three times a turn.
                    422 -> if (LlmError.braveTokenInvalid(body.decodeToString())) KeyCheckResult(KeyCheck.REJECTED) else KeyCheckResult(KeyCheck.BRAVE_UNAVAILABLE, status)
                    429 -> KeyCheckResult(KeyCheck.OUT_OF_CREDIT)
                    else -> KeyCheckResult(KeyCheck.BRAVE_UNAVAILABLE, status)
                }
            } catch (_: Exception) {
                // No signal about the key itself — offline, DNS, timeout. Telling
                // the user their key is wrong here would be a guess, and the wrong
                // one most of the time.
                KeyCheckResult(KeyCheck.UNREACHABLE)
            }
        }

        /**
         * Parses [GroundingSource] values out of the XML context string produced by `fetchContext`.
         * Format: `<source index="N"><url>…</url><title>…</title>[<published>…</published>]<content>…</content></source>`
         */
        fun parseSources(from: String): List<GroundingSource> =
            sourcePattern.findAll(from).map { match ->
                val url = match.groupValues[1]
                GroundingSource(
                    url = url,
                    domain = hostOf(url) ?: url,
                    title = match.groupValues[2],
                    snippet = match.groupValues[3],
                )
            }.toList()

        /**
         * Extracts unique [GroundingSource] values from markdown links (`[title](url)`) in a string.
         * Used to surface only the sources the model actually cited in its response.
         */
        fun parseMarkdownLinks(from: String): List<GroundingSource> {
            val seen = LinkedHashSet<String>()
            val out = ArrayList<GroundingSource>()
            for (match in markdownLinkPattern.findAll(from)) {
                val url = match.groupValues[2]
                if (!seen.add(url)) continue
                out.add(
                    GroundingSource(
                        url = url,
                        domain = hostOf(url) ?: url,
                        title = match.groupValues[1],
                    ),
                )
            }
            return out
        }

        /**
         * Renders the grounding payload as the `<source>` XML handed to the model.
         *
         * `grounding.generic` supplies the passages; the top-level `sources` map
         * supplies the metadata it omits — a cleaner page-level title, and the
         * publish date, which the model needs to judge whether a passage is
         * actually current. Its `snippet` is also the fallback when a page came
         * back with no passages at all.
         * `includePublished` exists so the date signal can be A/B'd against the
         * same grounding payload — Brave's results span different dates, and
         * whether that actually helps a small model pick the current one is a
         * measurement, not an assumption.
         */
        fun contextXML(from: BraveGroundingResponse, includePublished: Boolean = true): String {
            val bodies = from.grounding.generic.mapNotNull { page ->
                if (page.url.isEmpty()) return@mapNotNull null
                val meta = from.sources?.get(page.url)
                // Join multiple snippets with an ellipsis marker so the model can see
                // they are discrete passages rather than one continuous block of text.
                // Each is unpacked first: some arrive as JSON-LD, and the model should
                // read facts rather than syntax. See `readableSnippet`.
                val snippets = (page.snippets ?: emptyList())
                    .map { GroundingSource.readableSnippet(it) }
                    .filter { it.isNotEmpty() }
                val content = if (snippets.isEmpty()) {
                    GroundingSource.readableSnippet(meta?.snippet.orEmpty())
                } else {
                    snippets.joinToString(" […] ")
                }
                if (content.isEmpty()) return@mapNotNull null
                val title = listOfNotNull(page.title, meta?.title)
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
                // Built line by line, not from a raw string: interpolating the optional <published> line into a
                // trimIndent() block left the other lines carrying their source indentation (kit 0.4.0, brave.json).
                val published = if (includePublished) meta?.displayDate?.let { "<published>$it</published>" } else null
                listOfNotNull("<url>${page.url}</url>", "<title>$title</title>", published, "<content>$content</content>").joinToString("\n")
            }
            return bodies.mapIndexed { i, body ->
                "<source index=\"${i + 1}\">\n$body\n</source>"
            }.joinToString("\n")
        }

        fun decode(body: String): BraveGroundingResponse =
            wire.decodeFromString(BraveGroundingResponse.serializer(), body)

        private fun hostOf(url: String): String? = try {
            URI(url).host
        } catch (_: Exception) {
            null
        }

        // ON THE IO DISPATCHER, like every other synchronous OkHttp call in this port. The tool runs inside the
        // generation loop, which the view model drives on Main; a blocking call there is a
        // NetworkOnMainThreadException, and this catch turned it into "check your network connection" — every
        // search failed instantly on the emulator (2026-09-08) while the reply itself streamed fine. iOS cannot
        // have this failure: URLSession is asynchronous by construction.
        suspend fun liveFetch(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
            val client = TeemoonHttp.builder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder().url(url).get()
            for ((k, v) in headers) req.addHeader(k, v)
            client.newCall(req.build()).execute().use { resp ->
                resp.code to resp.body.bytes()
            }
        }
    }

    /** The token ceiling asked of Brave for this turn: breadth-scaled for a cloud model, window-bounded on device. */
    fun payloadTokenCap(resolvedCount: Int): Int = GroundingBudget.maxTokens(resolvedCount, onDevice, contextTokens)

    private suspend fun fetchContext(
        query: String,
        freshness: String?,
        contextThresholdMode: String?,
        count: Int?,
    ): String {
        // Default 10 pages: halves follow-up payload vs. the old 20-page default (4096 vs 8192
        // max tokens), reducing upload time and model input tokens for the final response turn.
        val resolvedCount = count ?: 10
        val maxTokens = payloadTokenCap(resolvedCount)
        // Cap returned sources to avoid flooding the context window.
        // Brave may fetch `count` pages but we only need the best N in the output.
        val maxURLs = GroundingBudget.urlCap(resolvedCount, onDevice)
        val builder = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("maximum_number_of_tokens", maxTokens.toString())
            .addQueryParameter("maximum_number_of_urls", maxURLs.toString())
        if (freshness != null) builder.addQueryParameter("freshness", freshness)
        if (contextThresholdMode != null) {
            builder.addQueryParameter("context_threshold_mode", contextThresholdMode)
        }
        if (resolvedCount != 10) builder.addQueryParameter("count", resolvedCount.toString())
        val url = builder.build().toString()
        val headers = mapOf(
            "X-Subscription-Token" to apiKey,
            "Accept" to "application/json",
        )

        // An EXPLICIT timeout, because the defaults do not bound this.
        //
        // `URLRequest`'s default 60 s is an *idle* timeout — it fires only when
        // no bytes move — and `timeoutIntervalForResource` defaults to SEVEN
        // DAYS. A connection that opens and then stalls therefore hangs
        // indefinitely, and this call sits inside a tool round: the model is
        // waiting on the search, the user is waiting on the model, and nothing
        // ever times out.
        //
        // Observed on device: a live suite stopped dead here for 16 minutes with
        // no error, twice. Every other network call in teemoon sets 10-20 s;
        // this one was relying on defaults that do not mean what they look like.
        //
        // A failed search is recoverable — the tool returns an error string the
        // model can answer around. A hung one is not.
        val (status, data) = try {
            fetch(url, headers, SEARCH_TIMEOUT_MS)
        } catch (e: Exception) {
            throw LlmError(
                source = LlmError.Source.BraveGrounding,
                userMessage = "Brave web search failed — check your network connection.",
                url = url,
                requestHeaders = mapOf("X-Subscription-Token" to apiKey),
                underlyingError = e,
            )
        }

        if (status != 200) {
            throw LlmError(
                source = LlmError.Source.BraveGrounding,
                userMessage = LlmError.groundingMessage(status, data.decodeToString()),
                httpStatus = status,
                url = url,
                requestHeaders = mapOf("X-Subscription-Token" to apiKey),
                responseBody = data.decodeToString(),
            )
        }

        val decoded = try {
            decode(data.decodeToString())
        } catch (_: Exception) {
            throw LlmError(
                source = LlmError.Source.BraveGrounding,
                userMessage = "Brave web search returned an unexpected response format.",
                url = url,
                requestHeaders = mapOf("X-Subscription-Token" to apiKey),
                responseBody = data.decodeToString(),
            )
        }

        val context = contextXML(decoded)
        if (context.isEmpty()) {
            throw LlmError(
                source = LlmError.Source.BraveGrounding,
                userMessage = "Brave web search returned no results for this query.",
                url = url,
                requestHeaders = mapOf("X-Subscription-Token" to apiKey),
            )
        }
        // THE CITATION ASK RIDES WITH THE RESULTS, not only in the tool description. Measured 2026-10-02 over the real
        // search loop (Brave LLM context, same system prompt, GLM 5.3 Flash): with the ask only in the description,
        // near.ai linked 0 answers of 9 and Fireworks about two in three — both hosts render the description (the
        // model quotes it verbatim) and the prompt to within a token, and pinning sampling changed nothing. With this
        // line after the sources: near.ai 3 of 3, Fireworks 3 of 3. It sits AFTER contextXML so the kit's pinned
        // <source> bytes are untouched and parseSources reads the same elements.
        return context + CITATION_ASK
    }
}
