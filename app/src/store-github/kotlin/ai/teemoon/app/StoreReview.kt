package ai.teemoon.app

// android-only: the store review prompt where there is no store. This is the GitHub APK and every local build; the Play
// bundle builds src/store-play instead (app/build.gradle.kts, `-Pteemoon.store=play`). the maintainer 2026-11-01: the APK
// carries no Google Play libraries at all, not a disabled one.

import android.app.Activity

object StoreReview {
    fun request(activity: Activity) = Unit
}
