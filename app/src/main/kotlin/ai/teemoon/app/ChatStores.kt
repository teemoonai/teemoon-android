// android-only: each open chat's view model in a store of its own, so a chat left behind is released.
package ai.teemoon.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * One [ViewModelStore] per chat, held by the activity (this is itself an activity-scoped ViewModel, so the chats' lifetime
 * is the activity's, as before). The activity's own store has no way to drop one entry, so every chat opened in a session
 * kept its ChatViewModel — transcript, images, generation state — until the activity went. [release] clears the chats
 * the user has left, except one still answering: a turn outlives leaving its chat (TurnService), and its view model with
 * it, until the turn ends and the next release takes it.
 */
internal class ChatStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun owner(threadId: String): ViewModelStoreOwner {
        val store = stores.getOrPut(threadId) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore = store }
    }

    /** Clears every chat's store but [keep]'s and those [busy] says are still running a turn. */
    fun release(keep: String?, busy: (String) -> Boolean) {
        val it = stores.entries.iterator()
        while (it.hasNext()) {
            val (id, store) = it.next()
            if (id != keep && !busy(id)) { store.clear(); it.remove() }
        }
    }

    internal val open: Set<String> get() = stores.keys

    override fun onCleared() { stores.values.forEach { it.clear() }; stores.clear() }
}
