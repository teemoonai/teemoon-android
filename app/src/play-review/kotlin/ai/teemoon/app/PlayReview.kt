package ai.teemoon.app

// android-only: Play In-App Review, where iOS's ChatsListView calls StoreKit's requestReview() (the rule is
// ChatListBuckets.requestReviewIfAppropriate). Built into the Play bundle only (`-Pteemoon.store=play`), and into the JVM tests always.

import android.app.Activity
import com.google.android.play.core.review.ReviewManager
import java.util.concurrent.Executor

/**
 * Asks Play for its review card. As with StoreKit, Play decides whether anything shows (its own quota; nothing for a
 * copy not installed from Play), and nothing tells us whether the user rated, so a failure is not reported to them:
 * [done] gets whether the flow ran, for the test. [executor] is where Play's callbacks land (its main thread unless
 * a test says otherwise).
 */
class PlayReview(private val manager: ReviewManager, private val executor: Executor? = null) {
    fun request(activity: Activity, done: (Boolean) -> Unit = {}) {
        val info = manager.requestReviewFlow()
        val onInfo = com.google.android.gms.tasks.OnCompleteListener<com.google.android.play.core.review.ReviewInfo> { t ->
            if (!t.isSuccessful) { done(false); return@OnCompleteListener }
            val launch = manager.launchReviewFlow(activity, t.result)
            val onLaunch = com.google.android.gms.tasks.OnCompleteListener<Void> { done(it.isSuccessful) }
            if (executor != null) launch.addOnCompleteListener(executor, onLaunch) else launch.addOnCompleteListener(onLaunch)
        }
        if (executor != null) info.addOnCompleteListener(executor, onInfo) else info.addOnCompleteListener(onInfo)
    }
}
