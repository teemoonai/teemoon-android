package ai.teemoon.app

// android-only: the turn notification's channels as Android actually stores them. On 2026-10-13 a finished answer
// arrived silent with no status-bar icon (one LOW channel), then, once DEFAULT, with a chime and nothing on screen and
// the working notification's icon. TurnNotificationTest pins the intent; this pins what the platform made of it.

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TurnChannelsTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    @After fun clear() { nm.cancel(TurnService.RESULT_ID) }

    /** NotificationManager.notify is asynchronous: the record lands a moment after the call returns. */
    private fun awaitPosted(id: Int, withinMs: Long = 5_000): android.app.Notification? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < withinMs) {
            nm.activeNotifications.firstOrNull { it.id == id }?.let { return it.notification }
            Thread.sleep(50)
        }
        return null
    }

    @Test fun theWorkingChannelIsVisibleAndSilentAndTheAnswersChannelDropsABanner() {
        TurnService.channel(ctx)
        assertNotNull(nm.getNotificationChannel(TurnChannels.WORKING.id))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(TurnChannels.WORKING.id).importance)
        assertNull("the working notification starts at every send; it must not ding", nm.getNotificationChannel(TurnChannels.WORKING.id).sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(TurnChannels.ANSWERS.id).importance)
        assertNotNull("the answer is heard", nm.getNotificationChannel(TurnChannels.ANSWERS.id).sound)
        for (id in TurnChannels.RETIRED) assertNull("$id should be deleted", nm.getNotificationChannel(id))
    }

    /** The answer lands on the HIGH channel, with the moon-and-dot, so the status bar changes when it arrives. */
    @Test fun theAnswerPostsOnTheAnswersChannelWithItsOwnIcon() {
        TurnService.finish(ctx, TurnNotification.Ready, "thread-1")
        val posted = checkNotNull(awaitPosted(TurnService.RESULT_ID)) { "the result was never posted" }
        assertEquals(TurnChannels.ANSWERS.id, posted.channelId)
        assertEquals(R.drawable.ic_stat_moon_reply, posted.smallIcon.resId)
        // nothing of the chat: the title says it is ready, there is no text, and the lock screen gets the same words
        assertEquals("your answer is ready", posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertNull(posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        assertNull(posted.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("your answer is ready", posted.publicVersion?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        // `-e shot 1` holds the banner on screen for a look at the bar; the suites never pass it
        if (InstrumentationRegistry.getArguments().getString("shot") != null) Thread.sleep(8_000)
    }

    /**
     * The working notification asks to be shown at once. Android 12+ holds a foreground service's notification back
     * for up to 10 s by default, and on 2026-10-13 the moon took seconds to appear after Home was pressed.
     */
    @Test fun theWorkingNotificationShowsImmediately() {
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync { TurnService.start(ctx) }
        try {
            // The deferral happens before the notification reaches NotificationManager, so it is measured as the time
            // until it exists at all. Deferred, that is ~10 s; asked for immediately, well under a second.
            var posted: android.app.Notification? = null
            val t0 = System.currentTimeMillis()
            while (posted == null && System.currentTimeMillis() - t0 < 12_000) {
                posted = nm.activeNotifications.firstOrNull { it.id == TurnService.ONGOING_ID }?.notification
                if (posted == null) Thread.sleep(50)
            }
            val ms = System.currentTimeMillis() - t0
            assertNotNull("the service never posted its notification", posted)
            assertTrue("the working notification took $ms ms to post; it should be immediate", ms < 2_000)
            assertEquals(TurnChannels.WORKING.id, posted!!.channelId)
            assertEquals(R.drawable.ic_stat_moon, posted.smallIcon.resId)
        } finally {
            ins.runOnMainSync { TurnService.finish(ctx, TurnNotification.None, null) }
        }
    }

    /** A failure is not an answer: same channel, the moon with a "!" instead of the dot. */
    @Test fun aFailurePostsWithTheWarningMoon() {
        TurnService.finish(ctx, TurnNotification.Failed, "thread-1")
        val posted = checkNotNull(awaitPosted(TurnService.RESULT_ID)) { "the result was never posted" }
        assertEquals(TurnChannels.ANSWERS.id, posted.channelId)
        assertEquals(R.drawable.ic_stat_moon_failed, posted.smallIcon.resId)
    }
}
