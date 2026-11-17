// spec: IMAGES.md draft 3 §4 (teemoon-ios 67b39a4): the newest 10 images / 8 MiB per request; older ones are a fixed line
// for that request only; a model that cannot see images gets the line for every image and a count of what was left out.
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageCaps
import ai.teemoon.app.imaging.ImageHistory
import ai.teemoon.inference.WireImage
import ai.teemoon.inference.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHistoryTest {
    private fun img(size: Int, tag: Int = 0) = WireImage("image/jpeg", ByteArray(size) { tag.toByte() })
    private fun user(text: String, vararg images: WireImage) = WireMessage("user", text, images.toList())

    @Test fun theNewestImagesTravelAndOlderOnesBecomeTheLine() {
        // 2 + 8 history images and 1 in the prompt: one over the ten.
        val history = listOf(user("old", img(10, 1), img(10, 2)), WireMessage("assistant", "ok"), user("newer", *(3..10).map { img(10, it) }.toTypedArray()))
        val r = ImageHistory.apply(history, listOf(img(10, 11)), vision = true)
        assertEquals(1, r.promptImages.size)
        assertEquals((3..10).toList(), r.history[2].images.map { it.bytes[0].toInt() })
        // Of the oldest message's two images only the later one fits the ten.
        assertEquals(listOf(2), r.history[0].images.map { it.bytes[0].toInt() })
        assertEquals("old\n\n[image earlier in this chat]", r.history[0].content)
        assertEquals(1, r.leftOut)
    }

    @Test fun theEightMebibyteBudgetCountsBytesNotJustImages() {
        val five = 5 * 1024 * 1024
        val r = ImageHistory.apply(listOf(user("a", img(five)), user("b", img(five))), emptyList(), vision = true)
        assertEquals(1, r.history[1].images.size); assertEquals(0, r.history[0].images.size)
        assertEquals("a\n\n[image earlier in this chat]", r.history[0].content); assertEquals(1, r.leftOut)
    }

    @Test fun aModelThatCannotSeeImagesGetsTheLineForEveryOne() {
        val r = ImageHistory.apply(listOf(user("", img(5), img(5)), WireMessage("assistant", "seen")), listOf(img(5)), vision = false)
        assertTrue(r.history.all { it.images.isEmpty() } && r.promptImages.isEmpty())
        assertEquals("[image earlier in this chat]\n\n[image earlier in this chat]", r.history[0].content)
        assertEquals(3, r.leftOut)
    }

    /** The line is fixed text: the same history gives the same messages on every send (the prompt cache's prefix). */
    @Test fun theSameHistoryGivesTheSameMessagesEverySend() {
        val h = (0 until 6).map { user("turn $it", img(10, it)) }
        assertEquals(ImageHistory.apply(h, emptyList(), true).history.map { it.content }, ImageHistory.apply(h, emptyList(), true).history.map { it.content })
    }

    /**
     * IMAGES.md §4b (the maintainer 2026-11-12: up to 10 on the phone): the on-device model gets every one of the question's
     * images, each prepared, in order (it got the first alone once — on the Pixel 10 Pro a 10-image question prefilled 797
     * tokens, one image's worth); and since 00f72739 the earlier ones too, within the phone's number — prepared as well.
     */
    @Test fun theOnDeviceModelGetsEveryImageOfTheQuestionAndEarlierOnesWithinItsNumber() {
        val r = ImageHistory.applyOnDevice(listOf(user("before", img(10, 1))), listOf(img(10, 2), img(10, 3))) { it.copyOf(3) }
        assertEquals(listOf(2, 3), r.promptImages.map { it.bytes[0].toInt() })
        assertTrue(r.promptImages.all { it.bytes.size == 3 })
        assertEquals("the earlier image travels, prepared", listOf(3), r.history[0].images.map { it.bytes.size })
        assertEquals("before", r.history[0].content)
        assertEquals(0, r.leftOut)
        val eleven = ImageHistory.applyOnDevice(emptyList(), (1..11).map { img(10, it) }) { it }
        assertEquals("the phone's cap", ImageCaps.ON_DEVICE_PER_SEND, eleven.promptImages.size)
        assertEquals(1, eleven.leftOut)
    }

    /**
     * The maintainer 2026-11-12 ("Yes", from his Pixel: a photo, another photo, then a question about both — E2B said it
     * didn't have them): a phone model gets the cloud's history rule — earlier images resent, newest first, up to the
     * phone's 10 per request with the question's own; older ones the line (IMAGES.md §4b @ 00f72739).
     */
    @Test fun aPhoneModelsThirdTurnCarriesBothEarlierPhotos() {
        val history = listOf(user("what is this?", img(10, 1)), WireMessage("assistant", "a red circle"),
            user("and this?", img(10, 2)), WireMessage("assistant", "a blue square"))
        val r = ImageHistory.applyOnDevice(history, emptyList()) { it }
        assertEquals("the first photo travels again", listOf(1), r.history[0].images.map { it.bytes[0].toInt() })
        assertEquals("the second photo travels again", listOf(2), r.history[2].images.map { it.bytes[0].toInt() })
        assertEquals("what is this?", r.history[0].content)
        assertEquals(0, r.leftOut)
    }

    /**
     * wherefilter.json phoneImages.history (kit bfc27875): each case's turns, the last one the request — how many images
     * it sends, how many become the line, and whether the kept conversation may serve it (any image: never).
     */
    @Test fun theHistoryCasesAreTheKits() {
        val kit = kotlinx.serialization.json.Json.parseToJsonElement(ImageHistoryTest::class.java.getResourceAsStream("/wherefilter.json")!!.readBytes().decodeToString())
        fun kotlinx.serialization.json.JsonElement.o() = this as kotlinx.serialization.json.JsonObject
        fun kotlinx.serialization.json.JsonElement.i() = (this as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        val cases = (kit.o()["phoneImages"]!!.o()["history"]!!.o()["cases"] as kotlinx.serialization.json.JsonArray).map { it.o() }
        assertEquals(3, cases.size)
        var tag = 0
        for (c in cases) {
            val turns = (c["turns"] as kotlinx.serialization.json.JsonArray).map { it.o()["images"]!!.i() }
            val history = turns.dropLast(1).flatMap { n -> listOf(user("q", *Array(n) { img(10, ++tag) }), WireMessage("assistant", "a")) }
            val prompt = List(turns.last()) { img(10, ++tag) }
            val r = ImageHistory.applyOnDevice(history, prompt, maxImages = c["maxImages"]!!.i()) { it }
            val sent = r.promptImages.size + r.history.sumOf { it.images.size }
            val lines = r.history.sumOf { m -> m.content.split(ImageHistory.EARLIER).size - 1 }
            val name = (c["name"] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals(name, c["expectSentOnLastTurn"]!!.i(), sent)
            assertEquals(name, c["expectLines"]!!.i(), lines)
            assertEquals(name, (c["expectReuse"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean(), ai.teemoon.inference.ConversationReuse.reusable(requestImages = sent))
        }
    }
}
