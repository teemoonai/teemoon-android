// ports: teemoonTests/LocalServerLiveTests.swift @ v1.0.1 (LocalServerFailureClassificationTests)
//        teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (badKeyIsRecognizedFromTheBodyWhenTheStatusLies, sourceResolvesByHost)
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class EndpointModelCatalogTest {

    // MARK: - Failure classification (LocalServerFailureClassificationTests)

    @Test fun keylessForbiddenIsNotBlamedOnTheKey() {
        assertEquals(
            EndpointModelCatalog.FailureKind.FORBIDDEN,
            EndpointModelCatalog.failureKind(403, body = null, sentKey = false),
        )
        assertEquals(
            EndpointModelCatalog.FailureKind.FORBIDDEN,
            EndpointModelCatalog.failureKind(403, body = ByteArray(0), sentKey = false),
        )
    }

    @Test fun forbiddenStillMeansUnauthorizedWhenAKeyWasSent() {
        assertEquals(
            EndpointModelCatalog.FailureKind.UNAUTHORIZED,
            EndpointModelCatalog.failureKind(403, body = null, sentKey = true),
        )
        assertEquals(
            EndpointModelCatalog.FailureKind.UNAUTHORIZED,
            EndpointModelCatalog.failureKind(401, body = null, sentKey = false),
        )
    }

    @Test fun otherStatusesAreUnaffectedByTheKeySignal() {
        for (sent in listOf(true, false)) {
            assertNull(EndpointModelCatalog.failureKind(200, sentKey = sent))
            assertEquals(
                EndpointModelCatalog.FailureKind.NO_MODELS_ENDPOINT,
                EndpointModelCatalog.failureKind(404, sentKey = sent),
            )
            assertEquals(
                EndpointModelCatalog.FailureKind.PAYMENT_REQUIRED,
                EndpointModelCatalog.failureKind(402, sentKey = sent),
            )
            assertEquals(
                EndpointModelCatalog.FailureKind.RATE_LIMITED,
                EndpointModelCatalog.failureKind(429, sentKey = sent),
            )
        }
    }

    // MARK: - Body-sniffed 400 (ProviderCatalogAdapterTests)

    @Test fun badKeyIsRecognizedFromTheBodyWhenTheStatusLies() {
        val xai = """{"code":"invalid-argument","error":"Incorrect API key provided. You can obtain an API key from https://console.x.ai."}"""
            .toByteArray()
        assertEquals(
            EndpointModelCatalog.FailureKind.UNAUTHORIZED,
            EndpointModelCatalog.failureKind(400, body = xai),
        )
        assertEquals(
            EndpointModelCatalog.FailureKind.OFFLINE,
            EndpointModelCatalog.failureKind(400, body = null),
        )
        val shapeError = """{"error":{"detail":"messages: List should have at most 1 item"}}""".toByteArray()
        assertEquals(
            EndpointModelCatalog.FailureKind.OFFLINE,
            EndpointModelCatalog.failureKind(400, body = shapeError),
        )
        assertEquals(
            EndpointModelCatalog.FailureKind.UNAUTHORIZED,
            EndpointModelCatalog.failureKind(401, body = null),
        )
        assertEquals(
            EndpointModelCatalog.FailureKind.PAYMENT_REQUIRED,
            EndpointModelCatalog.failureKind(402, body = xai),
        )
        assertNull(EndpointModelCatalog.failureKind(200, body = null))
    }

    @Test fun sourceResolvesByHost() {
        assertEquals(EndpointModelCatalog.Source.NEAR_AI, EndpointModelCatalog.Source.resolve("cloud-api.near.ai"))
        assertEquals(EndpointModelCatalog.Source.XAI, EndpointModelCatalog.Source.resolve("api.x.ai"))
        assertEquals(EndpointModelCatalog.Source.FIREWORKS, EndpointModelCatalog.Source.resolve("api.fireworks.ai"))
        assertEquals(EndpointModelCatalog.Source.GENERIC, EndpointModelCatalog.Source.resolve("ringzero.tailnet-name.ts.net"))
        assertEquals(EndpointModelCatalog.Source.GENERIC, EndpointModelCatalog.Source.resolve(null))
        assertEquals(EndpointModelCatalog.Source.GENERIC, EndpointModelCatalog.Source.resolve("api.x.ai.evil.com"))
    }

    // MARK: - probe

    @Test fun probeParsesChatModelsAndSkipsNonChatAndDuplicates() = runTest {
        val body = """
        {"data":[
          {"id":"bartowski/Qwen2.5-7B-Instruct-GGUF:Q4_K_M","meta":{"n_ctx":32768,"ftype":"Q4_K_M"}},
          {"id":"text-embedding-ada"},
          {"id":"Bartowski/Qwen2.5-7B-Instruct-GGUF:Q4_K_M"},
          {"id":"llama3"}
        ]}
        """.trimIndent()
        val result = EndpointModelCatalog.probe(
            baseUrl = "http://127.0.0.1:11434/v1",
            fetch = { url, _, _ ->
                assertTrue(url.endsWith("/v1/models"))
                200 to body.toByteArray()
            },
        )
        val models = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(listOf("bartowski/Qwen2.5-7B-Instruct-GGUF:Q4_K_M", "llama3"), models.map { it.id })
        assertEquals("Qwen", models[0].vendor)
        assertEquals("32k · Q4_K_M", models[0].contextWindow)
        assertEquals("Meta", models[1].vendor)
    }

    @Test fun probeEmptyOrUnparseableIsBadResponse() = runTest {
        suspend fun kind(status: Int, body: String) =
            (EndpointModelCatalog.probe("http://h/v1", fetch = { _, _, _ -> status to body.toByteArray() })
                as EndpointModelCatalog.ProbeResult.Failed).kind
        assertEquals(EndpointModelCatalog.FailureKind.BAD_RESPONSE, kind(200, """{"data":[]}"""))
        assertEquals(EndpointModelCatalog.FailureKind.BAD_RESPONSE, kind(200, "not json"))
        assertEquals(
            EndpointModelCatalog.FailureKind.BAD_RESPONSE,
            kind(200, """{"data":[{"id":"text-embedding-3-small"}]}"""),
        )
    }

    @Test fun probeSendsBearerOnlyWhenAKeyExists() = runTest {
        var headers = emptyMap<String, String>()
        EndpointModelCatalog.probe(
            "http://h/v1",
            apiKey = "  sk-x  ",
            fetch = { _, h, t ->
                headers = h
                assertEquals(EndpointModelCatalog.PROBE_TIMEOUT_MS, t)
                200 to """{"data":[{"id":"m"}]}""".toByteArray()
            },
        )
        assertEquals("Bearer sk-x", headers["Authorization"])
        var keyless = emptyMap<String, String>()
        EndpointModelCatalog.probe(
            "http://h/v1",
            fetch = { _, h, _ ->
                keyless = h
                200 to """{"data":[{"id":"m"}]}""".toByteArray()
            },
        )
        assertTrue(keyless.isEmpty())
    }

    @Test fun probeNamedAuthHeaderIsUsedAsIs() = runTest {
        var headers = emptyMap<String, String>()
        EndpointModelCatalog.probe(
            "http://h/v1",
            authHeaderName = "X-Api-Key",
            apiKey = "k",
            fetch = { _, h, _ ->
                headers = h
                200 to """{"data":[{"id":"m"}]}""".toByteArray()
            },
        )
        assertEquals("k", headers["X-Api-Key"])
        assertNull(headers["Authorization"])
    }

    @Test fun probeHttpFailureUsesTheStatusMap() = runTest {
        val failed = EndpointModelCatalog.probe(
            "http://h/v1",
            apiKey = "",
            fetch = { _, _, _ -> 403 to ByteArray(0) },
        ) as EndpointModelCatalog.ProbeResult.Failed
        assertEquals(EndpointModelCatalog.FailureKind.FORBIDDEN, failed.kind)
    }

    @Test fun probeTransportMapsConnectAndCleartext() = runTest {
        val down = EndpointModelCatalog.probe(
            "http://h/v1",
            fetch = { _, _, _ -> throw ConnectException("refused") },
        ) as EndpointModelCatalog.ProbeResult.Failed
        assertEquals(EndpointModelCatalog.FailureKind.NOTHING_LISTENING, down.kind)
        val dns = EndpointModelCatalog.probe(
            "http://h/v1",
            fetch = { _, _, _ -> throw UnknownHostException("nope") },
        ) as EndpointModelCatalog.ProbeResult.Failed
        assertEquals(EndpointModelCatalog.FailureKind.NOTHING_LISTENING, dns.kind)
        val timeout = EndpointModelCatalog.probe(
            "http://h/v1",
            fetch = { _, _, _ -> throw SocketTimeoutException("idle") },
        ) as EndpointModelCatalog.ProbeResult.Failed
        assertEquals(EndpointModelCatalog.FailureKind.NOTHING_LISTENING, timeout.kind)
        val ats = EndpointModelCatalog.probe(
            "http://h/v1",
            fetch = { _, _, _ -> error("CLEARTEXT communication not permitted by network security policy") },
        ) as EndpointModelCatalog.ProbeResult.Failed
        assertEquals(EndpointModelCatalog.FailureKind.HTTP_BLOCKED, ats.kind)
    }

    @Test fun rootUrlStripsThePath() {
        assertEquals(
            "http://127.0.0.1:11434",
            EndpointModelCatalog.rootUrl("http://127.0.0.1:11434/v1"),
        )
    }
}
