// ports: teemoon/Inference/SSEParser.swift @ v1.0.1 (the two value types only)
package ai.teemoon.inference

/** One turn in the conversation, in the shape an OpenAI-compatible server wants. */
data class WireMessage(val role: String, val content: String)

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
