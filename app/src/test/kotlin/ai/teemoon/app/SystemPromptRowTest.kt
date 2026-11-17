// UI audit #10 (pm features/ui-audit-1.1.md; the maintainer 2026-10-25, both platforms): settings → chats shows the system
// prompt as a row with its value, opening its own screen to edit it, with "reset to default". The whole prompt
// used to fill the screen and push "import chats" to the bottom.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemPromptRowTest {
    @Test fun theRowSaysDefaultUntilThePromptIsEdited() {
        assertEquals("default", SystemPromptCopy.rowValue(AppSettings.DEFAULT_SYSTEM_PROMPT))
        assertEquals("custom", SystemPromptCopy.rowValue("You are terse."))
    }

    @Test fun resetIsOfferedOnlyForAnEditedPrompt() {
        assertEquals(false, SystemPromptCopy.canReset(AppSettings.DEFAULT_SYSTEM_PROMPT))
        assertEquals(true, SystemPromptCopy.canReset("You are terse."))
    }

    @Test fun theScreenComesBackAfterProcessDeath() =
        assertEquals(Route.SystemPrompt, RouteCodec.decode(RouteCodec.encode(Route.SystemPrompt)))
}
