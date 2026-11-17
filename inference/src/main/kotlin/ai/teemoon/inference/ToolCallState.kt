// ports: teemoon/Inference/SSEParser.swift @ v1.0.1 (the two value types only)
package ai.teemoon.inference

/**
 * One turn in the conversation, in the shape an OpenAI-compatible server wants. [images]: re-encoded pictures this turn
 * carries (IMAGES.md §4) — never the bytes a user or an export supplied, only what the isolated decoder produced.
 */
data class WireMessage(val role: String, val content: String, val images: List<WireImage> = emptyList())

/** A stored image as sent: its type and its re-encoded bytes. */
class WireImage(val mime: String, val bytes: ByteArray) {
    /** IMAGES.md §4: `{"type":"image_url","image_url":{"url":"data:<mime>;base64,…"}}`. */
    fun part(): Map<String, Any> = mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)))
}

/**
 * A message's `content` on the wire: the text when there are no images; with images, an ARRAY — the text part first (when
 * there is text), then each image (IMAGES.md §4). E2EE seals an array whole (E2EE.encryptRequestBody).
 */
fun wireContent(text: String, images: List<WireImage>): Any =
    if (images.isEmpty()) text
    else (if (text.isEmpty()) emptyList() else listOf(mapOf("type" to "text", "text" to text))) + images.map { it.part() }

/** One tool call being assembled. Mirrors Swift's `ToolCallState`. */
data class ToolCallState(
    val id: String = "",
    val name: String = "",
    val arguments: String = "",
)

/**
 * The minimal tool schema the client-side RECOVERY parser needs to disambiguate
 * a malformed tool call the server parser could not normalize (e.g. GLM's
 * kwarg-collapse): the function name and its parameter names. teemoon knows
 * these because it registered the tools — which is exactly the knowledge the
 * strict server-side parser lacks.
 */
data class RecoveryToolSpec(
    val name: String,
    val paramNames: List<String>,
    val requiredParams: List<String>,
)
