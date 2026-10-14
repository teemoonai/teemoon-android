package ai.teemoon.app

// android-only: the settings sheet's push direction. "add cloud key" opened from cloud keys slid in from the LEFT on the
// Pixel (2026-10-04) — the picker sat at a shallower level than the list that pushes it, so the move animated as a pop.

import ai.teemoon.providers.WhereLocality
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLevelTest {
    /** Every push inside a sheet, parent → child, as the screens wire them in MainActivity. */
    private val pushes = listOf(
        Route.Settings to Route.PlacesHub,
        Route.Settings to Route.Appearance,
        Route.Settings to Route.ChatsSettings,
        Route.Settings to Route.Search,
        Route.Settings to Route.Credits,
        Route.PlacesHub to Route.PlacesManage(WhereLocality.HOME),
        Route.PlacesHub to Route.PlacesManage(WhereLocality.CLOUD),
        Route.PlacesManage(WhereLocality.CLOUD) to Route.CloudKeyPicker,
        Route.Where to Route.CloudKeyPicker,   // the first-run push
    )

    @Test fun everyPushGoesDeeperSoItSlidesInFromTheRight() {
        pushes.forEach { (from, to) -> assertTrue("$from → $to animates as a pop", routeLevel(to) > routeLevel(from)) }
    }
}
