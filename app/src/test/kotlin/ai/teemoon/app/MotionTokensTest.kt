// Every animation takes its spec from Motion (card motion-pass item 9): the audit counted 12 hand-tuned specs on Android
// for 7 jobs, among them two springs 20 stiffness apart doing the same job. A spec written at a call site fails here.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MotionTokensTest {
    private val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Motion.kt" }.toList()
    private val spec = Regex("""\b(tween|spring|infiniteRepeatable|keyframes)\s*[<(]""")

    @Test fun noSpecIsTunedAtItsCallSite() {
        assertTrue("the sources were found from ${File(".").absolutePath}", sources.size > 50)
        val hits = sources.flatMap { f -> f.readLines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//")
            val doc = line.trimStart().let { it.startsWith("*") || it.startsWith("/*") }
            if (!doc && spec.containsMatchIn(code)) "${f.name}:${i + 1}: ${line.trim()}" else null
        } }
        assertEquals("specs outside Motion.kt — take a Motion token:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    /** The spatial token never bounces: a size or a place that overshoots and comes back is the strip's old spring. */
    @Test fun spatialHasNoBounce() {
        val s = Motion.spatial<Float>()
        assertEquals(1f, s.dampingRatio)
    }
}
