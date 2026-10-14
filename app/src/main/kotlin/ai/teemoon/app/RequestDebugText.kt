// ports: teemoon/Views/Chat/RequestDebugView.swift @ v1.0.1 (debugText — the copied form of the developer card)
package ai.teemoon.app

import ai.teemoon.inference.ToolCallDebugFormat
import java.util.Locale

object RequestDebugText {
    private fun Double.debugFormatted() = String.format(Locale.US, "%.1fs", this)

    /** The developer card as copied: timings, the redacted URL and headers, the request body, each tool round, the response. */
    fun debugText(info: LastRequestDebugInfo): String {
        val parts = ArrayList<String>()
        parts += "=== ${info.providerName}${if (info.modelID.isEmpty()) "" else " ${info.modelID}"} Debug ==="
        val timing = listOfNotNull(
            info.totalDuration?.debugFormatted(),
            info.timeToFirstToken?.let { "ttft ${it.debugFormatted()}" },
            info.thinkingTime?.let { "think ${it.debugFormatted()}" },
            info.generationTime?.let { "gen ${it.debugFormatted()}" },
            info.outputTokens?.let { "${it}tok" },
            info.tokensPerSecond?.let { String.format(Locale.US, "%.0ftok/s", it) })
        if (timing.isNotEmpty()) parts += timing.joinToString(" · ")
        info.url?.let { parts += "\n--- URL ---\n${DebugHeaderRedaction.copyURLString(it)}" }
        info.requestHeaders?.takeIf { it.isNotEmpty() }?.let { parts += "\n--- Headers ---\n${DebugHeaderRedaction.copyHeaderBlock(it)}" }
        info.requestBodyJson?.let { parts += "\n--- Request Body ---\n${if (info.isE2EEActive) ToolCallDebugFormat.compactRequestBody(it) else it}" }
        info.toolCalls.forEachIndexed { i, tc ->
            var sig = ToolCallDebugFormat.toolCallSignature(tc.name, tc.arguments, tc.result)
            ToolCallDebugFormat.toolCallSubtitle(tc.name, tc.arguments)?.let { sig += " $it" }
            val label = if (info.toolCalls.size > 1) "$sig ${i + 1}" else sig
            ToolCallDebugFormat.sourcesJSONString(tc.result)?.let { parts += "\n--- Tool: $label · Response JSON ---\n$it" }
            parts += "\n--- Tool: $label · Response for LLM ---\n${tc.result}"
        }
        info.responseBody?.takeIf { it.isNotEmpty() }?.let { parts += "\n--- Response ---\n$it" }
        // the URL and headers are masked above by name; any stored key left anywhere else is masked here
        return DebugHeaderRedaction.scrubStoredSecrets(parts.joinToString("\n"))
    }
}
