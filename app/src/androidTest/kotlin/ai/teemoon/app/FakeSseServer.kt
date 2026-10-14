package ai.teemoon.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The shape of teemoon-ios/tools/fake_sse_server.py, in-process: GET …/models
 * lists one model; POST …/chat/completions streams "Paragraph N: …" word by
 * word as OpenAI chunks, then a stop chunk, then [DONE], then closes.
 */
class FakeSseServer(private val lines: Int = 6, private val delayMs: Long = 4,
    /**
     * `fake_sse_server.py --tool-round`: the FIRST reply carries a marker-format web_search call mid-content and stops;
     * the tool runs against this server's own `/res/v1/llm/context` (a fake Brave, one source); the SECOND reply is the
     * paragraphs. The grounded turn, end to end, with no key and no network.
     */
    private val toolRound: Boolean = false,
    /** Silence before the first token — a reasoning phase the activity chip has to live through. */
    private val firstTokenDelayMs: Long = 0,
    /**
     * Silence before the FOLLOW-UP reply of a tool round — the second round trip plus prefill, with the preamble already
     * on screen. The window iOS measured at 3.0 s per round on DeepSeek, and the one the trailing activity chip covers.
     */
    private val followUpDelayMs: Long = 0,
    /** A `<think>…</think>` block ahead of the answer, for the thinking disclosure. */
    private val thinking: Boolean = false,
    /** Every completion fails with this HTTP status and a JSON error body: the error card's row, from a real turn. */
    private val failStatus: Int? = null,
    /** The first [failFirst] completions fail (with [failStatus], or 500), the rest answer: the retry journey's server. */
    private val failFirst: Int = 0,
    /** Serve model files as this many bytes, at once, instead of the slow 10 MB stream: a download that FINISHES — with bytes no pinned checksum matches. */
    private val modelBytes: Int? = null,
    /** What `/models` lists. Empty: a server that is up and answers, with nothing to run. */
    private val models: List<String> = listOf("fake-model"),
    /** `/models` answers 401: a host that refuses the key it was given. */
    private val rejectKey: Boolean = false,
    /** Stream THIS text as the reply (word by word) instead of the paragraphs — a markdown fixture, say. */
    private val reply: String? = null,
    /** The fake Brave refuses every key (401), as it does a typo. */
    private val braveRejects: Boolean = false,
    /** Answer as an Ollama server too: /api/version, /api/tags, /api/show, /api/ps, /api/delete, /api/pull, /api/generate. */
    private val ollama: Boolean = false,
    /**
     * Serve model files as [rangedBytes] of [rangedPattern], slowly (16 KB every 100 ms), honouring `Range: bytes=N-`
     * with a 206: the kill-and-resume journey (G2) needs a transfer it can interrupt and a host that resumes it.
     */
    private val rangedBytes: Int? = null,
) : AutoCloseable {
    /** Every model request's Range header ("" when it had none), in order: the resume journey asserts the second one picks up. */
    val modelRanges = CopyOnWriteArrayList<String>()
    /** Ollama mode: what the server holds, what is loaded (warm), and how its delete and pull behave. Mutable mid-journey. */
    val ollamaModels = CopyOnWriteArrayList(listOf("fake-model"))
    val ollamaWarm = CopyOnWriteArraySet(setOf("fake-model"))
    @Volatile var ollamaDeleteFails = false
    /** Off: a warm-up (/api/generate) no longer loads the model — the journey can watch a model go cold. */
    @Volatile var ollamaWarmsOnGenerate = true
    @Volatile var ollamaPullFails = false
    /** Pull pacing: steps of progress, ms apart — slow enough to watch and to stop. */
    @Volatile var ollamaPullSteps = 20
    @Volatile var ollamaPullStepMs = 150L
    /** Every completion request's body, in order — the journeys that assert what reached the wire read it. */
    val bodies = CopyOnWriteArrayList<String>()
    private val completions = AtomicInteger(0)
    private val posts = AtomicInteger(0)
    /** Model-file requests (`…/resolve/…`) — the cellular journeys assert when the bytes are, and are not, fetched. */
    val modelRequests = AtomicInteger(0)
    /** Fake-Brave searches (`…/llm/context`) — the on-device search journey asserts the tool really ran. */
    val searchRequests = AtomicInteger(0)
    /** Where a model host override points: this server's root, not its /v1. */
    val rootUrl: String get() = "http://127.0.0.1:$port"
    /** The one source the fake Brave returns — what the sources chip must show. */
    val sourceTitle = "Example weather"
    // 127.0.0.1 explicitly: getLoopbackAddress() is ::1 on Android, and nothing then listens where the app connects.
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"
    @Volatile private var running = true

    init {
        thread(isDaemon = true, name = "fake-sse") {
            while (running) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
    }

    private val words = ("attestation quote enclave measurement provenance transcript verifier ledger " +
        "signature manifest artifact digest revision endpoint gateway registry policy").split(" ")

    private fun replyText(): String = reply ?: replyParagraphs()
    private fun replyParagraphs(): String = (if (thinking) "<think>Reasoning: weigh the question, pick the plain answer.</think> " else "") + (0 until lines).joinToString("\n\n") { i ->
        "Paragraph $i: " + (0 until 12).joinToString(" ") { j -> words[(i * 7 + j * 3) % words.size] + "-$i$j" } + "."
    }

    private fun handle(client: Socket): Unit = client.use { c ->
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val requestLine = reader.readLine() ?: return
        Log.i("SMOKE", "fake-sse: $requestLine")
        var contentLength = 0
        var range = ""
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) contentLength = h.substringAfter(':').trim().toInt()
            if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(':').trim()
        }
        // Content-Length counts BYTES; the reader hands out chars. Counting chars stalled every request whose body
        // carried a non-ASCII glyph (a seeded table's "8f3a…c1"): the server waited for bytes that had already arrived
        // and the app sat on "thinking…" for good. Sum each char's UTF-8 width instead.
        val body = StringBuilder()
        if (contentLength > 0) {
            var bytes = 0
            while (bytes < contentLength) {
                val ch = reader.read(); if (ch < 0) break
                body.append(ch.toChar())
                bytes += when { ch < 0x80 -> 1; ch < 0x800 -> 2; ch in 0xD800..0xDFFF -> 2; else -> 3 }
            }
        }
        val out = c.getOutputStream()
        val path = requestLine.split(" ").getOrNull(1) ?: ""
        if (ollama && path.startsWith("/api/")) { ollamaApi(requestLine, path, body.toString(), out); return }
        if (requestLine.startsWith("GET") && path.trimEnd('/').endsWith("/models")) {
            if (rejectKey) { json(out, """{"error":{"message":"invalid api key","type":"invalid_request_error"}}""", 401); return }
            // an Ollama machine's OpenAI list IS its tags, as real Ollama's is: a delete or a pull shows up here too
            val listed = if (ollama) ollamaModels.toList() else models
            json(out, """{"object":"list","data":[${listed.joinToString(",") { """{"id":"$it","object":"model","owned_by":"test"}""" }}]}""")
            return
        }
        if (requestLine.startsWith("GET") && path.substringBefore('?').endsWith("/llm/context")) {
            searchRequests.incrementAndGet()
            if (braveRejects) { json(out, """{"error":{"code":"SUBSCRIPTION_TOKEN_INVALID","status":401}}""", 401); return }
            // BraveGroundingResponse: grounding.generic[] with snippets, sources keyed by index
            json(out, """{"grounding":{"generic":[{"url":"https://example.org/weather","title":"$sourceTitle","snippets":["Sunny, 24°C in Paris this afternoon."]}]},"sources":{"1":{"title":"$sourceTitle","hostname":"example.org"}}}""")
            return
        }
        if (requestLine.startsWith("GET") && path.contains("/resolve/")) {
            // A model file, served slowly enough to stay in flight while a journey looks at it (a few KB a second).
            modelRequests.incrementAndGet()
            rangedBytes?.let { n ->
                modelRanges += range
                val from = Regex("bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toInt() ?: 0
                if (from >= n) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return }
                val head = if (from > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${n - 1}/$n\r\n" else "HTTP/1.1 200 OK\r\n"
                out.write("${head}Content-Type: application/octet-stream\r\nContent-Length: ${n - from}\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                runCatching { var at = from; while (at < n) { val end = minOf(n, at + 16 * 1024); out.write(ByteArray(end - at) { rangedPattern(at + it) }); out.flush(); at = end; Thread.sleep(100) } }
                return
            }
            modelBytes?.let { n ->
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $n\r\nConnection: close\r\n\r\n".toByteArray()); out.write(ByteArray(n) { 7 }); out.flush(); return
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 10000000\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
            runCatching { repeat(2_000) { out.write(ByteArray(4096)); out.flush(); Thread.sleep(200) } }
            return
        }
        if (!requestLine.startsWith("POST")) { json(out, """{"error":"not found"}""", 404); return }
        bodies += body.toString()
        val n = completions.incrementAndGet()
        if (n <= failFirst) { json(out, """{"error":{"message":"the fake server was told to fail","type":"server_error"}}""", failStatus ?: 500); return }
        if (failFirst == 0) failStatus?.let { json(out, """{"error":{"message":"the fake server was told to fail","type":"server_error"}}""", it); return }
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        val nth = posts.incrementAndGet()
        // word-by-word, the marker JSON split at its spaces exactly as a real server delivers it
        val text = if (toolRound && nth == 1) "Paragraph 0: looking that up. <tool_call>{\"name\": \"web_search\", \"arguments\": {\"query\": \"paris weather today\"}}</tool_call>" else replyText()
        if (firstTokenDelayMs > 0) Thread.sleep(firstTokenDelayMs)
        if (toolRound && nth >= 2 && followUpDelayMs > 0) Thread.sleep(followUpDelayMs)
        for (word in text.split(" ")) {
            val chunk = """{"id":"chatcmpl-fake","object":"chat.completion.chunk","model":"fake-model","choices":[{"index":0,"delta":{"content":${jsonString("$word ")}},"finish_reason":null}]}"""
            out.write("data: $chunk\n\n".toByteArray()); out.flush(); Thread.sleep(delayMs)
        }
        out.write("data: {\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion.chunk\",\"model\":\"fake-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n".toByteArray())
        out.write("data: [DONE]\n\n".toByteArray()); out.flush()
    }

    private fun json(out: OutputStream, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun ollamaApi(requestLine: String, path: String, body: String, out: OutputStream) {
        val name = Regex("\"(?:model|name)\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        when {
            path.startsWith("/api/version") -> json(out, """{"version":"0.12.0"}""")
            path.startsWith("/api/tags") -> json(out, """{"models":[${ollamaModels.joinToString(",") { """{"name":"$it","model":"$it","size":1000000,"details":{"family":"llama","parameter_size":"1B","quantization_level":"Q4_K_M"}}""" }}]}""")
            path.startsWith("/api/show") -> json(out, """{"capabilities":["completion"]}""")
            path.startsWith("/api/ps") -> json(out, """{"models":[${ollamaWarm.joinToString(",") { """{"name":"$it","model":"$it"}""" }}]}""")
            path.startsWith("/api/generate") -> { if (ollamaWarmsOnGenerate) name?.let { ollamaWarm.add(it) }; json(out, """{"done":true}""") }
            path.startsWith("/api/delete") -> if (ollamaDeleteFails) json(out, """{"error":"permission denied"}""", 500) else { name?.let { ollamaModels.remove(it); ollamaWarm.remove(it) }; json(out, "{}") }
            path.startsWith("/api/pull") -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nConnection: close\r\n\r\n".toByteArray())
                fun line(l: String) { out.write((l + "\n").toByteArray()); out.flush() }
                line("""{"status":"pulling manifest"}""")
                // a pull that can never succeed fails BEFORE any bytes: OllamaDownloadCenter re-attaches a stream that made
                // progress (Ollama resumes it) and gives up only after attempts that made none
                if (ollamaPullFails) { line("""{"error":"pull model manifest: file does not exist"}"""); return }
                val total = 10_000_000L
                for (i in 1..ollamaPullSteps) {
                    line("""{"status":"pulling abc123","digest":"sha256:abc123","total":$total,"completed":${total * i / ollamaPullSteps}}""")
                    Thread.sleep(ollamaPullStepMs)
                }
                line("""{"status":"verifying sha256 digest"}"""); line("""{"status":"writing manifest"}""")
                name?.let { if (it !in ollamaModels) ollamaModels.add(it) }
                line("""{"status":"success"}""")
            }
            else -> json(out, """{"error":"not found"}""", 404)
        }
    }

    override fun close() { running = false; runCatching { socket.close() } }

    companion object {
        /** The ranged model file's byte at [i] — not constant, so a resume that lands at the wrong offset fails its checksum. */
        fun rangedPattern(i: Int): Byte = (i % 251).toByte()
        fun rangedSha(n: Int): String = MessageDigest.getInstance("SHA-256").digest(ByteArray(n) { rangedPattern(it) }).joinToString("") { "%02x".format(it) }
    }
}
