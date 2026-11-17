// The composer's gated send (ChatScreen's "send anyway" / sealed-unconfirmed rows; iOS ChatView.generateBypassing).
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GatedSendTest {
    /**
     * "send anyway" sends the message the question was asked about. It read the composer at the tap, so a draft typed
     * while the question was up went instead (pm ios-state-machines, Tier 3) — and the picked images never went at all.
     */
    @Test fun sendAnywaySendsTheGatedMessageNotTheDraftTypedSince() {
        val gated = GatedSend("is this sealed?", listOf("photo"))
        assertEquals("is this sealed?", gated.prompt)
        assertEquals(listOf("photo"), gated.images)
        assertEquals("a draft typed since stays in the composer", "and another thing", gated.promptAfter("and another thing"))
        assertEquals(listOf("other"), gated.imagesAfter(listOf("other")))
    }

    /** The common case: nothing changed while the question was up, so the composer empties as a plain send's does. */
    @Test fun anUnchangedComposerEmptiesAfterTheSend() {
        val gated = GatedSend("hello", listOf("photo"))
        assertEquals("", gated.promptAfter("hello"))
        assertEquals(emptyList<String>(), gated.imagesAfter(listOf("photo")))
    }

    /**
     * The re-shown alert after a Re-verify declined a fresh send (iOS generateBypassing → performGenerate): the question
     * went back to the composer and no composer send was gated, so "send anyway" sends the composer. It sent nothing — or
     * an older message the user had declined, left in the gate.
     */
    @Test fun withNoGatedMessageSendAnywaySendsTheComposer() {
        assertEquals(GatedSend("hello", listOf("photo")), GatedSend.orComposer(null, "hello", listOf("photo")))
        val gated = GatedSend("is this sealed?", emptyList<String>())
        assertEquals(gated, GatedSend.orComposer(gated, "hello", emptyList()))
        assertEquals(null, GatedSend.orComposer<String>(null, "", emptyList()))
    }
}
