// ports: teemoonTests/ReplyMachineVectorsTests.swift @ 1044becc
//
// conformance/reply-machine.json is the reply lifecycle Android ports from (pm ios-state-machines Tier 2, G1–G8).
// These tests hold the kit to itself: every name it uses is declared, every state is reachable, terminal states
// persist a statusRaw and restart only through the restart events, and every vector replays through the kit's own
// table to the outcome it states. No app type is involved: ReplyMachineTest replays the same vectors through the
// real ReplyMachine.
//
// The kit is read as plain Kotlin values (Map/List/String/Int/Boolean/null), as iOS reads it through
// JSONSerialization, so each check here is the Swift check line for line. A key present with a JSON null is
// `containsKey && value == null` — Swift's `is NSNull`.
package ai.teemoon.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class ReplyMachineVectorsTest {

    companion object {
        /** The vendored kit (conformance/vendor/reply-machine.json, kit 0.6.6), byte for byte iOS's. */
        val kit: Map<String, Any?> by lazy {
            val text = ReplyMachineVectorsTest::class.java.getResourceAsStream("/reply-machine.json")!!.reader().readText()
            plain(Json.parseToJsonElement(text)) as Map<String, Any?>
        }

        fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> LinkedHashMap<String, Any?>().apply { for ((k, v) in e) put(k, plain(v)) }
            is JsonArray -> e.map { plain(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.intOrNull != null -> e.intOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.content.toDouble()
            }
        }

        fun obj(any: Any?): Map<String, Any?>? = any as? Map<String, Any?>
        fun objs(any: Any?): List<Map<String, Any?>> = (any as? List<*>)?.mapNotNull { obj(it) } ?: emptyList()

        fun states(kit: Map<String, Any?>): Map<String, Map<String, Any?>> =
            obj(kit["states"])?.mapValues { obj(it.value) ?: emptyMap() } ?: emptyMap()
        fun events(kit: Map<String, Any?>): Map<String, Map<String, Any?>> =
            obj(kit["events"])?.mapValues { obj(it.value) ?: emptyMap() } ?: emptyMap()
        fun transitions(kit: Map<String, Any?>): List<Map<String, Any?>> = objs(kit["transitions"])
        fun vectors(kit: Map<String, Any?>): List<Map<String, Any?>> = objs(kit["vectors"])
        fun statusValues(kit: Map<String, Any?>): Set<String> =
            ((obj(obj(kit["persistedFields"])?.get("statusRaw"))?.get("values") as? List<*>)?.filterIsInstance<String>() ?: emptyList()).toSet()
        fun reloadMarkers(kit: Map<String, Any?>): Map<String, Any?> =
            obj(obj(obj(kit["persistedFields"])?.get("statusRaw"))?.get("reloadMarker")) ?: emptyMap()
        fun strings(any: Any?): List<String> = (any as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    }

    @Test fun header() {
        assertEquals("reply-machine", kit["fixtureClass"])
        assertEquals("0.6.6", kit["kitVersion"])
        assertEquals("teemoon-ios", kit["sourceOfTruth"])
        assertFalse(((kit["rule"] as? String) ?: "").isEmpty())
    }

    @Test fun everyNameInTheTableIsDeclared() {
        val states = states(kit); val events = events(kit)
        val ops = obj(kit["ops"])?.keys ?: emptySet()
        val guards = obj(kit["guards"])?.keys ?: emptySet()
        assertNotNull(states[kit["initialState"] as? String ?: ""])
        for (row in transitions(kit)) {
            val label = "${row["from"] ?: "?"} × ${row["event"] ?: "?"}"
            assertTrue("$label: from", states[row["from"] as? String ?: ""] != null)
            assertTrue("$label: to", states[row["to"] as? String ?: ""] != null)
            assertTrue("$label: event", events[row["event"] as? String ?: ""] != null)
            for (op in strings(row["ops"])) assertTrue("$label: op $op", op in ops)
            for (key in obj(row["when"])?.keys ?: emptySet()) assertTrue("$label: guard $key", key in guards)
            assertTrue("$label: effects", strings(row["effects"]).isNotEmpty())
        }
    }

    @Test fun noTwoRowsMatchTheSameStateEventAndGuard() {
        val seen = HashSet<String>()
        for (row in transitions(kit)) {
            val whenKey = obj(row["when"])?.let { w -> w.keys.sorted().joinToString(",") { "$it=${w[it]}" } } ?: ""
            val key = "${row["from"]}|${row["event"]}|$whenKey"
            assertTrue("duplicate row $key", seen.add(key))
        }
    }

    @Test fun everyStateIsReachable() {
        val initial = kit["initialState"] as String
        val reached = mutableSetOf(initial); val frontier = mutableListOf(initial)
        while (frontier.isNotEmpty()) {
            val state = frontier.removeAt(frontier.lastIndex)
            for (row in transitions(kit)) if (row["from"] == state) {
                val to = row["to"] as? String
                if (to != null && reached.add(to)) frontier.add(to)
            }
        }
        assertEquals(states(kit).keys, reached)
    }

    @Test fun everyLiveStateCanEnd() {
        for ((name, state) in states(kit)) if (state["kind"] == "live") {
            val events = transitions(kit).filter { it["from"] == name }.mapNotNull { it["event"] as? String }.toSet()
            assertTrue("$name lacks an exit", events.containsAll(listOf("userStop", "appKilled", "deleteChat", "retry", "thinkHarder")))
        }
    }

    @Test fun everyTerminalStatePersistsAStatusRaw() {
        val states = states(kit); val values = statusValues(kit)
        val terminal = strings(kit["terminalStates"])
        assertTrue(kit["terminalStates"] is List<*>)
        assertEquals(states.filter { it.value["kind"] == "terminal" }.keys, terminal.toSet())
        for (name in terminal) {
            val state = states[name] ?: kitFail("$name is not a state")
            assertTrue("$name has no statusRaw", state.containsKey("statusRaw"))
            when (state["persist"] as? String) {
                "kept" -> assertTrue("$name persists an undeclared statusRaw", (state["statusRaw"] as? String ?: "") in values)
                "untouched", "deleted" ->
                    assertTrue("$name writes no row, so no statusRaw", state.containsKey("statusRaw") && state["statusRaw"] == null)
                else -> fail("$name: persist must be kept, untouched or deleted")
            }
        }
        // The value written at turn start, read back at launch, is one of the persisted values.
        assertTrue("interrupted" in values)
    }

    @Test fun terminalStatesRestartOnlyThroughRestartEvents() {
        val terminal = strings(kit["terminalStates"]).toSet()
        val restart = strings(kit["restartEvents"]).toSet()
        assertTrue(events(kit).keys.containsAll(restart))
        for (row in transitions(kit)) if ((row["from"] as? String ?: "") in terminal) {
            assertTrue("${row["from"]} × ${row["event"]}", (row["event"] as? String ?: "") in restart)
        }
    }

    @Test fun markersAreIdsAndCoverEveryStatus() {
        val markers = obj(kit["markers"])?.keys ?: emptySet()
        assertEquals(setOf("marker.didntFinish", "marker.stopped", "marker.cutShort", "marker.decryptFailed"), markers)
        val reload = obj(obj(obj(kit["persistedFields"])?.get("statusRaw"))?.get("reloadMarker")) ?: kitFail("no reloadMarker")
        assertEquals(statusValues(kit) + "nil", reload.keys)
        for ((_, marker) in reload) (marker as? String)?.let { assertTrue(it in markers) }
        for ((name, state) in states(kit)) (state["marker"] as? String)?.let { assertTrue(name, it in markers) }
    }

    @Test fun everyBugHasAVector() {
        val cache = objs(obj(kit["engineCache"])?.get("vectors"))
        val bugs = (vectors(kit) + cache).mapNotNull { it["bug"] as? String }.toSet()
        assertEquals((1..8).map { "G$it" }.toSet(), bugs)
        val named = vectors(kit).mapNotNull { it["name"] as? String }.toSet()
        assertEquals("vector names are unique", vectors(kit).size, named.size)
    }

    @Test fun platformNotesNameRealVectors() {
        val names = vectors(kit).mapNotNull { it["name"] as? String }.toSet()
        for (note in objs(kit["platformNotes"])) {
            assertFalse(((note["note"] as? String) ?: "").isEmpty())
            for (name in strings(note["vectors"])) assertTrue(name, name in names)
        }
    }

    @Test fun vectorsUseOnlyDeclaredNames() {
        val events = events(kit); val states = states(kit); val values = statusValues(kit)
        val recordKey = Regex("""^(pending|failed)\|\d+\|.+$""")
        for (vector in vectors(kit)) {
            val name = vector["name"] as? String ?: "?"
            for (event in objs(vector["events"])) {
                val kind = event["event"] as? String ?: ""
                val declared = events[kind] ?: kitFail("$name: undeclared event $kind")
                val payload = strings(declared["payload"]).toSet() + "event"
                assertTrue("$name: $kind carries undeclared fields", payload.containsAll(event.keys))
            }
            val expect = obj(vector["expect"]) ?: kitFail("$name: no expect")
            assertTrue("$name: undeclared state", states[expect["state"] as? String ?: ""] != null)
            for (row in objs(expect["rows"])) {
                (row["statusRaw"] as? String)?.let { assertTrue("$name: statusRaw $it", it in values) }
                (row["sentBeforeConfirmed"] as? String)?.let { assertTrue("$name: $it", recordKey.matches(it)) }
            }
        }
    }

    @Test fun vectorsReplayThroughTheTable() {
        val reload = reloadMarkers(kit)
        for (vector in vectors(kit)) {
            val name = vector["name"] as? String ?: "?"
            val replay = ReplyKitReplay(kit, objs(vector["given"]))
            val checkpoints = objs(vector["checkpoints"])
            for ((index, event) in objs(vector["events"]).withIndex()) {
                replay.apply(event, index)
                for (point in checkpoints) if (point["after"] == index) {
                    assertEquals("$name after $index", point["state"], replay.state)
                    assertEquals("$name after $index: live", point["live"], replay.live)
                    (point["resumeArmed"] as? Boolean)?.let { assertEquals("$name after $index", it, replay.armed) }
                }
            }
            val expect = obj(vector["expect"]) ?: kitFail("$name: no expect")
            assertEquals("$name: state", expect["state"], replay.state)
            assertEquals("$name: live", expect["live"], replay.live)
            assertEquals("$name: ignored", (expect["ignored"] as? List<*>) ?: emptyList<Int>(), replay.ignored)
            assertEquals("$name: resumeArmed", (expect["resumeArmed"] as? Boolean) ?: false, replay.armed)
            val rows = objs(expect["rows"]).map { ReplyKitRow.of(it) }
            assertEquals("$name: rows", rows, replay.rows.values.sortedBy { it.generation })
            for ((generation, marker) in obj(expect["markers"]) ?: emptyMap()) {
                val raw = replay.rows[generation.toIntOrNull() ?: -1]?.statusRaw ?: "nil"
                assertEquals("$name: reload marker of $generation", marker, reload[raw] as? String)
            }
            (expect["sessionMarker"] as? String)?.let {
                assertEquals("$name: session marker", it, states(kit)[replay.state]?.get("marker") as? String)
            }
            (expect["requests"] as? List<*>)?.let { requests ->
                assertEquals("$name: requests", objs(requests).map { listOf(it["generation"] as? Int ?: -1, it["images"] as? Int ?: -1) }, replay.requests)
            }
            (expect["userTurns"] as? Int)?.let { assertEquals("$name: user turns", it, replay.userTurns) }
            (expect["draft"] as? String)?.let { assertEquals("$name: draft", it, replay.draft) }
            (expect["returned"] as? List<*>)?.let { assertEquals("$name: returned", it, replay.returned) }
        }
    }

    @Test fun engineCacheVectorsReplay() {
        val cache = obj(kit["engineCache"]) ?: kitFail("no engineCache")
        val rows = objs(cache["transitions"])
        val states = strings(cache["states"]).toSet(); val events = strings(cache["events"]).toSet()
        for (row in rows) {
            assertTrue((row["from"] as? String ?: "") in states && (row["to"] as? String ?: "") in states)
            assertTrue((row["event"] as? String ?: "") in events)
        }
        for (vector in objs(cache["vectors"])) {
            var state = "empty"; var epoch = 0
            for (event in objs(vector["events"])) {
                val kind = event["event"] as? String ?: ""
                if (kind == "evict") epoch += 1
                val current = (event["epoch"] as? Int)?.let { it == epoch }
                val row = rows.firstOrNull { row ->
                    if (row["from"] != state || row["event"] != kind) return@firstOrNull false
                    val want = obj(row["when"])?.get("epochCurrent") as? Boolean ?: return@firstOrNull true
                    want == current
                }
                (row?.get("to") as? String)?.let { state = it }
            }
            val expect = obj(vector["expect"]) ?: kitFail("no expect")
            assertEquals("${vector["name"] ?: "?"}", expect["state"], state)
            assertEquals(expect["resident"], if (state == "resident") 1 else 0)
            assertEquals(expect["epoch"], epoch)
        }
    }
}

/** A persisted reply row as the vectors state it. */
data class ReplyKitRow(
    val generation: Int,
    val statusRaw: String?,
    val content: String,
    val sources: Int,
    val sentBeforeConfirmed: String?,
) {
    companion object {
        fun of(o: Map<String, Any?>) = ReplyKitRow(
            generation = o["generation"] as? Int ?: -1, statusRaw = o["statusRaw"] as? String,
            content = o["content"] as? String ?: "", sources = o["sources"] as? Int ?: 0,
            sentBeforeConfirmed = o["sentBeforeConfirmed"] as? String,
        )
    }
}

/**
 * Runs vectors through the kit's own table and ops, exactly as the kit's `ops` and `staleRule` describe them.
 * It stands in for nothing in the app: it proves the vectors agree with the table they ship with.
 */
class ReplyKitReplay(private val kit: Map<String, Any?>, given: List<Map<String, Any?>>) {
    class Generation(
        val place: String, val supersedes: List<Int>, var text: String = "", var sources: Int = 0, var sbc: String? = null,
        var images: Int = 0, var origin: String = "send", val replaces: List<Int> = emptyList(), var sent: Boolean = false,
    ) {
        val isRetry: Boolean get() = origin in listOf("retry", "reverifyRetry", "autoResume")
        val unsentRetry: Boolean get() = isRetry && !sent
    }

    var state: String = kit["initialState"] as? String ?: "idle"
    var live: Int? = null
    var highest = 0
    var armed = false
    val generations = HashMap<Int, Generation>()
    val rows = HashMap<Int, ReplyKitRow>()
    val ignored = ArrayList<Int>()
    val requests = ArrayList<List<Int>>()
    var userTurns = 0
    var draft: String? = null
    val returned = ArrayList<Int>()
    private var questionImages = 0

    init {
        for (row in given.map { ReplyKitRow.of(it) }) { rows[row.generation] = row; highest = maxOf(highest, row.generation) }
    }

    fun apply(event: Map<String, Any?>, index: Int) {
        val kind = event["event"] as? String ?: kitFail("event without a kind")
        if (kind == "draftEdited") draft = "kept"
        val category = ReplyMachineVectorsTest.events(kit)[kind]?.get("category") as? String ?: ""
        if ((category == "engine" || category == "gate" || kind == "confirm" || kind == "decline") &&
            event["generation"] as? Int != live
        ) { ignored.add(index); return }
        if (kind == "reverifyRetry" && event["captured"] as? Int != highest) { ignored.add(index); return }

        val current = live?.let { generations[it] }
        val facts: Map<String, Any?> = mapOf(
            "supersedes" to !(current?.supersedes?.isEmpty() ?: true),
            "hasText" to !(current?.text?.isBlank() ?: true),
            "place" to (current?.place ?: ""),
            "resumeArmed" to armed,
            "reason" to (event["reason"] as? String ?: ""),
            "origin" to (current?.origin ?: ""),
            "unsentRetry" to (current?.unsentRetry ?: false),
            "restorable" to (current != null && current.unsentRetry && current.replaces.any { g ->
                val row = rows[g] ?: return@any false
                row.content.isNotBlank() && (row.statusRaw ?: "") !in listOf("interrupted", "failed", "decryptFailed")
            }),
        )
        val match = ReplyMachineVectorsTest.transitions(kit).firstOrNull { row ->
            if (row["from"] != state || row["event"] != kind) return@firstOrNull false
            (ReplyMachineVectorsTest.obj(row["when"]) ?: emptyMap()).all { (key, want) ->
                when (want) {
                    is List<*> -> (facts[key] as? String ?: "") in want.filterIsInstance<String>()
                    is Boolean -> facts[key] as? Boolean == want
                    else -> facts[key] as? String == want as? String
                }
            }
        }
        val row = match ?: return
        val to = row["to"] as? String ?: return
        for (op in ReplyMachineVectorsTest.strings(row["ops"])) run(op, event, to)
        state = to
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(op: String, event: Map<String, Any?>, to: String) {
        when (op) {
            "retireLive" -> {
                val g = live; val c = g?.let { generations[it] }
                if (g != null && c != null && c.supersedes.isEmpty() && !c.unsentRetry) {
                    rows[g] = ReplyKitRow(g, "stopped", c.text, c.sources, c.sbc)
                }
                live = null
            }
            "deleteRows" -> for (g in (event["replaces"] as? List<*>)?.filterIsInstance<Int>() ?: emptyList()) rows.remove(g)
            "persistQuestion" -> {
                userTurns += 1
                questionImages = event["images"] as? Int ?: 0
                if (draft == "kept") draft = "consumed"
            }
            "returnQuestion" -> {
                userTurns -= 1
                returned.add(live ?: kitFail("returnQuestion with no live generation"))
            }
            "sendRequest" -> {
                val g = live ?: kitFail("sendRequest with no live generation")
                generations[g]?.let { c ->
                    if (c.unsentRetry) {
                        for (old in c.replaces) rows.remove(old)
                        rows[g] = ReplyKitRow(g, "interrupted", "", 0, c.sbc)
                    }
                    c.sent = true
                }
                requests.add(listOf(g, generations[g]?.images ?: 0))
            }
            "newGeneration" -> {
                val g = event["generation"] as? Int ?: kitFail("newGeneration without a generation")
                live = g; highest = maxOf(highest, g); armed = false
                generations[g] = Generation(event["place"] as? String ?: "remote",
                    (event["supersedes"] as? List<*>)?.filterIsInstance<Int>() ?: emptyList(),
                    images = if (event["event"] == "send") questionImages else 0,
                    origin = event["event"] as? String ?: "",
                    replaces = (event["replaces"] as? List<*>)?.filterIsInstance<Int>() ?: emptyList())
                questionImages = 0
            }
            "writeLiveRow" -> {
                val g = live
                if (g != null && generations[g]?.supersedes?.isEmpty() == true) rows[g] = ReplyKitRow(g, "interrupted", "", 0, null)
            }
            "setSentBeforeConfirmed" -> {
                val g = live ?: kitFail("setSentBeforeConfirmed with no live generation")
                generations[g]?.sbc = event["sentBeforeConfirmed"] as? String
                rows[g]?.let { rows[g] = it.copy(sentBeforeConfirmed = event["sentBeforeConfirmed"] as? String) }
            }
            "appendText" -> generations[live ?: kitFail("appendText with no live generation")]?.let { it.text += event["text"] as? String ?: "" }
            "appendSources" -> generations[live ?: kitFail("appendSources with no live generation")]?.let { it.sources += event["sources"] as? Int ?: 0 }
            "armResumeUnlessSupersedes" -> armed = generations[live ?: kitFail("arm with no live generation")]?.supersedes?.isEmpty() ?: false
            "disarmResume" -> armed = false
            "kill" -> live = null
            "end" -> {
                val g = live ?: kitFail("end with no live generation")
                val c = generations[g] ?: kitFail("end of an unknown generation")
                val target = ReplyMachineVectorsTest.states(kit)[to] ?: kitFail("end into an unknown state $to")
                if (c.unsentRetry && to != "restored") for (old in c.replaces) rows.remove(old)
                when (target["persist"] as? String) {
                    "kept" -> {
                        val content = if (target["content"] == "textSoFar") c.text else ""
                        rows[g] = ReplyKitRow(g, target["statusRaw"] as? String, content, c.sources, c.sbc)
                        if (c.supersedes.isNotEmpty() && (to == "complete" || to == "cutOff")) for (old in c.supersedes) rows.remove(old)
                    }
                    "deleted" -> rows.remove(g)
                    else -> {}
                }
                live = null
            }
            else -> fail("undeclared op $op")
        }
    }
}

/** A failed precondition while reading the kit (Swift's `try #require`). */
internal fun kitFail(message: String): Nothing = throw AssertionError(message)
