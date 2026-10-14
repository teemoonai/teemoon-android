package ai.teemoon.app

// android-only: I8 (docs/USE_CASES.md). An update installed over the previous release keeps every chat, key, place and
// model. tools/upgrade_survival.sh installs the PREVIOUS release, seeds it, installs this build over it with
// `adb install -r` (what the store does: same package, same data), then runs this with `-e upgrade verify`. It launches
// the app PLAINLY — no uitesting flag, so nothing is seeded or cleared — and reads back what the old build wrote.

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.teemoon.chat.ChatDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ai.teemoon.providers.Provider

@RunWith(AndroidJUnit4::class)
class UpgradeSurvivalTest {
    @get:Rule val compose = emptyComposeRule()
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    @Test fun everythingTheOldBuildWroteSurvivesTheUpdate() {
        assumeTrue("run by tools/upgrade_survival.sh (-e upgrade verify)", InstrumentationRegistry.getArguments().getString("upgrade") == "verify")
        val scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer")).fetchSemanticsNodes().isNotEmpty() }
            val app = (ctx.applicationContext as TeemoonApplication).state!!
            // places and keys
            val providers = app.providers.value
            val fireworks = providers.firstOrNull { it.endpoint == Provider.fireworks.endpoint }
            assertTrue("the keyed cloud place is gone: ${providers.map { it.name }}", fireworks != null)
            assertEquals("the cloud key did not survive", "uitest-upgrade-key", app.credentials.credential(fireworks!!))
            val box = providers.firstOrNull { it.name == "upgrade box" }
            assertTrue("the home server is gone: ${providers.map { it.name }}", box != null)
            assertEquals("the selected place changed", box!!.id, app.selectedId.value)
            // chats
            val db = when (val o = ChatDatabase.open(ctx)) { is ChatDatabase.Open.Ready -> o.db; is ChatDatabase.Open.Failed -> error("the chat store failed to open after the update: ${o.reason}") }
            runBlocking {
                for (t in 0..3) assertEquals("fixture thread $t lost messages", 2, db.chatDao().messages("uitest-fixture-$t").size)
            }
            // the phone's model
            assertTrue("the downloaded model is gone", app.localCatalog.any(app.localStorage::isInstalled))
        } finally { scenario.close() }
    }
}
