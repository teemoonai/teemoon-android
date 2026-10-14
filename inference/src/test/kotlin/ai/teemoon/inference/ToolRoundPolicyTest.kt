package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundPolicyTest {

    @Test fun defaultBudgetMatchesHostedIos() {
        assertEquals(4, ToolRoundPolicy.DEFAULT_MAX_ROUNDS)
    }

    @Test fun spendsExactlyItsBudget() {
        val p = ToolRoundPolicy(maxRounds = 2)
        assertTrue(p.consume()); assertTrue(p.consume())
        assertFalse("third round must be refused", p.consume())
        assertEquals(2, p.roundsUsed)
        assertTrue(p.exhausted)
    }

    /** A model that emits the same call forever must terminate, not spin. */
    @Test fun terminatesARepeatingModel() {
        val p = ToolRoundPolicy(maxRounds = 3)
        var loops = 0
        while (p.consume() && loops < 100) loops += 1
        assertEquals(3, loops)
    }

    /**
     * The notice is a measured artifact. Asserting its exact text is the point:
     * paraphrasing it is the drift §13.2 says no fixture can catch.
     */
    @Test fun noticeIsVerbatimAndPolicyShaped() {
        val n = ToolRoundPolicy.SEARCHES_EXHAUSTED_NOTICE
        assertTrue(n.startsWith("SEARCH POLICY:"))
        assertTrue(n.contains("This is a setting, not a failure."))
        assertTrue(n.contains("Answer the question now from the results already gathered above."))
        assertTrue(n.contains("say what you found and what is still uncertain"))
        assertFalse("must not be failure-shaped", n.contains("exhausted after"))
    }

    @Test fun injectsOnceAndNotAfterAUserTurn() {
        val p = ToolRoundPolicy(maxRounds = 2)
        p.consume()
        assertTrue(p.shouldTellModelSearchesAreOver(includeTools = false, lastRole = "tool", alreadyTold = false))
        // two user messages in a row is a shape some servers reject
        assertFalse(p.shouldTellModelSearchesAreOver(includeTools = false, lastRole = "user", alreadyTold = false))
        assertFalse(p.shouldTellModelSearchesAreOver(includeTools = false, lastRole = "tool", alreadyTold = true))
        // still offering tools → nothing to say yet
        assertFalse(p.shouldTellModelSearchesAreOver(includeTools = true, lastRole = "tool", alreadyTold = false))
    }

    @Test fun saysNothingBeforeAnyRoundRan() {
        val p = ToolRoundPolicy()
        assertFalse(p.shouldTellModelSearchesAreOver(includeTools = false, lastRole = "tool", alreadyTold = false))
    }
}
