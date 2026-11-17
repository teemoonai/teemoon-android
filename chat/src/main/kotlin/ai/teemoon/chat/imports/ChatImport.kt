// ports: teemoon/Chat/Import/ChatImporter.swift @ 8d0b76c — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
package ai.teemoon.chat.imports

import java.io.InputStream

/**
 * One import, start to finish: the file's first element picks the source, the source reads the file a conversation at
 * a time, the writer stores each chat as it comes. Plain JSON today; a zip reader would sit in front of [run] and
 * hand it the inner file's stream.
 */
object ChatImport {
    /** Every source the app can read. A new one is added here, beside its fixture folder. */
    val sources: List<ChatImportSource> = listOf(NearChatImport)

    /**
     * Imports [input] into [writer]'s store. Throws [NotAnExportException] when the file is none of [sources]' kinds
     * — the only file-level error: once the kind is known, a bad part is skipped and counted, never fatal.
     */
    suspend fun run(
        input: InputStream, writer: ChatImportWriter, sources: List<ChatImportSource> = this.sources,
        /** Where image payloads are set aside while the file streams (IMAGES.md §3); emptied when the run ends. */
        spillDir: java.io.File? = null,
    ): ImportSummary = try { scan(input, writer, sources, spillDir) } finally { spillDir?.deleteRecursively() }

    private suspend fun scan(input: InputStream, writer: ChatImportWriter, sources: List<ChatImportSource>, spillDir: java.io.File?): ImportSummary {
        val scanner = TopLevelArrayScanner(input, spillDir = spillDir)
        scanner.open()
        // §4: the kind is read from the first element within the caps; any over a cap before it are handed on (a
        // source counts them). An empty array is an export with nothing in it; a first element that is cut off or
        // does not parse, or a file with nothing within the caps, is not an export as far as the user can tell.
        val overCap = ArrayList<ScannedElement>()
        var first: ScannedElement? = scanner.next() ?: return ImportSummary()
        while (first is ScannedElement.TooBig || first is ScannedElement.TooDeep) { overCap += first; first = scanner.next() }
        if (first !is ScannedElement.Value) throw NotAnExportException(if (first == null) "nothing within the caps" else "the first element is broken")
        val source = sources.firstOrNull { it.detect(first.keys) } ?: throw NotAnExportException("no source reads this file")
        return writer.write(source.parse(overCap, first, scanner))
    }
}
