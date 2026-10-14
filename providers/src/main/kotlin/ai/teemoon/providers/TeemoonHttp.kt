// android-only: the RUNTIME half of the cleartext gate (iOS: ATS with NSAllowsLocalNetworking, Info.plist).
package ai.teemoon.providers

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Every HTTP client in the app is built here, so every one carries the cleartext gate.
 *
 * iOS lets a self-hosted server be reached over plain http with ATS's `NSAllowsLocalNetworking`, and ATS refuses
 * cleartext to everything else. Android's network-security-config cannot say "private networks": it names domains,
 * not RFC-1918 ranges. So the config opens cleartext (res/xml/network_security_config.xml) and THIS decides what
 * teemoon will actually talk to in the clear — [Provider.isPrivateHost]: loopback, RFC-1918, link-local, the
 * Tailscale range, `.local`, `.ts.net`. The two halves only ever ship together: opening the config without this is
 * a security regression, and this without the opened config is what shipped until 2026-10-02 — the config allowed
 * only the emulator's 10.0.2.2, so "connect a computer" at a LAN address failed on every real phone.
 *
 * The refusal says CLEARTEXT, so [EndpointModelCatalog.failureKindForTransport] files it as HTTP_BLOCKED and the
 * user gets the same "use https" guidance a platform block gave. A redirect cannot launder it either: the gate is
 * also a NETWORK interceptor, which OkHttp runs on every hop. As an application interceptor alone it saw the request
 * once, before redirects, and `followSslRedirects(false)` stops only a scheme change — so a private http endpoint
 * answering 307 to another http host had the body re-sent there in clear (the local audit, 2026-10-13). The network
 * pass runs after the connection opens and before a byte of the request is written.
 */
object TeemoonHttp {
    val cleartextGate = Interceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps && !Provider.isPrivateHost(url.host)) {
            throw IOException("CLEARTEXT communication to ${url.host} not permitted: teemoon uses plain http only " +
                "to a private host (your LAN, Tailscale, .local or this device)")
        }
        chain.proceed(chain.request())
    }

    /**
     * A hop to another host carries no key header. OkHttp drops only `Authorization` when a redirect changes host; a key
     * sent as `X-Api-Key`, `X-Subscription-Token` or a provider's own header ([CredentialHeaders]) rode the redirect to
     * the new host (the local audit's residual, 2026-10-13). Same host and port, the request is untouched.
     */
    val keyHeadersStayHome = Interceptor { chain ->
        val hop = chain.request()
        val origin = chain.call().request().url
        // host AND port: another port on the same host is another server (the filter compared hosts only at first)
        if (hop.url.host.equals(origin.host, ignoreCase = true) && hop.url.port == origin.port) return@Interceptor chain.proceed(hop)
        val keyNames = CredentialHeaders.all()
        val stripped = hop.newBuilder().apply { hop.headers.names().filter { it.lowercase() in keyNames }.forEach { removeHeader(it) } }.build()
        chain.proceed(stripped)
    }

    /** The one way to build a client: the gate (before the call and on every hop), key headers kept to their host, and no https→http redirect. Add timeouts on the result. */
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(cleartextGate)
        .addNetworkInterceptor(cleartextGate)
        .addNetworkInterceptor(keyHeadersStayHome)
        .followSslRedirects(false)

    fun client(): OkHttpClient = builder().build()
}
