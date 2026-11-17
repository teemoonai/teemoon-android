// conformance/disclosure.json (teemoon-ios 868edec6): the time slice of DisclosurePolicy — 4 zones × 2 tiers at one instant.
package ai.teemoon.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DisclosurePolicyKitTest {
    private val kit by lazy { Json.parseToJsonElement(DisclosurePolicyKitTest::class.java.getResourceAsStream("/disclosure.json")!!.readBytes().decodeToString()).jsonObject }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun everyCellIsTheKits() {
        val at = Instant.parse(kit.s("instant"))
        val cells = kit["cells"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, cells.size)
        for (c in cells) {
            val p = DisclosurePolicy(DisclosurePolicy.Tier.valueOf(c.s("tier").uppercase()), ZoneId.of(c.s("zone")))
            val name = "${c.s("zone")} ${c.s("tier")}"
            assertEquals(name, c.s("dateLine"), p.dateLine(at))
            assertEquals(name, c.s("toolDay"), p.toolDay(at))
            assertEquals(name, c.s("sentAt"), p.sentAt(at))
        }
    }

    /** decide: phone, home and a sealed cloud request are private; an unsealed cloud one is minimised. */
    @Test fun theTierIsTheKits() {
        for (d in kit["decide"]!!.jsonArray.map { it.jsonObject }) {
            val place = when (d.s("place")) { "phone" -> WhereLocality.PHONE; "home" -> WhereLocality.HOME; else -> WhereLocality.CLOUD }
            assertEquals(d.toString(), DisclosurePolicy.Tier.valueOf(d.s("tier").uppercase()), DisclosurePolicy.destinationTier(place, d["sealed"]!!.jsonPrimitive.boolean))
        }
    }

    /** "whatever the phone's language": the names stay English on a German phone. */
    @Test fun namesAreEnglishWhateverTheLocale() {
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val p = DisclosurePolicy(DisclosurePolicy.Tier.PRIVATE, ZoneId.of("America/Toronto"))
            assertEquals("Thursday, November 12, 2026", p.toolDay(Instant.parse("2026-11-13T01:42:00Z")))
            assertEquals("\n\n[Sent at 8:42 PM]", p.sentAt(Instant.parse("2026-11-13T01:42:00Z")))
        } finally { java.util.Locale.setDefault(was) }
    }
}
