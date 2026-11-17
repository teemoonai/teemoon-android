// ports: teemoon/Confidential/DisclosurePolicy.swift @ 868edec6 — the time slice (docs/DISCLOSURE.md; the maintainer 2026-11-12)
package ai.teemoon.providers

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a request says about the person, per destination tier (docs/DISCLOSURE.md, conformance/disclosure.json, which
 * iOS runs too). Callers ask the policy how an item renders; they never branch on the tier themselves. The maintainer
 * 2026-11-12 (1.1.0): the prompt's time stops leaking the time zone and the language — a destination that could log it
 * (a plain-TLS cloud) gets UTC; this phone, a home machine and a request sealed end to end get local time with the
 * zone named once; every name is English whatever the phone's language.
 */
class DisclosurePolicy(val tier: Tier, private val zone: ZoneId = ZoneId.systemDefault()) {
    enum class Tier { PRIVATE, MINIMISED }

    companion object {
        /** Private: this phone, a home place, or a request sealed end to end. Minimised: everything else. */
        fun destinationTier(place: WhereLocality, sealed: Boolean): Tier =
            if (place == WhereLocality.PHONE || place == WhereLocality.HOME || sealed) Tier.PRIVATE else Tier.MINIMISED

        fun of(place: WhereLocality, sealed: Boolean, zone: ZoneId = ZoneId.systemDefault()) = DisclosurePolicy(destinationTier(place, sealed), zone)

        /** iOS en_US_POSIX: English names and a fixed calendar whatever the phone's language. */
        private val ENGLISH: Locale = Locale.US
    }

    /** The system prompt's `{{date}}`: a day, never the minute, so the prefix stays cacheable all day. */
    fun dateLine(now: Instant): String = when (tier) {
        Tier.PRIVATE -> "${toolDay(now)} (${zone.id}, ${offset(now)})"
        Tier.MINIMISED -> "${toolDay(now)} (UTC)"
    }

    /** The web-search tool's "Today is …". */
    fun toolDay(now: Instant): String = format("EEEE, MMMM d, yyyy", now)

    /** Appended to a user turn on the wire, never stored; the turn's own timestamp, so the bytes repeat. */
    fun sentAt(at: Instant): String = when (tier) {
        Tier.PRIVATE -> "\n\n[Sent at ${format("h:mm a", at)}]"
        Tier.MINIMISED -> "\n\n[Sent at ${format("HH:mm", at)} UTC]"
    }

    private val renderZone: ZoneId get() = if (tier == Tier.PRIVATE) zone else ZoneOffset.UTC

    private fun format(pattern: String, at: Instant): String = DateTimeFormatter.ofPattern(pattern, ENGLISH).format(at.atZone(renderZone))

    /** "UTC+05:30", "UTC−05:00" (a true minus, as iOS's). */
    private fun offset(at: Instant): String {
        val seconds = zone.rules.getOffset(at).totalSeconds
        val minutes = Math.abs(seconds) / 60
        return String.format(ENGLISH, "UTC%s%02d:%02d", if (seconds < 0) "−" else "+", minutes / 60, minutes % 60)
    }
}
