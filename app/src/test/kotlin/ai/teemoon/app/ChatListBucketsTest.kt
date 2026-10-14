// Kotlin-self pins for ChatsListView's pure parts (no Swift tests exist): the date buckets, compact time, sections, the review rule.
package ai.teemoon.app

import ai.teemoon.chat.ThreadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ChatListBucketsTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-09-03T15:30:00Z").toEpochMilli()
    private val h = 3_600_000L; private val d = 24 * h

    @Test fun bucketsFollowTheCalendarNotTheClock() {
        assertEquals(DateBucket.TODAY, DateBucket.of(now - 2 * h, now, zone))
        assertEquals("just after midnight is still today", DateBucket.TODAY, DateBucket.of(Instant.parse("2026-09-03T00:10:00Z").toEpochMilli(), now, zone))
        assertEquals(DateBucket.YESTERDAY, DateBucket.of(now - 16 * h, now, zone))
        assertEquals(DateBucket.EARLIER_THIS_WEEK, DateBucket.of(now - 4 * d, now, zone))
        assertEquals("seven days back is still this week", DateBucket.EARLIER_THIS_WEEK, DateBucket.of(Instant.parse("2026-08-27T09:00:00Z").toEpochMilli(), now, zone))
        assertEquals(DateBucket.EARLIER, DateBucket.of(now - 9 * d, now, zone))
    }
    @Test fun compactTimeShowsTheUnitTheBucketNeeds() {
        val t = Instant.parse("2026-09-01T09:05:00Z").toEpochMilli()
        assertTrue(ChatListBuckets.compactTime(t, DateBucket.TODAY, zone).contains("9:05"))
        assertEquals("Tue", ChatListBuckets.compactTime(t, DateBucket.EARLIER_THIS_WEEK, zone))
        assertEquals("Sep 1", ChatListBuckets.compactTime(t, DateBucket.EARLIER, zone))
    }
    @Test fun sectionsDropEmptyBucketsAndSearchIsOneRankedSection() {
        val a = ThreadEntity("a", "a", now - h); val b = ThreadEntity("b", "b", now - 3 * d); val c = ThreadEntity("c", "c", now - 30 * d)
        assertEquals(listOf("today", "earlier this week", "earlier"), ChatListBuckets.sections(listOf(a, b, c), false, now, zone).map { it.title })
        assertEquals(listOf("results"), ChatListBuckets.sections(listOf(c, a), true, now, zone).map { it.title })
        assertEquals(listOf("c", "a"), ChatListBuckets.sections(listOf(c, a), true, now, zone).single().threads.map { it.id })
        assertTrue(ChatListBuckets.sections(emptyList(), true, now, zone).isEmpty())
    }
    @Test fun reviewIsAskedEveryFiveVisitsAtMost() {
        assertFalse(ChatListBuckets.shouldRequestReview(4, 0)); assertTrue(ChatListBuckets.shouldRequestReview(5, 0)); assertFalse(ChatListBuckets.shouldRequestReview(9, 5))
    }
}
