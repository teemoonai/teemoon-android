package ai.teemoon.app

// android-only: the Play bundle's store review prompt, Play In-App Review (CONTRIBUTING.md allows it, alone of Play's
// libraries). The GitHub APK builds src/store-github, a no-op with no Google Play libraries (the maintainer 2026-11-01).

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory

object StoreReview {
    fun request(activity: Activity) = PlayReview(ReviewManagerFactory.create(activity)).request(activity)
}
