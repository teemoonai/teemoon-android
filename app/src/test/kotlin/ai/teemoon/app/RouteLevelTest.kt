package ai.teemoon.app

// android-only: the settings sheet's push direction. "add cloud key" opened from cloud keys slid in from the LEFT on the
// Pixel (2026-10-04) — the picker sat at a shallower level than the list that pushes it, so the move animated as a pop.

import ai.teemoon.providers.WhereLocality
import org.junit.Assert.assertEquals
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

    /** MainActivity's parentOf for these routes (no first-run push, the form opened from where). */
    private val parentOf: (Route) -> Route? = { r ->
        when (r) {
            Route.CloudKeyPicker -> Route.PlacesManage(WhereLocality.CLOUD)
            Route.Appearance, Route.ChatsSettings, Route.Search, Route.Credits, Route.PlacesHub -> Route.Settings
            Route.SystemPrompt, Route.NearImport -> Route.ChatsSettings
            is Route.PlacesManage -> Route.PlacesHub
            is Route.Browse -> if (r.encryptedOnly) Route.Trust else Route.Where
            else -> null
        }
    }

    /**
     * "show chats" after an import swaps the sheet from settings › chats to the chat list — another stack, not its parent.
     * It animated as a pop: the list slid in from a third off the left while the import sheet closed (the maintainer's
     * Pixel, 2026-11-07, "slides in from the left, half indented"). A page from another stack appears; pushes and pops
     * inside one stack keep their slide.
     */
    @Test fun aSwapToAnotherStackAppearsWithoutASlide() {
        assertEquals(PageSwap.SWAP, pageSwap(Route.ChatsSettings, Route.Threads, parentOf))
        assertEquals(PageSwap.SWAP, pageSwap(Route.Settings, Route.Threads, parentOf))
        assertEquals(PageSwap.SWAP, pageSwap(Route.Threads, Route.Where, parentOf))
        // the first-run push (where → the provider picker) is where's own: MainActivity's parentOf says so while it runs
        val firstRun: (Route) -> Route? = { r -> if (r == Route.CloudKeyPicker) Route.Where else parentOf(r) }
        pushes.forEach { (from, to) -> val p = if (from == Route.Where) firstRun else parentOf
            assertEquals("$from → $to", PageSwap.PUSH, pageSwap(from, to, p)); assertEquals("$to → $from", PageSwap.POP, pageSwap(to, from, p)) }
    }
}
