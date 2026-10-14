package ai.teemoon.app

// android-only: both halves of the cleartext gate on a real Android network stack. The JVM test (TeemoonHttpTest)
// cannot see the network-security-config; this does. Hermetic: the server is in this process, reached through the
// device's own PRIVATE address (not loopback), which is exactly what "connect a computer" on a LAN looks like to the
// platform. Until 2026-10-02 the config permitted only 10.0.2.2 and this request died with
// "CLEARTEXT communication to <private ip> not permitted by network security policy".

import ai.teemoon.providers.Provider
import ai.teemoon.providers.TeemoonHttp
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CleartextGateDeviceTest {
    private fun privateAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().map { it.hostAddress!! }
        .firstOrNull { Provider.isPrivateHost(it) }

    /** Plain http to a private address passes both halves — the platform config and the runtime gate. */
    @Test fun plainHttpToAPrivateAddressGoesThrough() {
        val ip = privateAddress()
        assumeTrue("no private IPv4 on this device", ip != null)
        val server = ServerSocket(0, 4, InetAddress.getByName("0.0.0.0"))
        thread(isDaemon = true) {
            runCatching { server.accept().use { c ->
                c.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) { /* drain the response headers */ } }
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
            } }
        }
        try {
            TeemoonHttp.client().newCall(Request.Builder().url("http://$ip:${server.localPort}/v1/models").build()).execute().use { resp ->
                assertEquals("http://$ip must reach a server on the device's own network", 200, resp.code)
            }
        } finally { server.close() }
    }

    /** Plain http to a public host is refused by the runtime gate before any socket opens. */
    @Test fun plainHttpToAPublicHostIsRefusedOnDevice() {
        val e = runCatching { TeemoonHttp.client().newCall(Request.Builder().url("http://example.com/").build()).execute().close() }.exceptionOrNull()
        assertTrue("public http went through: $e", e is IOException && e.message!!.contains("CLEARTEXT"))
    }
}
