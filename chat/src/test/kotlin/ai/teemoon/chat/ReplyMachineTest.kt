// ports: teemoonTests/ReplyMachineTests.swift @ f6d44c1b
//
// Every vector in conformance/reply-machine.json, replayed through ReplyMachine. The machine returns effects; a
// map stands in for the store and applies them, so what is compared is what the app would persist.
// +1 Android-only: MessageStatus (the Room column's words) and ReplyStatus are one vocabulary.
package ai.teemoon.chat

import ai.teemoon.chat.ReplyMachineVectorsTest.Companion.kit
import ai.teemoon.chat.ReplyMachineVectorsTest.Companion.obj
import ai.teemoon.chat.ReplyMachineVectorsTest.Companion.objs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReplyMachineTest {

    private fun gen(any: Any?): ReplyGeneration? = (any as? Int)?.let(::ReplyGeneration)
    private fun gens(any: Any?): List<ReplyGeneration> = ((any as? List<*>)?.filterIsInstance<Int>() ?: emptyList()).map(::ReplyGeneration)

    /** The kit's event object as a ReplyEvent. [next] stands in for a send's id when the vector gives none (a refused send). */
    private fun event(o: Map<String, Any?>, next: ReplyGeneration, restorable: Boolean = false): ReplyEvent {
        val kind = o["event"] as? String ?: kitFail("event without a kind")
        val g = gen(o["generation"]) ?: next
        val place = ReplyPlace.fromRaw(o["place"] as? String ?: "remote") ?: ReplyPlace.REMOTE
        return when (kind) {
            "send" -> ReplyEvent.Send(g, place, images = o["images"] as? Int ?: 0)
            "draftEdited" -> ReplyEvent.DraftEdited
            "retry" -> ReplyEvent.Retry(g, place, gens(o["replaces"]), restorable)
            "thinkHarder" -> ReplyEvent.ThinkHarder(g, place, gens(o["supersedes"]))
            "reverifyRetry" -> ReplyEvent.ReverifyRetry(g, place, gens(o["replaces"]), gen(o["captured"]) ?: kitFail("no captured"), restorable)
            "autoResume" -> ReplyEvent.AutoResume(g, place, gens(o["replaces"]), restorable)
            "gatePassed" -> ReplyEvent.GatePassed(g, o["sealed"] as? Boolean ?: false, o["sentBeforeConfirmed"] as? String)
            "gateNeedsConfirm" -> ReplyEvent.GateNeedsConfirm(g)
            "gateRefused" -> ReplyEvent.GateRefused(g)
            "gateWouldGoPlain" -> ReplyEvent.GateWouldGoPlain(g)
            "confirm" -> ReplyEvent.Confirm(g, o["sentBeforeConfirmed"] as? String)
            "decline" -> ReplyEvent.Decline(g)
            "firstToken" -> ReplyEvent.FirstToken(g, o["text"] as? String ?: "")
            "token" -> ReplyEvent.Token(g, o["text"] as? String ?: "")
            "toolRound" -> ReplyEvent.ToolRound(g)
            "toolResult" -> ReplyEvent.ToolResult(g, o["sources"] as? Int ?: 0)
            "toolRoundEnded" -> ReplyEvent.ToolRoundEnded(g)
            "finish" -> ReplyEvent.Finish(g, FinishReason.fromRaw(o["reason"] as? String ?: "") ?: kitFail("unknown reason ${o["reason"]}"))
            "streamEndedWithoutDone" -> ReplyEvent.StreamEndedWithoutDone(g)
            "decryptFailed" -> ReplyEvent.DecryptFailed(g, o["field"] as? String ?: "")
            "error" -> ReplyEvent.Error(g, o["kind"] as? String ?: "")
            "engineAbandoned" -> ReplyEvent.EngineAbandoned(g, o["reason"] as? String ?: "")
            "engineLateCallback" -> ReplyEvent.EngineLateCallback(g, o["kind"] as? String ?: "")
            "userStop" -> ReplyEvent.UserStop
            "appBackgrounded" -> ReplyEvent.AppBackgrounded
            "backgroundTimeExpired" -> ReplyEvent.BackgroundTimeExpired
            "appKilled" -> ReplyEvent.AppKilled
            "launchRecovery" -> ReplyEvent.LaunchRecovery
            "deleteChat" -> ReplyEvent.DeleteChat
            else -> kitFail("unknown event $kind")
        }
    }

    private data class StoredRow(val status: String?, val content: String, val sources: Int, val sentBeforeConfirmed: String?)

    private fun stored(row: Map<String, Any?>) = StoredRow(
        status = row["statusRaw"] as? String, content = row["content"] as? String ?: "",
        sources = row["sources"] as? Int ?: 0, sentBeforeConfirmed = row["sentBeforeConfirmed"] as? String,
    )

    @Test fun everyVectorReplays() {
        val vectors = ReplyMachineVectorsTest.vectors(kit)
        assertTrue(vectors.size >= 25)
        for (vector in vectors) {
            val name = vector["name"] as? String ?: "?"
            val store = HashMap<ReplyGeneration, StoredRow>()
            for (row in objs(vector["given"])) store[ReplyGeneration(row["generation"] as? Int ?: 0)] = stored(row)
            val machine = ReplyMachine(highest = store.keys.maxOrNull() ?: ReplyGeneration(0))
            val ignored = ArrayList<Int>()
            var shown: ReplyMarker? = null
            val requests = ArrayList<List<Int>>(); var userTurns = 0
            var draft: String? = null
            val returned = ArrayList<Int>()
            val checkpoints = objs(vector["checkpoints"])
            for ((index, raw) in objs(vector["events"]).withIndex()) {
                if (raw["event"] == "draftEdited") draft = "kept"
                // The kit's `restorable`, read from the stand-in store as the app reads it from Room.
                val restorable = gens(raw["replaces"]).any { g ->
                    val row = store[g] ?: return@any false
                    row.content.isNotBlank() && (row.status ?: "") !in listOf("interrupted", "failed", "decryptFailed")
                }
                val effects = machine.handle(event(raw, next = ReplyGeneration(machine.highest.value + 1), restorable))
                for (effect in effects) {
                    when (effect) {
                        is ReplyEffect.IgnoreStale -> ignored.add(index)
                        is ReplyEffect.PersistRow -> store[effect.generation] = StoredRow(
                            effect.row.status.rawValue, effect.row.content, effect.row.sources, effect.row.sentBeforeConfirmed)
                        is ReplyEffect.PersistSentBeforeConfirmed ->
                            store[effect.generation]?.let { store[effect.generation] = it.copy(sentBeforeConfirmed = effect.value) }
                        is ReplyEffect.DeleteRows -> for (g in effect.generations) store.remove(g)
                        is ReplyEffect.ShowMarker -> shown = effect.marker
                        is ReplyEffect.PersistQuestion -> {
                            userTurns += 1
                            if (draft == "kept") draft = "consumed"
                        }
                        is ReplyEffect.SendRequest -> requests.add(listOf(effect.generation.value, effect.images))
                        is ReplyEffect.ReturnQuestionToComposer -> {
                            userTurns -= 1
                            returned.add(effect.generation.value)
                        }
                        else -> {}
                    }
                }
                for (point in checkpoints) if (point["after"] == index) {
                    assertEquals("$name after $index", point["state"], machine.state.rawValue)
                    assertEquals("$name after $index: live", gen(point["live"]), machine.live)
                    (point["resumeArmed"] as? Boolean)?.let { assertEquals("$name after $index", it, machine.resumeArmed) }
                }
            }
            val expect = obj(vector["expect"]) ?: kitFail("$name: no expect")
            assertEquals("$name: state", expect["state"], machine.state.rawValue)
            assertEquals("$name: live", gen(expect["live"]), machine.live)
            assertEquals("$name: ignored", (expect["ignored"] as? List<*>) ?: emptyList<Int>(), ignored)
            assertEquals("$name: resumeArmed", (expect["resumeArmed"] as? Boolean) ?: false, machine.resumeArmed)
            val want = HashMap<ReplyGeneration, StoredRow>()
            for (row in objs(expect["rows"])) want[ReplyGeneration(row["generation"] as? Int ?: -1)] = stored(row)
            assertEquals("$name: persisted rows", want, store)
            for ((g, marker) in obj(expect["markers"]) ?: emptyMap()) {
                val status = store[ReplyGeneration(g.toIntOrNull() ?: -1)]?.status?.let(ReplyStatus::fromRaw)
                assertEquals("$name: reload marker of $g", marker, ReplyStatus.reloadMarker(status)?.rawValue)
            }
            (expect["requests"] as? List<*>)?.let { want ->
                assertEquals("$name: requests", objs(want).map { listOf(it["generation"] as? Int ?: -1, it["images"] as? Int ?: -1) }, requests)
            }
            (expect["userTurns"] as? Int)?.let { assertEquals("$name: user turns", it, userTurns) }
            (expect["draft"] as? String)?.let { assertEquals("$name: draft", it, draft) }
            (expect["returned"] as? List<*>)?.let { assertEquals("$name: returned", it, returned) }
            (expect["sessionMarker"] as? String)?.let { marker ->
                assertEquals("$name: session marker", marker, shown?.rawValue)
                assertEquals("$name: state marker", marker, machine.state.marker?.rawValue)
            }
        }
    }

    /** The type's vocabulary is the kit's: same states, statusRaw values, markers, terminal set. */
    @Test fun vocabularyMatchesTheKit() {
        val states = ReplyMachineVectorsTest.states(kit)
        assertEquals(states.keys, ReplyState.entries.map { it.rawValue }.toSet())
        assertEquals(ReplyMachineVectorsTest.statusValues(kit), ReplyStatus.entries.map { it.rawValue }.toSet())
        assertEquals(obj(kit["markers"])?.keys ?: emptySet<String>(), ReplyMarker.entries.map { it.rawValue }.toSet())
        for (state in ReplyState.entries) {
            val declared = states[state.rawValue] ?: kitFail("$state is not in the kit")
            assertEquals("$state", declared["kind"] == "terminal", state.isTerminal)
            assertEquals("$state", declared["kind"] == "live", state.isLive)
            assertEquals("$state: statusRaw", declared["statusRaw"] as? String, state.status?.rawValue)
            assertEquals("$state: marker", declared["marker"] as? String, state.marker?.rawValue)
        }
        val reload = ReplyMachineVectorsTest.reloadMarkers(kit)
        assertEquals(reload["nil"] as? String, ReplyStatus.reloadMarker(null)?.rawValue)
        for (status in ReplyStatus.entries) {
            assertEquals("$status", reload[status.rawValue] as? String, ReplyStatus.reloadMarker(status)?.rawValue)
        }
    }

    /**
     * Android-only: messages.status is written from ReplyStatus.rawValue and read back with MessageStatus's words.
     * They are one vocabulary; a status added to one and not the other would read back as a row the machine never
     * wrote (and its marker would be lost).
     */
    @Test fun messageStatusIsReplyStatusesVocabulary() {
        assertEquals(ReplyStatus.COMPLETE.rawValue, MessageStatus.COMPLETE)
        assertEquals(ReplyStatus.CUT_OFF.rawValue, MessageStatus.CUT_OFF)
        assertEquals(ReplyStatus.INTERRUPTED.rawValue, MessageStatus.INTERRUPTED)
        assertEquals(ReplyStatus.STOPPED.rawValue, MessageStatus.STOPPED)
        assertEquals(ReplyStatus.FAILED.rawValue, MessageStatus.FAILED)
        assertEquals(ReplyStatus.DECRYPT_FAILED.rawValue, MessageStatus.DECRYPT_FAILED)
        assertEquals(ReplyStatus.entries.map { it.rawValue }.toSet(), MessageStatus.all)
    }

    @Test fun engineCacheVectorsReplay() {
        val cache = obj(kit["engineCache"]) ?: kitFail("no engineCache")
        for (vector in objs(cache["vectors"])) {
            val epochs = EngineCacheEpochs()
            for (event in objs(vector["events"])) {
                when (event["event"] as? String) {
                    "build" -> epochs.build()
                    "evict" -> epochs.evict()
                    "initialized" -> epochs.initialized(epoch = event["epoch"] as? Int ?: -1)
                    else -> fail("unknown engine cache event")
                }
            }
            val expect = obj(vector["expect"]) ?: kitFail("no expect")
            assertEquals("${vector["name"] ?: "?"}", expect["state"], epochs.state.rawValue)
            assertEquals(expect["resident"], epochs.residentCount)
            assertEquals(expect["epoch"], epochs.epoch)
        }
    }

    @Test fun aStaleInitializeIsReleasedNotInserted() {
        val epochs = EngineCacheEpochs()
        assertEquals(EngineCacheEpochs.Effect.StartBuild(epoch = 0), epochs.build())
        assertEquals(EngineCacheEpochs.Effect.Bumped(epoch = 1), epochs.evict())
        assertEquals(EngineCacheEpochs.Effect.ReleaseStale, epochs.initialized(epoch = 0))
        assertEquals(0, epochs.residentCount)
    }
}
