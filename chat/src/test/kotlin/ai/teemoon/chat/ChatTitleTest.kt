package ai.teemoon.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A chat is named by its FIRST question (the maintainer, 2026-10-25: "a"). Until 1.1.0 every send rewrote the title, so the
 * chats list showed the newest question, and continuing an imported near chat replaced the title it came with.
 */
class ChatTitleTest {
    @Test fun theFirstQuestionNamesTheChatAndLaterOnesDoNot() = runTest {
        val dao = ListChatDao(); val repo = ChatRepository(dao)
        repo.createThread("t", 1L)
        repo.titleFromFirstMessage("t", "  Why is the sky blue?\nOne sentence.")
        assertEquals("Why is the sky blue?", dao.threads.single().title)
        repo.titleFromFirstMessage("t", "And why are sunsets red?")
        assertEquals("Why is the sky blue?", dao.threads.single().title)
    }

    @Test fun anImportedTitleSurvivesTheNextQuestion() = runTest {
        val dao = ListChatDao(); val repo = ChatRepository(dao)
        dao.insertChat(ThreadEntity("near:conv_lisbon", "Two days in Lisbon", 1L), listOf(MessageEntity("near:conv_lisbon:m", "near:conv_lisbon", "user", "hi", 1L)))
        repo.titleFromFirstMessage("near:conv_lisbon", "What about Porto?")
        assertEquals("Two days in Lisbon", dao.threads.single().title)
    }

    @Test fun aBlankQuestionNamesNothing() = runTest {
        val dao = ListChatDao(); val repo = ChatRepository(dao)
        repo.createThread("t", 1L)
        repo.titleFromFirstMessage("t", "   \n  ")
        assertEquals(null, dao.threads.single().title)
        repo.titleFromFirstMessage("t", "Now a real one")
        assertEquals("Now a real one", dao.threads.single().title)
    }
}
