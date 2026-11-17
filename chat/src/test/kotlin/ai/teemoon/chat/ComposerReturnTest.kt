// ports: teemoonTests/ConfirmPendingSendAnywayTests.swift @ fe7194f0 (composerReturnFollowsTheKit only; the rest of
// that suite drives ChatViewModel and moves with the view model's wiring)
package ai.teemoon.chat

import ai.teemoon.chat.ReplyMachineVectorsTest.Companion.kit
import ai.teemoon.chat.ReplyMachineVectorsTest.Companion.obj
import ai.teemoon.chat.ReplyMachineVectorsTest.Companion.objs
import ai.teemoon.chat.ReplyMachineVectorsTest.Companion.strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerReturnTest {

    /** A letter stands for distinct image bytes (iOS: ComposerImageTests.kept(<the letter's scalar>)). */
    private fun images(any: Any?): List<ByteArray> = strings(any).map { byteArrayOf(it.codePointAt(0).toByte()) }

    /** reply-machine.json composerReturn: every vector, letters standing for distinct image bytes. */
    @Test fun composerReturnFollowsTheKit() {
        val rule = obj(kit["composerReturn"]) ?: kitFail("no composerReturn")
        val vectors = objs(rule["vectors"])
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val name = vector["name"] as? String ?: "?"
            val q = obj(vector["question"]) ?: kitFail("$name: no question")
            val d = obj(vector["draft"]) ?: kitFail("$name: no draft")
            val want = obj(vector["expect"]) ?: kitFail("$name: no expect")
            val got = ComposerReturn.afterReturn(
                questionText = q["text"] as? String ?: "", questionImages = images(q["images"]),
                draftText = d["text"] as? String ?: "", draftImages = images(d["images"]),
                limit = vector["limit"] as? Int ?: 0, bytes = { it },
            )
            assertEquals(name, want["text"] as? String, got.text)
            assertEquals("$name: images", images(want["images"]).map { it.toList() }, got.images.map { it.toList() })
            assertEquals("$name: overLimit", want["overLimit"] as? Boolean ?: false, got.overLimit)
            assertEquals("$name: over the limit disables send", want["sendDisabled"] as? Boolean ?: false, got.overLimit)
        }
    }
}
