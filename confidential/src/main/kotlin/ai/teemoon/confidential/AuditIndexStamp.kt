// ports: teemoon/Confidential/AuditIndexStamp.swift @ v1.0.1
package ai.teemoon.confidential

/** Freshness line under known-code. Stale after a day must be visible. */
object AuditIndexStamp {
    const val STALE_AFTER_SECONDS: Long = 86_400

    data class Stamp(val text: String, val stale: Boolean)

    /** null when there is no index to stamp. Times are epoch milliseconds. */
    fun make(hasIndex: Boolean, updated: String?, loadedAtMs: Long?, nowMs: Long): Stamp? {
        if (!hasIndex) return null
        val updatedText = if (!updated.isNullOrEmpty()) updated else "unknown"
        val fetched = loadedAtMs ?: return Stamp("audit index · updated $updatedText · fetch age unknown — verdicts may lag the fleet", true)
        val ageSeconds = (nowMs - fetched) / 1000
        val ago = relativeAgo(ageSeconds)
        if (ageSeconds > STALE_AFTER_SECONDS) return Stamp("audit index · cached $ago — verdicts may lag the fleet", true)
        return Stamp("audit index · updated $updatedText · refreshed $ago", false)
    }

    /**
     * iOS uses RelativeDateTimeFormatter's abbreviated style ("1 min. ago");
     * the JVM has no equivalent, so this is the same information in the same
     * register. The tests pin the words around it, not the unit spelling.
     */
    internal fun relativeAgo(seconds: Long): String = when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} hr ago"
        else -> { val d = seconds / 86_400; if (d == 1L) "1 day ago" else "$d days ago" }
    }
}
