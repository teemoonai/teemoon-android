// The developer card's header with a real long model id (the maintainer's Pixel, 2026-11-13: near.ai
// "DeepSeek-V4.1-Flash-1017-abliterated" took the row, "HTTP 200" broke into "HTT / P / 200" and "E2EE" stood one letter
// a line). The layout both apps agreed (pm 2026-11-13): the provider and the status tokens never wrap or shrink; the
// model id takes what is left, on one line, cut in the middle; at the largest sizes the row wraps as a unit, never
// inside a token. The full id stays in the body and the copied text.
package ai.teemoon.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

abstract class DebugCardHeaderCase(widthDp: Int, fontScale: Float = 1f) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = widthDp * 2, screenHeight = 700, density = Density.XHIGH, fontScale = fontScale))

    private val info = LastRequestDebugInfo(providerName = "near.ai", modelID = "deepseek-ai/DeepSeek-V4.1-Flash-1017-abliterated",
        url = "https://cloud-api.near.ai/v1/chat/completions", requestHeaders = null, requestBodyJson = null, responseBody = null, toolCalls = emptyList(),
        threadID = "t", totalDuration = 4.2, timeToFirstToken = 0.8, outputTokens = 812, isE2EEActive = true, teeVerification = null)

    @Test fun theStatusTokensNeverBreak() {
        val lines = HashMap<String, Int>(); val cut = HashMap<String, Boolean>()
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp)) {
            RequestDebugCard(info, onCopy = {}, onHeaderToken = { token, n, ellipsized -> lines[token] = n; cut[token] = ellipsized })
        } } } }
        for (token in listOf("provider", "status", "e2ee", "copy")) {
            assertEquals("$token on one line: $lines", 1, lines[token])
            assertEquals("$token never cut: $cut", false, cut[token])
        }
        assertEquals("the model id on one line: $lines", 1, lines["model"])
        assertTrue("the model id is drawn: $lines", "model" in lines)
    }
}

class DebugCardHeaderTest : DebugCardHeaderCase(412)
class DebugCardHeaderSmallestWidthTest : DebugCardHeaderCase(360)
class DebugCardHeaderLargestTextTest : DebugCardHeaderCase(412, fontScale = 2f)
class DebugCardHeaderSmallestLargestTextTest : DebugCardHeaderCase(360, fontScale = 2f)

/**
 * The header's three layouts, first that fits wins — iOS's (near-import-request-header, ios 2026-11-13): one line with
 * the id middle-cut (the id counts as 60 wide for the test, so it simply truncates at default sizes); two lines, the
 * glyph, provider and status on the first and the id under them; three lines, provider / status / id.
 */
class RequestHeaderLayoutTest {
    private fun choose(available: Int, provider: Int = 100, status: Int = 120) =
        RequestHeaderLayout.choose(available = available, provider = provider, status = status, gap = 6, idMin = 60)

    @Test fun oneLineWhenProviderTheIdsMinimumAndTheStatusFit() = assertEquals(RequestHeaderLayout.ONE_LINE, choose(100 + 6 + 60 + 6 + 120))
    @Test fun twoLinesWhenOnlyProviderAndStatusFit() {
        assertEquals(RequestHeaderLayout.TWO_LINES, choose(100 + 6 + 60 + 6 + 120 - 1))
        assertEquals(RequestHeaderLayout.TWO_LINES, choose(100 + 6 + 120))
    }
    @Test fun threeLinesWhenProviderAndStatusDoNot() = assertEquals(RequestHeaderLayout.THREE_LINES, choose(100 + 6 + 120 - 1))
}
