package ai.teemoon.app

// android-only: the import redesign's (f) on a device (pm 2026-10-30) — an export opened from Files ("open with") or
// shared to teemoon starts the app, is copied to the cache while the activity holds the URI's grant, and opens the
// import page at its confirm (iOS cdea210c): the dry run writes nothing, and nothing is imported until "import" is
// tapped — a shared file never writes chats on its own (pm 2026-11-01).
// The URI comes from the app's own FileProvider with a read grant, as a file manager's would; the activity is created
// fresh by each launch (savedInstanceState null), the path a cold start takes. The fixture is the import corpus's
// near-export-v1.json (6 chats).

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingExportColdStartTest {
    @get:Rule val compose = emptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = (ctx.applicationContext as TeemoonApplication).state!!

    /**
     * Each case starts with the fixture's chats gone from the store: a case after one that imported them would land on
     * "nothing new to import" (v7), which has no "import" to tap (the device run of 2026-11-02 failed two cases so).
     */
    @Before fun forgetTheFixturesChats() {
        when (val o = ai.teemoon.chat.ChatDatabase.open(ctx)) {
            is ai.teemoon.chat.ChatDatabase.Open.Ready -> runBlocking {
                val dao = o.db.chatDao()
                dao.observeThreads().first().filter { it.id.startsWith("near:") }.forEach { dao.deleteThread(it.id) }
            }
            is ai.teemoon.chat.ChatDatabase.Open.Failed -> error("the store did not open: $o")
        }
    }

    /** The arrival's confirm step, on the import page: counted, nothing written. */
    private fun atTheConfirm(): ChatImportUi.Confirm {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("import.confirm")).fetchSemanticsNodes().isNotEmpty() }
        return app.chatImport.state.value as ChatImportUi.Confirm
    }

    /** The fixture where the app's FileProvider serves it (file_paths.xml: cache/camera/), and its content URI. */
    private fun exportUri(): android.net.Uri {
        val f = File(ctx.cacheDir, "camera/near-export-v1.json").apply { parentFile!!.mkdirs() }
        javaClass.getResourceAsStream("/import/near/near-export-v1.json")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    private fun importedAfter(launch: Intent) {
        // an earlier case's flow ends BEFORE the launch: dismissed after it, it raced the arrival's own dry run and could
        // end that flow instead, leaving the start page (the device run of 2026-11-02)
        (ctx.applicationContext as TeemoonApplication).state?.chatImport?.dismiss()
        ActivityScenario.launch<MainActivity>(launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)).use {
            val preview = atTheConfirm()
            compose.onNode(hasTestTag("import.confirm")).performClick()
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && app.chatImport.state.value !is ChatImportUi.Finished) {
                assertTrue("the import failed: ${app.chatImport.state.value}", app.chatImport.state.value !is ChatImportUi.Failed)
                Thread.sleep(100)
            }
            assertTrue("no import finished: ${app.chatImport.state.value}", app.chatImport.state.value is ChatImportUi.Finished)
            assertEquals("the import wrote what the confirm promised", ChatImportUi.Finished(preview.preview), app.chatImport.state.value)
            // the app's own store, read as the app opens it
            val threads = when (val o = ai.teemoon.chat.ChatDatabase.open(ctx)) {
                is ai.teemoon.chat.ChatDatabase.Open.Ready -> runBlocking { o.db.chatDao().observeThreads().first() }
                is ai.teemoon.chat.ChatDatabase.Open.Failed -> error("the store did not open: $o")
            }
            assertTrue("the export's chats are in the store", threads.any { it.id == "near:conv_lisbon" })
            assertTrue("nothing lingers but the one cached copy", (File(ctx.cacheDir, IncomingExport.DIR).list()?.size ?: 0) <= 1)
            app.chatImport.dismiss()
        }
    }

    @Test fun anExportOpenedFromFilesIsImportedOnAColdStart() =
        importedAfter(Intent(Intent.ACTION_VIEW).setDataAndType(exportUri(), IncomingExport.MIME).setClass(ctx, MainActivity::class.java))

    @Test fun anExportSharedToTeemoonIsImported() =
        importedAfter(Intent(Intent.ACTION_SEND).setType(IncomingExport.MIME).putExtra(Intent.EXTRA_STREAM, exportUri()).setClass(ctx, MainActivity::class.java))

    /** Untapped, the confirm writes nothing: the store's chat count is what it was before the file arrived. */
    @Test fun anArrivalWritesNothingBeforeTheTap() {
        fun count() = when (val o = ai.teemoon.chat.ChatDatabase.open(ctx)) {
            is ai.teemoon.chat.ChatDatabase.Open.Ready -> runBlocking { o.db.chatDao().observeThreads().first().size }
            is ai.teemoon.chat.ChatDatabase.Open.Failed -> error("the store did not open: $o")
        }
        val before = count()
        val launch = Intent(Intent.ACTION_SEND).setType(IncomingExport.MIME).putExtra(Intent.EXTRA_STREAM, exportUri()).setClass(ctx, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)).use {
            atTheConfirm()
            Thread.sleep(1_000)
            assertEquals("the arrival wrote chats before the tap", before, count())
            app.chatImport.dismiss()
        }
    }

    /**
     * The filters as Android resolves them (an implicit intent, as a file manager or a share sheet sends): a json export
     * by view or share, and since 1.1.0's share-in (3f5fe2ba) images and text by share. Nothing else.
     */
    @Test fun aJsonExportAndSharedImagesOrTextResolveToTeemoon() {
        val uri = exportUri()
        fun resolves(i: Intent) = ctx.packageManager.queryIntentActivities(i.setPackage(ctx.packageName), 0).any { it.activityInfo.name == MainActivity::class.java.name }
        assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, IncomingExport.MIME)))
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType(IncomingExport.MIME).putExtra(Intent.EXTRA_STREAM, uri)))
        assertTrue("octet-stream is not offered", !resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")))
        assertTrue("a web link is not offered", !resolves(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("https://example.com/x.json"), IncomingExport.MIME)))
        assertTrue("an image share is offered (share-in)", resolves(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)))
        assertTrue("several images are offered (share-in)", resolves(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")))
        assertTrue("shared text is offered (share-in)", resolves(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")))
        assertTrue("viewing an image is not offered", !resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")))
        assertTrue("a video share is not offered", !resolves(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)))
    }
}
