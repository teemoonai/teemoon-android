package ai.teemoon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.teemoon.providers.AndroidKeystoreCipher
import ai.teemoon.providers.Keystore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

/**
 * A diagnostic, not a contract: the grounding request the app makes, with the key the app has, from the phone,
 * with Brave's response BODY in the log. The transcript shows only the status, so when a user
 * reports "web search failed" this is how the reason is read. Never logs the key.
 */
@RunWith(AndroidJUnit4::class)
class BraveGroundingProbeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(line: String) = Log.w("teemoon", "[probe] $line")

    @Test fun groundingRequestWithThePhonesOwnKey() {
        val key = Keystore(File(ctx.filesDir, "keys"), AndroidKeystoreCipher()).load(AppState.BRAVE_ACCOUNT)?.trim().orEmpty()
        log("brave key present=${key.isNotEmpty()} length=${key.length}")
        assumeTrue("no Brave key stored on this phone", key.isNotEmpty())
        val q = URLEncoder.encode("North Carolina shrimp and grits regional style coastal tradition", "UTF-8")
        for (params in listOf("maximum_number_of_tokens=4096&maximum_number_of_urls=10", "maximum_number_of_tokens=2048&maximum_number_of_urls=10", "")) {
            val url = URL("https://api.search.brave.com/res/v1/llm/context?q=$q" + (if (params.isEmpty()) "" else "&$params"))
            val c = url.openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("X-Subscription-Token", key)
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            val status = c.responseCode
            val body = (if (status == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            log("params='$params' status=$status body=" + body.take(400).replace('\n', ' '))
            c.disconnect()
        }
    }
}
