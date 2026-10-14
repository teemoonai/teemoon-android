// android-only regression: the final text is handed out BEFORE the reply-signature check runs, so a caller
// can persist the answer without waiting on the TEE (a reply shown, then lost to an app close, 2026-09-03).
package ai.teemoon.inference

import ai.teemoon.confidential.ResponseVerification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnTextBeforeVerifyTest {
    @Test fun onTurnTextFiresBeforeTheSignatureCheck() = runBlocking {
        val order = ArrayList<String>()
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                onPartial("pong")
                return TurnResult(content = "pong", report = TurnReport(verify = { order.add("verify"); ResponseVerification.Verified("0xabc") }))
            }
        }
        val engine = GenerationEngine(transport = transport, events = object : StreamCallbacks {
            override fun onText(text: String) {}
            override fun onTurnText(text: String) { order.add("turnText:$text") }
            override fun onSuccess(result: RequestResult) { order.add("success:${result.teeVerification is ResponseVerification.Verified}") }
        })
        assertEquals("pong", engine.run(emptyList(), "ping"))
        assertEquals(listOf("turnText:pong", "verify", "success:true"), order)
    }
}
