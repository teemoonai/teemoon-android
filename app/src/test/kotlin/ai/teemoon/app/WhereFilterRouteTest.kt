// spec: teemoon-ios docs/IMAGES.md §4e @ f04a55d2 — a where-sheet row tapped with "show only" on opens its browser with
// the same filters, and the route keeps them across process death as every route does (docs/ANDROID_NATIVE.md).
package ai.teemoon.app

import ai.teemoon.providers.ModelBrowserQuery.Filter
import org.junit.Assert.assertEquals
import org.junit.Test

class WhereFilterRouteTest {
    @Test fun aBrowseWithFiltersSurvivesTheSavedState() {
        for (f in listOf(setOf(Filter.VISION), setOf(Filter.ENCRYPTED), setOf(Filter.VISION, Filter.ENCRYPTED))) {
            val r = Route.Browse("A0000000-0000-0000-0000-000000000001", showOnly = f)
            assertEquals(r, RouteCodec.decode(RouteCodec.encode(r)))
        }
    }

    @Test fun aPlainBrowseIsAsBefore() {
        assertEquals("browse:p", RouteCodec.encode(Route.Browse("p")))
        assertEquals(Route.Browse("p", imagesOnly = true), RouteCodec.decode(RouteCodec.encode(Route.Browse("p", imagesOnly = true))))
    }
}
