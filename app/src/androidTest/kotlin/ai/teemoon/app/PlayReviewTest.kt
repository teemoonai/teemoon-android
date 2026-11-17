package ai.teemoon.app

// android-only: Play In-App Review's whole flow against FakeReviewManager, on a device (its ReviewInfo needs a real
// PendingIntent). Compiled into every device build: the Play half's source and library are the androidTest's always.

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.play.core.review.testing.FakeReviewManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlayReviewTest {
    @Test fun theFlowRequestsAndLaunchesTheCard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val done = CountDownLatch(1); var ran: Boolean? = null
            scenario.onActivity { a -> PlayReview(FakeReviewManager(a)).request(a) { ran = it; done.countDown() } }
            done.await(10, TimeUnit.SECONDS)
            assertEquals(true, ran)
        }
    }
}
