// ports: teemoon/Inference/StreamStallWatchdog.swift @ v1.1.0 — REBUILD over OkHttp/Okio timeouts
//
// Two different waits on a chat request. Before headers, a free-tier queue (NVIDIA held glm-5.3-flash 253–282 s,
// 2026-10-19) is still a live request, so the wait is long. After headers, bytes that stop for [STREAM_IDLE_MS] mean
// the stream is dead.
//
// iOS needs a polling watchdog because URLSession has one request timeout for both waits. OkHttp's readTimeout is
// already "the longest gap between bytes", and it is an Okio Timeout on the response's source: the client carries
// the header wait, and once headers are in, the body source's own timeout is narrowed to the stream's idle limit.
// When it fires, Okio closes the socket (HTTP/1) or cancels the stream (HTTP/2) and the read throws
// SocketTimeoutException — the same error the 240 s read timeout raised before this.
package ai.teemoon.inference

import java.util.concurrent.TimeUnit
import okio.BufferedSource

object StreamTimeouts {
    /** OkHttp's readTimeout for chat: the longest wait for headers. iOS: `StreamStallWatchdog.headerWait`. */
    const val HEADER_WAIT_MS = 600_000L
    /** The longest gap between bytes once the response has started. iOS: `StreamStallWatchdog.streamIdle`. */
    const val STREAM_IDLE_MS = 240_000L

    /** From headers on, [source] gives up after [idleMs] without a byte. */
    fun startStream(source: BufferedSource, idleMs: Long = STREAM_IDLE_MS) {
        source.timeout().timeout(idleMs, TimeUnit.MILLISECONDS)
    }
}
