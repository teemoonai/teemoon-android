// ports: teemoonTests/ConformanceErrorCopyVectors.swift @ 3f26a3e (the consuming half, against conformance/errorcopy.json)
//
// The error card's words, approved by the maintainer on the review page 2026-10-28 (error-card-lowercase, network-error-copy).
// Each line in the fixture is declared, not computed, so a wording change fails here and on iOS. The fixture keys rows on
// NSURLErrorDomain codes; the JVM has none, so each code is played as the exception OkHttp throws for that failure —
// the table below is this port's half of the rule, and NetworkFailure.of documents the same pairs.
package ai.teemoon.inference

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConformanceErrorCopyTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResourceAsStream("/errorcopy.json")!!.reader().readText())
        .jsonObject["vectors"]!!.jsonObject

    private fun handshake(cause: Throwable) = SSLHandshakeException("handshake").apply { initCause(cause) }

    /** URLError code → (the exception Android sees, whether the phone has a network). */
    private fun played(code: Int): Pair<Throwable, Boolean> = when (code) {
        -1001 -> SocketTimeoutException("timeout") to true
        -1009 -> UnknownHostException("Unable to resolve host") to false   // notConnectedToInternet
        -1020 -> UnknownHostException("Unable to resolve host") to false   // dataNotAllowed: no network for the app
        -1005 -> SocketException("Connection reset") to true
        -1003 -> UnknownHostException("Unable to resolve host") to true
        -1004 -> ConnectException("Failed to connect") to true
        -1006 -> UnknownHostException("No address associated with hostname") to true
        -1200 -> SSLHandshakeException("handshake failed") to true
        -1202 -> handshake(CertificateException("Trust anchor for certification path not found.")) to true
        -1201 -> handshake(CertificateExpiredException("expired")) to true
        -1203 -> handshake(CertificateException("unknown root")) to true
        -1204 -> handshake(CertificateNotYetValidException("not yet valid")) to true
        -1011 -> IOException("unexpected response") to true                // badServerResponse: anything else
        else -> error("no JVM stand-in for URLError $code — add one with the fixture row")
    }

    @Test fun everyNetworkFailureGetsItsLine() {
        val rows = vectors["network"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 19)
        val wrong = rows.mapNotNull { row ->
            val (error, online) = played(row["urlErrorCode"]!!.jsonPrimitive.int)
            val got = NetworkFailure.cardMessage(error, "{provider}", row["answerStarted"]!!.jsonPrimitive.boolean,
                row["selfHosted"]!!.jsonPrimitive.boolean, online)
            val want = row["line"]!!.jsonPrimitive.content
            if (got == want) null else "${row["note"]!!.jsonPrimitive.content}: got \"$got\""
        }
        assertEquals("rows the mapping gets wrong", emptyList<String>(), wrong)
    }

    @Test fun everyStatusGetsItsLine() {
        for (row in vectors["status"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(row["line"]!!.jsonPrimitive.content, LlmError.providerMessage(row["status"]!!.jsonPrimitive.int, "{provider}"))
        }
    }

    @Test fun theNameIsTheOneTheAppShowsUnchanged() {
        assertEquals("Fireworks took too long to answer. try again.",
            NetworkFailure.cardMessage(SocketTimeoutException("timeout"), "Fireworks", answerStarted = false, selfHosted = false))
    }

    /** OkHttp's text ("timeout", "closed", a class name) never reaches the line: it stays on the card's details. */
    @Test fun aNonNetworkFailureThatIsNotWordedShowsTheCatchAll() {
        val catchAll = vectors["catchAll"]!!.jsonPrimitive.content
        assertEquals(catchAll, NetworkFailure.cardMessage(IllegalStateException("closed"), "{provider}", false, false))
        assertEquals(catchAll, NetworkFailure.cardMessage(IOException("unexpected"), "{provider}", false, false))
    }

    @Test fun anOnDeviceFailureKeepsItsOwnLine() {
        assertEquals("not enough free memory to load this model (needs about 2700 mb, 1200 mb free). close some apps, or pick a smaller model.",
            NetworkFailure.cardMessage(LocalInferenceError.InsufficientMemory(2700, 1200), "{provider}", false, false))
    }

    /** Android-only: the transport's own stream-idle bound and a wrapped timeout read as iOS's watchdog and timeout lines. */
    @Test fun aWrappedTimeoutIsStillATimeoutAndAfterTextItIsAStall() {
        val wrapped = IOException("stream", SocketTimeoutException("timeout"))
        assertEquals(NetworkFailure.TIMED_OUT, NetworkFailure.of(wrapped, answerStarted = false, selfHosted = false))
        assertEquals(NetworkFailure.STALLED, NetworkFailure.of(wrapped, answerStarted = true, selfHosted = false))
    }
}
