// ports: android-only — the Android half of docs/RENDER_RECORD.md. No Swift source:
//        the iOS half is a separate producer writing the same rows.
package ai.teemoon.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified

/**
 * What a screen actually drew, as data — the only layer that can answer whether
 * Android's type, spacing and colour match iOS's.
 *
 * INERT IN PRODUCTION BY CONSTRUCTION. [LocalRenderRecorder] defaults to null,
 * nothing in the app installs one, and only a Paparazzi test does. With no
 * recorder present a call site is a null check and nothing else: no sink, no
 * allocation, no I/O. It is not analytics and it never leaves the process —
 * which is the distinction CONTRIBUTING.md's "no analytics" rule protects.
 *
 * RESOLVED VALUES ONLY. The point is that a framework default shows up as a
 * number whether or not anyone typed it: the chip that read 32dp against iOS's
 * 24pt was a Compose default nobody wrote down, so no source-reading tool could
 * ever have seen it (the 2026-09 port review measured why).
 */
class RenderRecorder(val screen: String) {

    private val rows = LinkedHashMap<String, Row>()

    data class Row(
        val screen: String,
        val kind: String,
        /** The join. Normalised as copy_audit.py normalises, so the two agree. */
        val text: String,
        val glyph: String? = null,
        val fontSize: Double? = null,
        val fontWeight: Int? = null,
        /** A palette TOKEN, never a hex: the palettes differ by design. */
        val color: String? = null,
        val padTop: Double? = null,
        val padLeading: Double? = null,
        val padBottom: Double? = null,
        val padTrailing: Double? = null,
        val corner: Double? = null,
    )

    /**
     * Offer a row. Elements with neither text nor a glyph are DROPPED rather
     * than given a positional key — a positional join silently pairs elements
     * that were never the same one, which is the failure the text join exists to
     * remove (docs/RENDER_RECORD.md).
     */
    fun row(
        kind: String,
        text: String? = null,
        glyph: String? = null,
        fontSize: TextUnit? = null,
        fontWeight: FontWeight? = null,
        color: String? = null,
        padding: Padding? = null,
        corner: Dp? = null,
    ) {
        val key = normalise(text.orEmpty())
        if (key.isEmpty() && glyph == null) return
        val row = Row(
            screen = screen, kind = kind, text = key, glyph = glyph,
            fontSize = fontSize?.takeIf { !it.isUnspecified }?.value?.toDouble()?.round1(),
            fontWeight = fontWeight?.weight,
            color = color,
            padTop = padding?.top?.value?.toDouble()?.round1(),
            padLeading = padding?.leading?.value?.toDouble()?.round1(),
            padBottom = padding?.bottom?.value?.toDouble()?.round1(),
            padTrailing = padding?.trailing?.value?.toDouble()?.round1(),
            corner = corner?.value?.toDouble()?.round1(),
        )
        // A DUPLICATE KEY IS A HARD ERROR, never a merge.
        //
        // First-write-wins was the original rule and it was wrong in exactly the
        // way role keys were: (screen, kind, text) is NOT unique on a real
        // screen — an `Other` labelled "save" wrapping a `Button` labelled
        // "save", a caption repeated down a settings list — and merging,
        // dropping or last-wins all resolve that collision SILENTLY, producing
        // the green this join was chosen to avoid. Raised by the teemoon-ios
        // session, which had the accessibility dump to prove it.
        //
        // A recomposition offering the identical row is not a collision, so an
        // equal value is idempotent. A DIFFERENT value under the same key means
        // two elements the record cannot tell apart, and the producer stops.
        val existing = rows[row.joinKey()]
        if (existing != null) {
            if (existing == row) return
            error("render record: two different elements share the key " +
                "kind=${row.kind} text=\"${row.text}\" glyph=${row.glyph} on screen $screen.\n" +
                "  first:  $existing\n  second: $row\n" +
                "The join cannot tell them apart, so a diff over this screen would be silently wrong. " +
                "Give one of them a distinct kind, or do not record it. See docs/RENDER_RECORD.md.")
        }
        rows[row.joinKey()] = row
    }

    data class Padding(val top: Dp, val leading: Dp, val bottom: Dp, val trailing: Dp)

    /** Sorted by key: composition order is not a contract. */
    fun toJson(): String = buildString {
        append("{\n  \"screen\": ").append(quote(screen)).append(",\n  \"rows\": [\n")
        val sorted = rows.values.sortedBy { it.joinKey() }
        sorted.forEachIndexed { i, r ->
            append("    ").append(r.json())
            if (i != sorted.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n}\n")
    }

    private fun Row.joinKey() = "$kind\u0000$text\u0000${glyph ?: ""}"   // escaped, not a raw NUL in the source

    private fun Row.json(): String = buildString {
        append("{")
        append("\"kind\": ").append(quote(kind))
        append(", \"text\": ").append(quote(text))
        glyph?.let { append(", \"glyph\": ").append(quote(it)) }
        fontSize?.let { append(", \"fontSize\": ").append(num(it)) }
        fontWeight?.let { append(", \"fontWeight\": ").append(it) }
        color?.let { append(", \"color\": ").append(quote(it)) }
        if (padTop != null || padLeading != null || padBottom != null || padTrailing != null) {
            append(", \"padding\": {")
            append("\"top\": ").append(num(padTop ?: 0.0))
            append(", \"leading\": ").append(num(padLeading ?: 0.0))
            append(", \"bottom\": ").append(num(padBottom ?: 0.0))
            append(", \"trailing\": ").append(num(padTrailing ?: 0.0))
            append("}")
        }
        corner?.let { append(", \"corner\": ").append(num(it)) }
        append("}")
    }

    companion object {
        /** The same normalisation copy_audit.py applies, so the join agrees with it. */
        fun normalise(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()

        private fun Double.round1() = Math.round(this * 10) / 10.0
        private fun num(v: Double) = if (v == Math.rint(v)) "${v.toInt()}" else "$v"
        private fun quote(s: String) =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}

/**
 * null in production, and nothing in the app installs one — see [RenderRecorder].
 * `staticCompositionLocalOf` because it never changes during a composition;
 * that costs nothing at a read when it is null.
 */
val LocalRenderRecorder = staticCompositionLocalOf<RenderRecorder?> { null }
