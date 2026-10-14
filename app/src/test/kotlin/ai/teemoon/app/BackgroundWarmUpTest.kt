package ai.teemoon.app

// android-only: the warm-up's foreground rule (docs/ANDROID_NATIVE.md "No engine is loaded behind the user's back").

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundWarmUpTest {
    /** A download finishing in the background selects its model; warming it there loaded ~4 GB into a backgrounded app (Pixel 10 Pro, 2026-10-05). */
    @Test fun noWarmUpWhileTheAppIsAway() = assertEquals(false, BackgroundWarmUp.shouldStart(foreground = false, busy = false))
    @Test fun aForegroundSelectionWarmsUp() = assertEquals(true, BackgroundWarmUp.shouldStart(foreground = true, busy = false))
    @Test fun aRunningTurnIsLoadingItAlready() = assertEquals(false, BackgroundWarmUp.shouldStart(foreground = true, busy = true))
    /** The app left while the engine was building: the trim that would evict it already fired, so the warm-up gives it back. */
    @Test fun anEngineBuiltAfterTheAppLeftIsGivenBack() = assertEquals(true, BackgroundWarmUp.shouldEvictAfterBuild(foreground = false, busy = false))
    @Test fun anEngineAturnIsUsingIsKept() = assertEquals(false, BackgroundWarmUp.shouldEvictAfterBuild(foreground = false, busy = true))
    @Test fun aForegroundBuildIsKept() = assertEquals(false, BackgroundWarmUp.shouldEvictAfterBuild(foreground = true, busy = false))
}
