// ports: teemoonTests/EngineLoadObserverTests.swift @ v1.1.0 (the observer half: a load placed on the turn's clock, and
//        which lines reach the chip)
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineLoadObserverTest {
    @Test fun aLoadIsPlacedOnTheTurnsClock() {
        val send = 1_000_000L
        val observed = EngineLoadObserver.Observed(startedMs = send - 2500, endedMs = send + 4000, imageCapable = true, cachePresent = true)
        assertEquals(EngineLoadLine.Load(-2500.0, 4000.0, true, true, null), observed.load(send))
        val finishedBefore = EngineLoadObserver.Observed(startedMs = send - 9000, endedMs = send - 1000, imageCapable = true, cachePresent = false)
        assertNull("a load that finished before the send is no load", finishedBefore.load(send).loadStartMs)
    }

    @Test fun theChipGetsALineOnlyForALoadingLine() {
        val observer = EngineLoadObserver()
        val send = 1_000_000L
        assertNull(observer.line(send, send + 5000))
        observer.began(send, imageCapable = true, cachePresent = false)
        assertEquals(EngineLoadLine.PREPARING_FOR_IMAGES, observer.line(send, send + 1000))
        observer.ended(send + 30_000)
        assertNull("after the load it reads as thinking", observer.line(send, send + 31_000))
        observer.began(send, imageCapable = false, cachePresent = true)
        assertNull(observer.line(send, send + 2000))
        assertEquals(EngineLoadLine.LOADING, observer.line(send, send + 3000))
        observer.rebuildSeen(send + 4000)
        assertEquals("a text-only rebuild never says images", EngineLoadLine.LOADING, observer.line(send, send + 5000))
    }

    @Test fun theFirstRebuildIsKept() {
        val observer = EngineLoadObserver()
        observer.began(0, imageCapable = true, cachePresent = true)
        observer.rebuildSeen(1200); observer.rebuildSeen(5000)
        assertEquals(1200L, observer.current?.rebuildSeenMs)
    }

    @Test fun theChipSaysTheLineWithItsGlyphState() {
        assertEquals(ActivityState.LoadingModel(EngineLoadLine.LOADING), ActivityState.of(false, 0, loadLine = EngineLoadLine.LOADING, thinkingHarder = true))
        assertEquals("loading the model…", activityLabel(ActivityState.LoadingModel(EngineLoadLine.LOADING), 7.0))
        assertEquals("a running search still wins", ActivityState.Searching, ActivityState.of(true, 0, loadLine = EngineLoadLine.LOADING))
    }
}
