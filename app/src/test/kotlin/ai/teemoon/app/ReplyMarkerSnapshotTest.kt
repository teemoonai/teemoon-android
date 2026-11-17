package ai.teemoon.app

// reply-markers (the maintainer approved, 2026-11-06): the four lines under a reply, as the chat draws them — "didn't
// finish" with its retry, stopped and cut short under the text that came, undecryptable under no text — at 100%.

import ai.teemoon.chat.ReplyMarker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class ReplyMarkerSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 411, screenHeight = 900, density = com.android.resources.Density.MEDIUM))

    @Test fun replyMarkers() = paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column { AssistantMessageView(messageId = "a1", content = "", generatingTimeMs = null, isLLMRunning = false, streaming = false, sources = emptyList()); ReplyMarkerLine(ReplyMarker.DIDNT_FINISH.rawValue, onRetry = {}) }
        Column { AssistantMessageView(messageId = "a2", content = "The sky looks blue because air scatters short", generatingTimeMs = null, isLLMRunning = false, streaming = false, sources = emptyList()); ReplyMarkerLine(ReplyMarker.STOPPED.rawValue) }
        Column { AssistantMessageView(messageId = "a3", content = "Here are the first three steps: open settings, then", generatingTimeMs = null, isLLMRunning = false, streaming = false, sources = emptyList()); ReplyMarkerLine(ReplyMarker.CUT_SHORT.rawValue) }
        Column { AssistantMessageView(messageId = "a5", content = "Paris is the capital of France.", generatingTimeMs = null, isLLMRunning = false, streaming = false, sources = emptyList())
            ReplyMarkerLine(ai.teemoon.chat.SentBeforeConfirmed.Mark.SENT_WHILE_CHECKING.rawValue) }
        Column { AssistantMessageView(messageId = "a6", content = "Paris is the capital of France.", generatingTimeMs = null, isLLMRunning = false, streaming = false, sources = emptyList())
            ReplyMarkerLine(ai.teemoon.chat.SentBeforeConfirmed.Mark.CHECK_FAILED_AFTER_SEND.rawValue, failed = true) }
        Column { AssistantMessageView(messageId = "a4", content = "", generatingTimeMs = null, isLLMRunning = false, streaming = false, sources = emptyList()); ReplyMarkerLine(ReplyMarker.DECRYPT_FAILED.rawValue) }
    } } } }

    /**
     * The reply and its marker as the transcript draws them: inside AnimatedContent, whose content is a Box. The marker
     * sat on the reply's first line there (the maintainer's Pixel, 2026-11-07: "you stopped this reply." over the title).
     */
    @Test fun aMarkerSitsUnderItsReplyInsideTheReplySlot() = paparazzi.snapshot { TeemoonTheme { Surface {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
            ReplyWithMarkers(reply = {
                AssistantMessageView(messageId = "s1", content = "The Last Keeper's Light\n\nElias had tended the light for forty-one years, and in all that time he had never once missed a night.",
                    generatingTimeMs = null, isLLMRunning = false, streaming = false, sources = emptyList())
            }, sentMark = null, marker = ReplyMarker.STOPPED.rawValue, onRetry = null)
        }
    } } }
}

