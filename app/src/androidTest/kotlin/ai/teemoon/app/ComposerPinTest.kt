// android-only: the composer's behaviour pinned before it moves to BasicTextField(TextFieldState) for image paste (pm for
// the maintainer, 2026-11-07: "pin today's behaviour first: … draft restore, focus, IME send, … the selection caret").
// Green on the String field; the migration must keep it green. The returned question's merge is ComposerReturn's
// (chat/ComposerReturnTest), and the 200% layout is ComposerPinSnapshotTest's.
package ai.teemoon.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerPinTest {
    @get:Rule val compose = createComposeRule()

    private val field get() = compose.onNodeWithTag("chat.composer")
    private fun selection() = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    /** A host holding the prompt as ChatScreen does; [external] lets a test set it from outside, as a returned question does. */
    private var sends = 0
    private var external: ((String) -> Unit)? = null
    private fun host(initial: String = "", streaming: Boolean = false, focus: FocusRequester? = null) = compose.setContent {
        var prompt by remember { mutableStateOf(initial) }
        external = { prompt = it }
        TeemoonTheme { Composer(value = prompt, onValueChange = { prompt = it }, streaming = streaming, onSend = { sends++ }, onStop = {}, focusRequester = focus) }
    }

    @Test fun typingReachesThePrompt() {
        host()
        field.performTextInput("hello")
        field.assertTextEquals("hello")
    }

    /** The keyboard's action key is send: it sends a prompt with text, and nothing while blank or while a reply streams. */
    @Test fun theImeActionSendsOnlyWhatCanBeSent() {
        host("hello"); field.performImeAction(); compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun theImeActionOnABlankPromptSendsNothing() {
        host("   "); field.performImeAction(); compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun theImeActionWhileStreamingSendsNothing() {
        host("hello", streaming = true); field.performImeAction(); compose.runOnIdle { assertEquals(0, sends) }
    }

    /** ContentView: the prompt takes focus when the thread is empty. */
    @Test fun theFocusRequesterFocusesTheField() {
        val focus = FocusRequester()
        host(focus = focus)
        compose.runOnIdle { focus.requestFocus() }
        field.assertIsFocused()
    }

    /**
     * A prompt set from outside (a returned question, the send clearing it) keeps the caret where it was, held inside
     * the new text — today's String field. The migration keeps this, not the end-of-text a whole-text replace gives.
     */
    @Test fun anOutsideChangeKeepsTheCaretWhereItWas() {
        host("hello world")
        field.performTextInputSelection(TextRange(5))
        compose.runOnIdle { external!!("why?\n\nhello world") }
        assertEquals(TextRange(5), selection())
        compose.runOnIdle { external!!("") }
        assertEquals(TextRange(0), selection())
    }

    /** The draft survives the screen being recreated (ChatScreen's rememberSaveable prompt). */
    @Test fun theDraftSurvivesRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            var prompt by rememberSaveable { mutableStateOf("") }
            TeemoonTheme { Column { Composer(value = prompt, onValueChange = { prompt = it }, streaming = false, onSend = {}, onStop = {}) } }
        }
        field.performTextInput("a draft")
        restore.emulateSavedInstanceStateRestore()
        field.assertTextEquals("a draft")
    }
}
