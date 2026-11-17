// ports: teemoonTests/ImportBehaviorTests.swift @ abe841b — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
// spec: docs/IMPORT_NEAR.md §7 test 8 and §9 (teemoon-ios, draft 7): no connection during an import, and an
// export's image is never fetched or decoded. Android's proof is by construction: the importer's code cannot name a
// network or image type, and the fixture's inline `data:` image never reaches a stored row.
package ai.teemoon.chat.imports

import ai.teemoon.chat.imports.ImportTestSupport.full
import ai.teemoon.chat.imports.ImportTestSupport.import
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatImportNoNetworkTest {
    private val sources: List<File> by lazy {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        File(root, "chat/src/main/kotlin/ai/teemoon/chat/imports").listFiles { f -> f.extension == "kt" }!!.toList()
    }

    /** Anything that can open a connection, resolve a name, or decode an image's bytes. java.net.URI only parses. */
    private val forbidden = listOf(
        "okhttp3", "java.net.URL", "java.net.HttpURLConnection", "java.net.URLConnection", "java.net.Socket",
        "java.net.InetAddress", "java.net.http", "javax.net", "android.net", "java.nio.channels",
        "Base64", "BitmapFactory", "ImageDecoder", "coil", "android.graphics",
        "openConnection", "openStream", "getByName",
    )

    @Test fun theImporterNamesNoNetworkOrImageType() {
        assertTrue("found ${sources.map { it.name }}", sources.size >= 5)
        for (f in sources) {
            val text = f.readText()
            for (word in forbidden) assertFalse("${f.name} names $word", text.contains(word))
            // The one java.net type allowed is URI, for the domain rule: a parse, never a lookup.
            for (line in text.lines().filter { it.startsWith("import java.net.") }) assertEquals("${f.name}: $line", "import java.net.URI", line)
        }
    }

    @Test fun theInlineImageIsNeverDecodedOrStored() = runTest {
        val store = ListImportStore()
        import(full, store)
        val receipt = store.transcript("near:conv_receipt")
        assertTrue(receipt[0].content.contains(ChatImportWriter.IMAGE_PLACEHOLDER))
        // The fixture's data: URL and its base64 payload appear nowhere; nor does the file id the image or file carried.
        for (m in store.messages.values) for (never in listOf("data:", "base64", "iVBORw0KGgo", "file_img1", "file_pdf1", "file_aud1"))
            assertFalse("$never in ${m.id}", (m.content + m.sourcesJson.orEmpty()).contains(never))
    }
}
