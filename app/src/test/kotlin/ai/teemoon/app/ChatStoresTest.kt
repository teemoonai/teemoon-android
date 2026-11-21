// android-only: every chat opened in a session kept its view model until the activity went; ChatStores releases the
// chats the user has left, except one whose turn is still running.
package ai.teemoon.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class ChatStoresTest {
    private class Chat : ViewModel() { var cleared = false; override fun onCleared() { cleared = true } }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = Chat() as T
    }

    private fun ChatStores.chat(id: String): Chat = ViewModelProvider.create(owner(id), factory)[id, Chat::class]

    @Test fun leftChatsAreReleasedAndTheCurrentOneKept() {
        val stores = ChatStores()
        val a = stores.chat("a"); val b = stores.chat("b"); val c = stores.chat("c")
        stores.release(keep = "c") { false }
        assertTrue(a.cleared); assertTrue(b.cleared); assertFalse(c.cleared)
        assertEquals(setOf("c"), stores.open)
    }

    @Test fun aChatStillAnsweringIsKeptUntilItsTurnEnds() {
        val stores = ChatStores()
        val a = stores.chat("a"); stores.chat("b")
        var aBusy = true
        stores.release(keep = "b") { it == "a" && aBusy }
        assertFalse("its turn outlives leaving it", a.cleared)
        aBusy = false
        stores.release(keep = "b") { it == "a" && aBusy }
        assertTrue("released once the turn has ended", a.cleared)
    }

    @Test fun reopeningAReleasedChatMakesANewViewModel() {
        val stores = ChatStores()
        val first = stores.chat("a")
        stores.release(keep = null) { false }
        assertTrue(first !== stores.chat("a"))
    }

    @Test fun theSameChatGetsTheSameViewModelWhileItIsOpen() {
        val stores = ChatStores()
        assertTrue(stores.chat("a") === stores.chat("a"))
    }
}
