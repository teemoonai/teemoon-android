// ports: teemoon/Views/Chat/RequestDebugView.swift @ v1.0.1 (debugText — the copied form of the developer card)
package ai.teemoon.app

import ai.teemoon.inference.ToolCallDebugFormat

object RequestDebugText {

    /** The developer card as copied: timings, the redacted URL and headers, the request body, each tool round, the response. */
    fun debugText(info: LastRequestDebugInfo): String {
        val parts = ArrayList<String>()
        parts += "=== ${info.providerName}${if (info.modelID.isEmpty()) "" else " ${info.modelID}"} Debug ==="
        info.localStatsLine?.let { parts += it }
        info.timingLine?.let { parts += it }
        info.phasesLine?.let { parts += "phases $it" }
        info.effortLevel?.let { parts += it }   // "effort high" / "thinking on", the card's words (iOS 7cd8c10)
        info.url?.let { parts += "\n--- URL ---\n${DebugHeaderRedaction.copyURLString(it)}" }
        info.requestHeaders?.takeIf { it.isNotEmpty() }?.let { parts += "\n--- Headers ---\n${DebugHeaderRedaction.copyHeaderBlock(it)}" }
        info.requestBodyJson?.let { parts += "\n--- Request Body ---\n${ai.teemoon.app.imaging.ImageBodyText.summarize(if (info.isE2EEActive) ToolCallDebugFormat.compactRequestBody(it) else it)}" }
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
