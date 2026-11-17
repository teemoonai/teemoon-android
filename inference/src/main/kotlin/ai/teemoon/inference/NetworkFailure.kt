// ports: teemoon/Inference/LLMError.swift @ v1.1.0 (NetworkFailure), teemoon/Inference/ChatGeneration.swift @ 3f26a3e (cardMessage)
package ai.teemoon.inference

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * A transport failure as the error card's line. The words are conformance/errorcopy.json (the maintainer approved
 * error-card-lowercase and network-error-copy on the review page, 2026-10-28); ConformanceErrorCopyTest holds them to
 * every row, which iOS runs too. Before this a timeout read "An unexpected error occurred with Fireworks. timeout":
 * OkHttp's text through the catch-all, the card the maintainer saw on image turns (cfcab84).
 */
enum class NetworkFailure {
    TIMED_OUT, STALLED, OFFLINE, DROPPED, UNREACHABLE, UNREACHABLE_HOME, INSECURE, OTHER;

    fun message(provider: String): String = when (this) {
        TIMED_OUT -> "$provider took too long to answer. try again."
        STALLED -> "$provider stopped partway through the answer. try again."
        OFFLINE -> "no connection, so nothing was sent. try again when you're online."
        DROPPED -> "the connection dropped before $provider finished. try again."
        UNREACHABLE -> "can't reach $provider. check your connection and try again."
        UNREACHABLE_HOME -> "can't reach $provider. is it on and on this network?"
        INSECURE -> "couldn't open a secure connection to $provider, so nothing was sent."
        OTHER -> "something went wrong talking to $provider. the details are below."
    }

    companion object {
        /**
         * iOS classifies a URLError by its code; the JVM has no codes, so the exception class stands in, read along the
         * cause chain (OkHttp and the transport wrap). The pairs, as the fixture's notes name them:
         * - timedOut → [InterruptedIOException]: OkHttp's read/write/connect timeouts and the stream's idle bound
         *   (StreamStallTest) all throw one; after text was shown it is the stall line, as iOS's watchdog.
         * - notConnectedToInternet / dataNotAllowed → [online] false. Android has no such exception — an offline phone
         *   fails DNS with [UnknownHostException] — so the caller asks the system whether a network is up.
         * - networkConnectionLost → [SocketException] (reset, abort), [EOFException], [ProtocolException] ("unexpected
         *   end of stream") and OkHttp's StreamResetException (an IOException by name, not a type this module imports).
         * - cannotFindHost / cannotConnectToHost / dnsLookupFailed → [UnknownHostException], [ConnectException],
         *   [NoRouteToHostException]; worded for a home server when [selfHosted].
         * - secureConnectionFailed and the certificate codes → [SSLHandshakeException], [SSLPeerUnverifiedException],
         *   [CertificateException]. A plain SSLException mid-stream is a dropped TLS connection, not a bad certificate.
         * Order matters: ConnectException and NoRouteToHostException ARE SocketExceptions.
         */
        fun of(error: Throwable, answerStarted: Boolean, selfHosted: Boolean, online: Boolean = true): NetworkFailure {
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            fun has(test: (Throwable) -> Boolean) = chain.any(test)
            return when {
                has { it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is CertificateException } -> INSECURE
                has { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
                    if (!online) OFFLINE else if (selfHosted) UNREACHABLE_HOME else UNREACHABLE
                has { it is InterruptedIOException } -> if (answerStarted) STALLED else TIMED_OUT
                has { it is SocketException || it is EOFException || it is ProtocolException || it.javaClass.simpleName == "StreamResetException" ||
                    it is javax.net.ssl.SSLException } -> if (!online && !answerStarted) OFFLINE else DROPPED
                !online && !answerStarted && has { it is java.io.IOException } -> OFFLINE
                else -> OTHER
            }
        }

        /**
         * iOS `ChatGeneration.cardMessage`: the card's line for a failure that isn't already an [LlmError]. A network
         * failure gets its line; an on-device failure keeps its own; anything else is the catch-all, with the details
         * on the card below it — never the exception's own text in the line.
         */
        fun cardMessage(error: Throwable, provider: String, answerStarted: Boolean, selfHosted: Boolean, online: Boolean = true): String {
            (error as? LocalInferenceError)?.message?.let { return it }
            return of(error, answerStarted, selfHosted, online).message(provider)
        }
    }
}
