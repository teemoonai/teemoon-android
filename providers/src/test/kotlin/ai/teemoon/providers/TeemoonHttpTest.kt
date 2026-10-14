// android-only: the runtime half of the cleartext gate (TeemoonHttp). No network: a refused request fails before any
// socket opens, and the private case is pointed at a closed loopback port.
package ai.teemoon.providers

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TeemoonHttpTest {
    private fun call(url: String): IOException? = try {
        TeemoonHttp.client().newCall(Request.Builder().url(url).build()).execute().close(); null
    } catch (e: IOException) { e }

    /** Plain http to a public host is refused by the gate itself, and says CLEARTEXT so the UI files it as HTTP_BLOCKED. */
    @Test fun plainHttpToAPublicHostIsRefused() {
        for (url in listOf("http://example.com/v1/models", "http://8.8.8.8/v1", "http://api.openai.com/v1")) {
            val e = call(url) ?: return fail("$url went through")
            assertTrue("$url: ${e.message}", e.message!!.contains("CLEARTEXT"))
            assertEquals(EndpointModelCatalog.FailureKind.HTTP_BLOCKED, EndpointModelCatalog.failureKindForTransport(e))
        }
    }

    /** A private host passes the gate: a closed loopback port fails as "nothing listening", never as blocked. */
    @Test fun plainHttpToAPrivateHostPassesTheGate() {
        for (url in listOf("http://127.0.0.1:9/v1/models", "http://localhost:9/v1/models")) {
            val e = call(url) ?: return fail("nothing listens on :9, yet $url succeeded")
            assertTrue("$url was blocked by the gate: ${e.message}", e.message?.contains("CLEARTEXT") != true)
        }
    }

    /** The gate decides only on host privacy for http: the RFC-1918, Tailscale and .local cases are Provider.isPrivateHost's, pinned in ProviderTest. */
    @Test fun theGateIsInstalledOnEveryBuilderAndRefusesSchemeDowngradingRedirects() {
        val client = TeemoonHttp.builder().build()
        assertTrue(client.interceptors.contains(TeemoonHttp.cleartextGate))
        assertTrue("every hop, not just the first", client.networkInterceptors.contains(TeemoonHttp.cleartextGate))
        assertTrue("a key header stays with its host", client.networkInterceptors.contains(TeemoonHttp.keyHeadersStayHome))
        assertEquals(false, client.followSslRedirects)
    }

    /**
     * A REDIRECT HOP IS GATED TOO. The gate was an application interceptor, which sees the request once, before
     * redirects; followSslRedirects(false) stops only a scheme change. So a private http endpoint answering 307/308 to
     * another http host had OkHttp re-send the body there in clear (the local audit, a LOW since 3910, 2026-10-13).
     * Here "public.example" resolves to a second loopback server: the body must never reach it.
     */
    @Test fun aRedirectToAPublicHostDoesNotCarryTheBody() {
        val public = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val private = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var publicGot = ""
        val p = thread { runCatching { public.accept().use { c -> c.soTimeout = 3_000
            publicGot = runCatching { c.getInputStream().readNBytes(1).decodeToString() }.recover { if (it is SocketTimeoutException) "" else throw it }.getOrDefault("") } } }
        val r = thread { runCatching { private.accept().use { c ->
            c.getInputStream().bufferedReader().let { rd -> while (rd.readLine()?.isNotEmpty() == true) {} }
            c.getOutputStream().write(("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://public.example:${public.localPort}/v1/chat/completions\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()); c.getOutputStream().flush() } } }
        val client = TeemoonHttp.builder()
            .dns { host -> if (host == "public.example") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(host) }.build()
        val e = try {
            client.newCall(Request.Builder().url("http://127.0.0.1:${private.localPort}/v1/chat/completions")
                .post("""{"messages":[{"role":"user","content":"the secret question"}]}""".toRequestBody("application/json".toMediaType())).build())
                .execute().close(); null
        } catch (e: IOException) { e }
        r.join(5_000); public.close(); p.join(5_000); private.close()
        assertTrue("the redirect hop was not refused", e?.message?.contains("CLEARTEXT") == true)
        assertEquals("the body went to the redirect's public host", "", publicGot)
    }

    /** One-shot servers: [redirectTo] answers 307 there; otherwise 200, and the request's head is recorded. */
    private class OneShot(private val redirectTo: ((Int) -> String)? = null) {
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        @Volatile var head = ""
        fun serve(targetPort: Int = 0) = thread { runCatching { socket.accept().use { c ->
            val rd = c.getInputStream().bufferedReader(); val lines = mutableListOf<String>()
            while (true) { val l = rd.readLine() ?: break; if (l.isEmpty()) break; lines += l }
            head = lines.joinToString("\n")
            val reply = redirectTo?.let { "HTTP/1.1 307 Temporary Redirect\r\nLocation: ${it(targetPort)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }
                ?: "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
            c.getOutputStream().write(reply.toByteArray()); c.getOutputStream().flush() } } }
    }

    /**
     * A KEY HEADER DOES NOT FOLLOW A REDIRECT TO ANOTHER HOST. OkHttp drops only Authorization when a hop changes host;
     * a key sent as X-Api-Key, X-Subscription-Token or a provider's own header rode the redirect (the local audit's
     * residual, 2026-10-13). Both hosts here are this machine, by two names, so the hop is allowed and only the header
     * is in question.
     */
    @Test fun aKeyHeaderIsDroppedWhenARedirectChangesHost() {
        CredentialHeaders.register("X-Gateway-Pass")
        val second = OneShot(); val first = OneShot(redirectTo = { port -> "http://localhost:$port/v1/models" })
        val t2 = second.serve(); val t1 = first.serve(second.socket.localPort)
        TeemoonHttp.client().newCall(Request.Builder().url("http://127.0.0.1:${first.socket.localPort}/v1/models")
            .header("X-Api-Key", "secret-one").header("X-Gateway-Pass", "secret-two").header("Accept", "application/json").build()).execute().close()
        t1.join(5_000); t2.join(5_000); first.socket.close(); second.socket.close()
        assertTrue("the first host got the key", first.head.contains("secret-one"))
        assertTrue("the redirect was not followed: ${second.head}", second.head.startsWith("GET /v1/models"))
        assertTrue("x-api-key rode the redirect: ${second.head}", !second.head.contains("secret-one"))
        assertTrue("a provider's own key header rode the redirect: ${second.head}", !second.head.contains("secret-two"))
        assertTrue("an ordinary header was dropped too", second.head.lowercase().contains("accept: application/json"))
    }

    /**
     * EVERY KEY-SENDING REQUEST REGISTERS ITS HEADER. Only the chat transport did, so a /models fetch, a probe or an
     * OpenRouter list sent a provider's own header unregistered until a chat turn had run — and the redirect filter and
     * the copy mask both read the registry (the local audit of c0ed24c, 2026-10-13).
     */
    @Test fun aModelListFetchRegistersItsKeyHeader() = kotlinx.coroutines.test.runTest {
        assertTrue(!CredentialHeaders.isCredential("X-List-Pass"))
        var sent: Map<String, String> = emptyMap()
        EndpointModelCatalog.fetchList("https://gw.example.com/v1/models", "list-key-0000", "X-List-Pass", fetch = { _, h, _ -> sent = h; 200 to "{}".toByteArray() }) { it }
        assertEquals("list-key-0000", sent["X-List-Pass"])
        assertTrue("the list fetch sent a key under a name nothing registered", CredentialHeaders.isCredential("x-list-pass"))
    }

    /** Same host, another port, is another server: the key header stays behind (the filter compared hosts only). */
    @Test fun aKeyHeaderIsDroppedWhenARedirectChangesPort() {
        val second = OneShot(); val first = OneShot(redirectTo = { port -> "http://127.0.0.1:$port/v1/models" })
        val t2 = second.serve(); val t1 = first.serve(second.socket.localPort)
        TeemoonHttp.client().newCall(Request.Builder().url("http://127.0.0.1:${first.socket.localPort}/v1/models")
            .header("X-Api-Key", "secret-port").build()).execute().close()
        t1.join(5_000); t2.join(5_000); first.socket.close(); second.socket.close()
        assertTrue("the redirect was not followed: ${second.head}", second.head.startsWith("GET /v1/models"))
        assertTrue("x-api-key followed the redirect to another port: ${second.head}", !second.head.contains("secret-port"))
    }
}
