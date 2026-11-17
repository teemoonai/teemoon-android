// pm sweep 2026-11-06 (settings and sheets): the web-search offer's key check had no run token. onKeyAccepted read the
// key field 1.7 s after key A's VALID — by then key B, pasted meanwhile, which Brave never accepted, was saved and
// enabled. A second VALID handed a key over twice. Each check now answers and hands over only its own key, once.
package ai.teemoon.app

import ai.teemoon.inference.BraveWebSearchTool.KeyCheck
import ai.teemoon.inference.BraveWebSearchTool.KeyCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyOfferRunTest {
    @Test fun aKeyPastedWhileTheLastOneWasAcceptedIsNotTheOneSaved() = runTest {
        val answers = mapOf("A" to CompletableDeferred<KeyCheckResult>(), "B" to CompletableDeferred())
        val saved = ArrayList<String>()
        val run = KeyOfferRun(verify = { answers.getValue(it).await() }, onAccepted = { saved += it })
        launch { run.check("A") }; testScheduler.runCurrent()
        answers.getValue("A").complete(KeyCheckResult(KeyCheck.VALID)); testScheduler.runCurrent()   // A accepted, in the pause
        launch { run.check("B") }; testScheduler.runCurrent()                                      // B pasted meanwhile
        answers.getValue("B").complete(KeyCheckResult(KeyCheck.REJECTED, 401)); advanceUntilIdle()
        assertEquals("B was rejected and A was superseded: nothing is saved", emptyList<String>(), saved)
        assertEquals(KeyCheck.REJECTED, run.result?.kind); assertFalse(run.accepted)
    }

    @Test fun aLateAnswerForAnEarlierKeyIsIgnored() = runTest {
        val answers = mapOf("A" to CompletableDeferred<KeyCheckResult>(), "B" to CompletableDeferred())
        val saved = ArrayList<String>()
        val run = KeyOfferRun(verify = { answers.getValue(it).await() }, onAccepted = { saved += it })
        launch { run.check("A") }; launch { run.check("B") }; testScheduler.runCurrent()
        answers.getValue("B").complete(KeyCheckResult(KeyCheck.VALID))
        answers.getValue("A").complete(KeyCheckResult(KeyCheck.REJECTED, 401)); advanceUntilIdle()
        assertEquals(listOf("B"), saved)
        assertEquals(KeyCheck.VALID, run.result?.kind)
    }

    @Test fun anAcceptedKeyIsHandedOverOnce() = runTest {
        val saved = ArrayList<String>()
        val run = KeyOfferRun(verify = { KeyCheckResult(KeyCheck.VALID) }, onAccepted = { saved += it })
        launch { run.check("A") }; testScheduler.runCurrent(); launch { run.check("A") }; advanceUntilIdle()
        assertEquals(listOf("A"), saved)
    }
}

/**
 * pm sweep 2026-11-06: search settings kept the last check's result while the key was edited or emptied, and a VALID
 * that arrived after an edit turned search on for a key Brave had not accepted.
 */
class SearchKeyCheckTest {
    @Test fun aLateValidForAnEditedKeyEnablesNothing() = runTest {
        val answer = CompletableDeferred<KeyCheckResult>()
        var enabled = 0
        val c = SearchKeyCheck(verify = { answer.await() }, onValid = { enabled++ })
        launch { c.commit("A") }; testScheduler.runCurrent()
        c.edited()                                   // the user changes the key while A is being checked
        answer.complete(KeyCheckResult(KeyCheck.VALID)); advanceUntilIdle()
        assertEquals(0, enabled); assertEquals(null, c.result); assertFalse(c.checking)
    }

    @Test fun editingOrEmptyingClearsTheLastResult() = runTest {
        val c = SearchKeyCheck(verify = { KeyCheckResult(KeyCheck.REJECTED, 401) }, onValid = {})
        c.commit("A"); assertEquals(KeyCheck.REJECTED, c.result?.kind)
        c.edited(); assertEquals(null, c.result)
        c.commit("A"); c.commit(""); assertEquals("an empty key has no verdict", null, c.result)
    }

    @Test fun aValidForTheCurrentKeyEnables() = runTest {
        var enabled = 0
        val c = SearchKeyCheck(verify = { KeyCheckResult(KeyCheck.VALID) }, onValid = { enabled++ })
        c.commit("A"); assertEquals(1, enabled); assertEquals(KeyCheck.VALID, c.result?.kind)
    }
}
