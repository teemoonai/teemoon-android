// ports: teemoon/Views/Chat/ChatsListView.swift @ v1.0.1 (DateBucket, BucketedSection, ChatRowView.compactTime, requestReviewIfAppropriate — the pure parts)
package ai.teemoon.app

import ai.teemoon.chat.ThreadEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DateBucket(val title: String) {
    TODAY("today"), YESTERDAY("yesterday"), EARLIER_THIS_WEEK("earlier this week"), EARLIER("earlier");

    companion object {
        fun of(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): DateBucket {
            val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return when {
                day == today -> TODAY
                day == today.minusDays(1) -> YESTERDAY
                !day.isBefore(today.minusDays(7)) -> EARLIER_THIS_WEEK
                else -> EARLIER
            }
        }
    }
}

data class BucketedSection(val title: String, val threads: List<ThreadEntity>)

object ChatListBuckets {
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    /** Today and yesterday show the time, this week the weekday, older the date. */
    fun compactTime(atMs: Long, bucket: DateBucket, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(atMs).atZone(zone)
        return when (bucket) { DateBucket.TODAY, DateBucket.YESTERDAY -> time.format(t); DateBucket.EARLIER_THIS_WEEK -> weekday.format(t); DateBucket.EARLIER -> monthDay.format(t) }
    }

    /** A search shows one "results" section in rank order; otherwise the date buckets, empty ones dropped. */
    fun sections(threads: List<ThreadEntity>, searching: Boolean, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<BucketedSection> {
        if (searching) return if (threads.isEmpty()) emptyList() else listOf(BucketedSection("results", threads))
        val grouped = threads.groupBy { DateBucket.of(it.timestamp, nowMs, zone) }
        return DateBucket.entries.mapNotNull { b -> grouped[b]?.takeIf { it.isNotEmpty() }?.let { BucketedSection(b.title, it) } }
    }

    /** iOS asks for a store review every five visits at most; the platform then decides whether to show anything. */
    fun shouldRequestReview(numberOfVisits: Int, numberOfVisitsOfLastRequest: Int): Boolean = numberOfVisits - numberOfVisitsOfLastRequest >= 5
}
