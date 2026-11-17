package ai.teemoon.chat.search

// android-only: the emulator import run (2026-11-06) — "show chats" after an import opened the list still filtered by
// an earlier search, so the chats just imported could be hidden. pm: show chats opens the list with the search cleared.

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListSearchMemoryTest {
    @Test fun aFreshOpenClearsTheSearchAndIsCounted() {
        val memory = ChatListSearchMemory()
        memory.query = "img_detail"
        memory.openFresh()
        assertEquals("", memory.query)
        assertEquals(1, memory.freshOpens)
        // the remembered search otherwise survives leaving the list, as iOS's does
        memory.query = "flux"
        assertEquals("flux", memory.query)
    }
}
