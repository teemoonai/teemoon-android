// android-only: the background-turn notification's rules.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnNotificationTest {

    /** The service is up for the whole turn, not from the moment the user leaves — priority cannot be raised afterwards. */
    @Test fun theServiceRunsForTheWholeTurn() {
        assertTrue(TurnNotificationPolicy.serviceRuns(turnInFlight = true))
        assertFalse(TurnNotificationPolicy.serviceRuns(turnInFlight = false))
    }

    /**
     * A turn in flight says leaving is safe, and not where it went: it shows at every send, so the provider's name would
     * reach the lock screen and listener apps on every turn (the local audit, 2026-10-13 — metadata, but still the chat's).
     */
    @Test fun aTurnInFlightSaysLeavingIsSafeAndNamesNoProvider() {
        val n = TurnNotificationPolicy.notification(true, foreground = false, outcome = null)
        assertEquals(TurnNotification.Working, n)
        assertTrue("it should say leaving is safe — that is the whole feature", TurnNotification.Working.body.contains("leave the app"))
        for (provider in listOf("near.ai", "grok", "fireworks", "openrouter", "nvidia", "brave", "your provider"))
            assertFalse(TurnNotification.Working.body.contains(provider))
    }

    /** Finishing while they are watching is not news. */
    @Test fun aTurnThatFinishesInTheForegroundSaysNothing() {
        assertEquals(TurnNotification.None,
            TurnNotificationPolicy.notification(false, foreground = true, outcome = TurnOutcome.SUCCEEDED))
    }

    /**
     * NOTHING OF THE CHAT LEAVES THE APP (2026-10-13, reversing 2026-09-21). The notification says only that an
     * answer is ready: no reply, no thread title (the title is the user's first message). Apps with notification
     * access, notification history and paired devices read whatever a notification carries; this carries nothing.
     */
    @Test fun theNotificationCarriesNothingOfTheChat() {
        val reply = "You're trusting three things, and it's worth separating them."
        val n = TurnNotificationPolicy.notification(false, foreground = false, outcome = TurnOutcome.SUCCEEDED)
        assertEquals(TurnNotification.Ready, n)
        assertEquals("your answer is ready", TurnNotification.Ready.title)
        val f = TurnNotificationPolicy.notification(false, foreground = false, outcome = TurnOutcome.FAILED)
        for (text in listOf(TurnNotification.Ready.title, TurnNotification.Failed.title, TurnNotification.Failed.body, f.toString()))
            for (leak in listOf("what attestation proves", reply)) assertFalse("$leak in \"$text\"", text.contains(leak))
    }

    /**
     * A FAILURE CARRIES NO PROVIDER TEXT, and that is separate from the content decision. Error bodies echo the
     * request back — near.ai's and Fireworks' both quote the offending field, which can be the prompt. The class
     * of failure is what the user can act on anyway.
     */
    @Test fun aFailureSaysTheClassNotTheCause() {
        val n = TurnNotificationPolicy.notification(false, foreground = false, outcome = TurnOutcome.FAILED)
        assertEquals(TurnNotification.Failed, n)
        assertEquals("that turn didn't finish", TurnNotification.Failed.title)
        assertEquals("open to see why", TurnNotification.Failed.body)
    }

    /**
     * A turn the USER stopped notifies about nothing. They asked for it to end; an app that announces having
     * obeyed is an app whose notifications get turned off, and then the one that matters never arrives.
     */
    @Test fun aTurnTheUserStoppedIsSilent() {
        assertEquals(TurnNotification.None,
            TurnNotificationPolicy.notification(false, foreground = false, outcome = TurnOutcome.STOPPED))
    }

    /** teemoon's own chrome is lowercase; the model's answer is the model's own. */
    @Test fun theAppsOwnCopyIsLowercase() {
        for (s in listOf(TurnNotification.Working.title, TurnNotification.Working.body,
                         TurnNotification.Ready.title, TurnNotification.Failed.title, TurnNotification.Failed.body))
            assertEquals(s.lowercase(), s)
    }

    /**
     * 2026-10-13: a finished answer arrived silent with no status-bar icon, because both notifications shared one
     * LOW channel, which Android files under Silent. The ongoing one gets an icon and no noise, the result gets the
     * default sound, and both ids are new because Android never raises an installed channel's importance.
     */
    @Test fun theOngoingIsVisibleAndSilentAndTheResultIsHeard() {
        val working = TurnChannels.WORKING
        val answers = TurnChannels.ANSWERS
        assertTrue("the ongoing notification needs a status-bar icon", working.visibleInStatusBar)
        assertFalse("the ongoing notification starts at every send; a sound there dings every turn", working.audible)
        assertTrue(answers.visibleInStatusBar)
        assertTrue("the answer arrives when the user is elsewhere; it must be heard", answers.audible)
        assertTrue(working.id != answers.id)
        for (c in TurnChannels.ALL) assertFalse("an installed channel keeps its old importance; the id must be new", c.id in TurnChannels.RETIRED)
        assertEquals(listOf("turns", "answers"), TurnChannels.RETIRED)
        assertFalse("the ongoing one must not drop a banner over whatever the user went to do", working.headsUp)
        assertTrue("the result is the news: a chime with nothing on screen was missed on 2026-10-13", answers.headsUp)
        for (c in TurnChannels.ALL) { assertEquals(c.name.lowercase(), c.name); assertEquals(c.description.lowercase(), c.description) }
    }
}
