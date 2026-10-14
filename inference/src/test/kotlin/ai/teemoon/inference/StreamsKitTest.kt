// ports: teemoonTests/ParsingValidationTests.swift @ v1.0.1 — over the kit's `streams` class (kit 0.4.0): the recorded
//        near.ai streams replayed through the Android parser must recover the same calls and leave the same text as iOS did.
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsKitTest {
    private val cases by lazy { Json.parseToJsonElement(StreamsKitTest::class.java.getResourceAsStream("/streams.json")!!.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonObject["cases"]!!.jsonArray }

    /** The transport's own replay: structured calls first, text recovery as the fallback, the sanitize pass at the end. */
    private fun replay(events: List<String>, specs: List<RecoveryToolSpec>): Pair<List<String>, String> {
        val parser = SseStreamParser(); val raw = StringBuilder()
        for (line in events) if (line.isNotBlank()) for (e in parser.consume("data: $line\n\n")) if (e is SseEvent.Content) raw.append(e.text)
        parser.consume("data: [DONE]\n\n")
        val structured = parser.toolCalls.filterValues { it.name.isNotEmpty() }
        val calls = if (structured.isNotEmpty()) structured else ToolCallFormat.parseAny(raw.toString(), specs)
        return calls.toSortedMap().values.map { it.name } to ToolMarkupContainment.sanitize(raw.toString()).clean
    }

    @Test fun everyRecordedStreamRecoversTheSameCallsAndLeavesTheSameText() {
        assertTrue(cases.size >= 3)
        for (c in cases) {
            val o = c.jsonObject; val name = o["name"]!!.jsonPrimitive.content
            val specs = o["availableTools"]!!.jsonArray.map { t -> val x = t.jsonObject; RecoveryToolSpec(x["name"]!!.jsonPrimitive.content, x["params"]!!.jsonArray.map { it.jsonPrimitive.content }, x["required"]!!.jsonArray.map { it.jsonPrimitive.content }) }
            val (names, finalContent) = replay(o["events"]!!.jsonArray.map { it.jsonPrimitive.content }, specs)
            for (expected in o["expectToolCalls"]!!.jsonArray.map { it.jsonPrimitive.content }) assertTrue("[$name] $expected not recovered — got $names", expected in names)
            for (marker in o["contentMustNotContain"]!!.jsonArray.map { it.jsonPrimitive.content }) assertFalse("[$name] leaked `$marker`: ${finalContent.take(200)}", finalContent.contains(marker))
            assertEquals("[$name] the calls iOS recovered", o["recoveredToolNames"]!!.jsonArray.map { it.jsonPrimitive.content }, names)
            assertEquals("[$name] the text iOS left visible", o["finalContent"]!!.jsonPrimitive.content, finalContent)
        }
    }
}
