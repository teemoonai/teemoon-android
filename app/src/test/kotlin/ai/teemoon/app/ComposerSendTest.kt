package ai.teemoon.app

import ai.teemoon.app.imaging.ComposerImagesMachine
import ai.teemoon.app.imaging.ComposerImagesMachine.Event
import ai.teemoon.app.imaging.ComposerImagesMachine.State
import ai.teemoon.app.imaging.ComposerSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maintainer's Pixel, 2026-11-12: a send with images on a model that can't see them cleared the composer and then
 * refused in the view model — the images and the text were lost behind the alert, and "choose an image-capable model"
 * opened nothing (the machine was already empty). The composer fires send/blind first: the images stay, the alert shows,
 * and choose opens the where sheet with them still attached (composerimages.json: attached + send/blind → attached).
 */
class ComposerSendTest {
    @Test fun aSendWithImagesOnAModelThatCantSeeThemIsSendBlindAndKeepsThem() {
        assertEquals(Event.SEND_BLIND, ComposerSend.event(hasImages = true, cantSee = true))
        assertEquals(Event.SEND_SEES, ComposerSend.event(hasImages = true, cantSee = false))
        assertNull("no images: an ordinary send", ComposerSend.event(hasImages = false, cantSee = true))
        // and the machine then holds them, and choose opens the where sheet
        val blind = ComposerImagesMachine.next(State.ATTACHED, Event.SEND_BLIND)
        assertEquals(State.ATTACHED, blind.to)
        assertTrue(ComposerImagesMachine.Effect.OPEN_WHERE in ComposerImagesMachine.next(blind.to, Event.ALERT_CHOOSE_MODEL).effects)
    }
}
