// android-only (iOS's requestReviewIfAppropriate has no test): the rule on the "+" tap, and
// Play In-App Review's flow when Play can't give a review card (the Play bundle's half, compiled into every test run).
// The flow against FakeReviewManager is the device test PlayReviewTest: off the device its PendingIntent is null.
package ai.teemoon.app

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreReviewTest {
    @Test fun theStoreIsAskedEveryFiveVisitsAndTheAskIsCounted() {
        val settings = AppSettings(InMemoryPrefs())
        var asked = 0
        repeat(4) { settings.incrementNumberOfVisits() }
        ChatListBuckets.requestReviewIfAppropriate(settings) { asked++ }
        assertEquals("four visits: not yet", 0, asked)
        settings.incrementNumberOfVisits()
        ChatListBuckets.requestReviewIfAppropriate(settings) { asked++ }
        assertEquals(1, asked); assertEquals(5, settings.numberOfVisitsOfLastRequest)
        ChatListBuckets.requestReviewIfAppropriate(settings) { asked++ }
        assertEquals("the same visit asks once", 1, asked)
        repeat(5) { settings.incrementNumberOfVisits() }
        ChatListBuckets.requestReviewIfAppropriate(settings) { asked++ }
        assertEquals(2, asked)
    }

    /** No Play Store, or a copy Play didn't install: the request fails, nothing is launched, nothing is shown. */
    @Test fun aRequestPlayRefusesLaunchesNothing() {
        var launched = 0
        val refusing = object : ReviewManager {
            override fun requestReviewFlow(): Task<ReviewInfo> = Tasks.forException(IllegalStateException("no Play Store"))
            override fun launchReviewFlow(activity: Activity, info: ReviewInfo): Task<Void> { launched++; return Tasks.forResult(null) }
        }
        var ran: Boolean? = null
        // the activity is never touched on this path; a real one can't be built off the device
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        PlayReview(refusing, executor = Runnable::run).request(unsafe.allocateInstance(Activity::class.java) as Activity) { ran = it }
        assertEquals(false, ran); assertEquals(0, launched)
    }
}
