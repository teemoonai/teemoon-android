// ports: teemoonTests/EmptyChatMoonTests.swift @ 768a47c — the welcome line it tested is gone (the maintainer 2026-11-08, "remove it")
package ai.teemoon.app

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class EmptyChatMoonTest {
    private fun root(): File = generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }.first { File(it, "app/src/main/kotlin").isDirectory }

    /**
     * 1.1.0 (the maintainer 2026-11-08, "remove it"; iOS the same): no first-run welcome line above the where chip. The
     * "choose where" chip stays and is the first run's prompt. A tripwire over the screen's sources, so the line can't
     * come back unnoticed.
     */
    @Test fun theFirstRunHasNoWelcomeLine() {
        for (path in listOf("app/src/main/kotlin/ai/teemoon/app/ChatScreen.kt", "app/src/main/kotlin/ai/teemoon/app/EmptyChatMoon.kt")) {
            val source = File(root(), path).takeIf { it.isFile }?.readText() ?: continue
            for (word in listOf("choose where teemoon runs", "chat.welcome", "WELCOME_LINE")) assertFalse("$path carries $word", source.contains(word))
        }
    }
}
