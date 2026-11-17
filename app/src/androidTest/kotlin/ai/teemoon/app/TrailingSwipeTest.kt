// The trailing swipe as iOS's `.swipeActions(edge: .trailing)` (the maintainer's Pixel, 2026-11-08: "unequip ready now from
// where sheets not working reliably. sometimes it just shows unequip but doesnt select it"). The red label was only a
// background painted while the row moved: a swipe short of the dismiss threshold showed "unequip", sprang back, and
// nothing happened — there was no button to tap. Now a part swipe opens to a real button that stays and acts on a tap,
// and a full swipe acts at once where iOS allows it.
package ai.teemoon.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrailingSwipeTest {
    @get:Rule val compose = createComposeRule()

    private var actions = 0

    private fun host(allowsFullSwipe: Boolean = true) = compose.setContent {
        TeemoonTheme {
            Box(Modifier.width(360.dp)) {
                TrailingSwipe("unequip", { actions++ }, allowsFullSwipe = allowsFullSwipe, modifier = Modifier.testTag("row")) {
                    Box(Modifier.fillMaxWidth().height(56.dp)) { Text("glm-5.3-flash", Modifier.testTag("rowText")) }
                }
            }
        }
    }

    /** The bug: a part swipe showed "unequip" and did nothing. Now it opens to a button that stays, and a tap acts. */
    @Test fun aPartSwipeOpensAButtonThatActsOnATap() {
        host()
        compose.onNodeWithTag("row").performTouchInput { swipeLeft(startX = right - 2f, endX = right - 140.dp.toPx(), durationMillis = 400) }
        compose.waitForIdle()
        assertEquals("a part swipe acted on its own", 0, actions)
        compose.onNodeWithText("unequip").performClick()
        compose.waitForIdle()
        assertEquals("the revealed button did nothing", 1, actions)
    }

    /** A full swipe acts at once (iOS allowsFullSwipe, the default). */
    @Test fun aFullSwipeActsOnce() {
        host()
        compose.onNodeWithTag("row").performTouchInput { swipeLeft(startX = right - 2f, endX = left + 2f, durationMillis = 300) }
        compose.waitForIdle()
        assertEquals(1, actions)
    }

    /** iOS allowsFullSwipe: false (a setup's model list): a full swipe only opens; the button acts. */
    @Test fun withoutFullSwipeAFullSwipeOnlyOpens() {
        host(allowsFullSwipe = false)
        compose.onNodeWithTag("row").performTouchInput { swipeLeft(startX = right - 2f, endX = left + 2f, durationMillis = 300) }
        compose.waitForIdle()
        assertEquals("a full swipe acted where iOS forbids it", 0, actions)
        compose.onNodeWithText("unequip").performClick()
        compose.waitForIdle()
        assertEquals(1, actions)
    }

    /** A closed row's button is not there to hit: a tap on the row never acts. */
    @Test fun aClosedRowNeverActsOnATap() {
        host()
        compose.onNodeWithTag("rowText").performClick()
        compose.waitForIdle()
        assertEquals(0, actions)
    }
}
