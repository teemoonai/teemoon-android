// ports: teemoonTests/SentBeforeConfirmedTests.swift @ e36774ed
//
// reply-machine.json sentBeforeConfirmedResolution: a sealed send that left before its record was confirmed keeps
// "pending|<recordKey>" until that record's verdict settles; pass clears it, a hard fail turns it into "failed|".
package ai.teemoon.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentBeforeConfirmedTest {
    private val resolution = Json.parseToJsonElement(javaClass.getResourceAsStream("/reply-machine.json")!!.reader().readText())
        .jsonObject.getValue("sentBeforeConfirmedResolution").jsonObject
    private fun values(e: kotlinx.serialization.json.JsonElement?) = e!!.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }

    @Test fun everyVectorResolves() {
        val vectors = resolution.getValue("vectors").jsonArray.map { it.jsonObject }
        assertTrue(vectors.size >= 3)
        for (v in vectors) {
            val name = v.getValue("name").jsonPrimitive.content
            val settle = v.getValue("settle").jsonObject
            val key = settle.getValue("recordKey").jsonPrimitive.content
            val outcome = SentBeforeConfirmed.Outcome.entries.single { it.rawValue == settle.getValue("outcome").jsonPrimitive.content }
            val got = values(v["rows"]).map { SentBeforeConfirmed.resolved(it, key, outcome) }
            assertEquals(name, values(v["expect"]), got)
            assertEquals("$name: marks", values(v["marks"]), got.map { SentBeforeConfirmed.mark(it)?.rawValue })
            v["countsAsE2EE"]?.let { counts ->
                assertEquals("$name: countsAsE2EE", counts.jsonArray.map { it.jsonPrimitive.boolean }, got.map(SentBeforeConfirmed::countsAsE2EE))
            }
        }
    }

    @Test fun vocabularyMatchesTheKit() {
        assertEquals(resolution.getValue("outcomes").jsonArray.map { it.jsonPrimitive.content }.toSet(), SentBeforeConfirmed.Outcome.entries.map { it.rawValue }.toSet())
        val marks = (resolution.getValue("marks") as JsonObject).values.mapNotNull { if (it is JsonNull) null else it.jsonPrimitive.content }.toSet()
        assertEquals(marks, SentBeforeConfirmed.Mark.entries.map { it.rawValue }.toSet())
    }

    @Test fun onlyANullRowCountsAsEndToEnd() {
        assertTrue(SentBeforeConfirmed.countsAsE2EE(null))
        assertFalse(SentBeforeConfirmed.countsAsE2EE("pending|1|n"))
        assertFalse(SentBeforeConfirmed.countsAsE2EE("failed|1|n"))
    }

    @Test fun recordKeyIsMillisecondsAndNonce() = assertEquals("1730851200000|n0", SentBeforeConfirmed.recordKey(1_730_851_200_000, "n0"))

    /** The store pass: every row of the settled record, and only those, is rewritten. */
    @Test fun settlingRewritesEveryRowOfThatRecordInTheStore() = runBlocking {
        val dao = ListChatDao(); val repo = ChatRepository(dao)
        val key = "1730851200000|n0"; val other = "1730851300000|n1"
        listOf("q" to "pending|$key", "a" to "pending|$key", "b" to "pending|$other", "c" to null).forEachIndexed { i, (id, value) ->
            dao.messages += MessageEntity(id, "t", if (id == "q") "user" else "assistant", id, i.toLong(), sentBeforeConfirmed = value)
        }
        fun now() = dao.messages.sortedBy { it.timestamp }.map { it.sentBeforeConfirmed }
        assertEquals(2, repo.settleSentBeforeConfirmed(key, SentBeforeConfirmed.Outcome.HARD_FAIL))
        assertEquals(listOf("failed|$key", "failed|$key", "pending|$other", null), now())
        assertEquals(1, repo.settleSentBeforeConfirmed(other, SentBeforeConfirmed.Outcome.PASS))
        assertEquals(listOf("failed|$key", "failed|$key", null, null), now())
        assertEquals("a failed row stays failed", 0, repo.settleSentBeforeConfirmed(key, SentBeforeConfirmed.Outcome.PASS))
    }
}
