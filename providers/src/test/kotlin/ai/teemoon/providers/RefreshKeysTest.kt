package ai.teemoon.providers

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class RefreshKeysTest {
    private fun provider(id: String) = Provider(id = id, name = "test", endpoint = "https://api.example-$id.com/v1", model = "m")

    @Test fun theKeysARefreshSendsAreReadOffTheCallersThread() {
        val io = Executors.newSingleThreadExecutor { Thread(it, "refresh-keys-io") }.asCoroutineDispatcher()
        try {
            val readOn = mutableListOf<String>()
            val a = provider("a"); val b = provider("b")
            val key = runBlocking {
                refreshKeys(listOf(a, b), io) { readOn += Thread.currentThread().name; "sk-" + it.id }
            }
            // the where sheet starts this refresh on the main thread: a Keystore decrypt there is a dropped frame
            assertTrue("read on $readOn", readOn.isNotEmpty() && readOn.all { it.startsWith("refresh-keys-io") })
            assertEquals("sk-a", key(a)); assertEquals("sk-b", key(b))
            assertEquals("", key(provider("c")))
        } finally { io.close() }
    }
}
