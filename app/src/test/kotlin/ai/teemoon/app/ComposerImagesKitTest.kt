// spec: teemoon-ios docs/IMAGES.md §4d @ 638973e2 — the composer's images over the kit's `composerimages` class (0.7.0):
// every state × event cell iOS generated must come out of ComposerImagesMachine, its next state and its effects in order.
// A cell the machine can't name is a failure, as is a cell the kit lost.
package ai.teemoon.app

import ai.teemoon.app.imaging.ComposerImagesMachine
import ai.teemoon.app.imaging.ComposerImagesMachine.Effect
import ai.teemoon.app.imaging.ComposerImagesMachine.Event
import ai.teemoon.app.imaging.ComposerImagesMachine.State
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerImagesKitTest {
    private val kit by lazy { Json.parseToJsonElement(ComposerImagesKitTest::class.java.getResourceAsStream("/composerimages.json")!!.readBytes().decodeToString()).jsonObject }

    @Test fun everyCellIsWhatIosGenerated() {
        val cells = kit["cells"]!!.jsonObject
        // 0.7.0: 7 states × 15 events — a kit that lost cells, or a machine that lost a state or event, fails here
        assertEquals(State.entries.size * Event.entries.size, cells.size)
        assertEquals(State.entries.map { it.key }.toSet(), kit["states"]!!.jsonObject.keys)
        assertEquals(Event.entries.map { it.key }.toSet(), kit["events"]!!.jsonObject.keys)
        assertEquals(Effect.entries.map { it.key }.toSet(), kit["effects"]!!.jsonObject.keys)
        for ((name, cell) in cells) {
            val (s, e) = name.split(" + ")
            val state = State.entries.single { it.key == s }; val event = Event.entries.single { it.key == e }
            val c = cell.jsonObject
            val want = ComposerImagesMachine.Step(State.entries.single { it.key == c["to"]!!.jsonPrimitive.content },
                c["effects"]!!.jsonArray.map { x -> Effect.entries.single { it.key == x.jsonPrimitive.content } })
            assertEquals(name, want, ComposerImagesMachine.next(state, event))
        }
    }

    /** Invariant 1 (the maintainer's Pixel, 2026-11-11: images vanished on a switch): a model switch never removes an image. */
    @Test fun aModelSwitchNeverRemovesAnImage() {
        for (s in State.entries) {
            val step = ComposerImagesMachine.next(s, Event.MODEL_SWITCHED)
            assertTrue("$s", Effect.REMOVE !in step.effects && Effect.REMOVE_ALL !in step.effects)
            assertEquals("$s keeps its images", s.key.startsWith("attached"), step.to.key.startsWith("attached"))
        }
    }
}
