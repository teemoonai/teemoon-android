// ports: teemoon/Providers/ModelCapabilities.swift @ v1.0.1
package ai.teemoon.providers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a *specific model* can do — modeled in the catalog, not discovered by
 * reacting to a 400 in the inference hot path.
 *
 * Distinct from [ProviderCapability], which describes the *provider/endpoint*.
 *
 * Optionality: a `ModelCapabilities?` of **null means "unknown"** — the source
 * didn't tell us. A **non-null** value means "known" (an empty set is a *known*
 * "supports none of these"). Callers treat unknown optimistically
 * (see [Provider.modelSupportsTools]).
 *
 * Bit values are PERSISTED, so they are a wire format: renumbering them would
 * silently reinterpret stored data. Stored as a bare integer (Swift
 * RawRepresentable), not `{"rawValue":…}`.
 */
@Serializable(with = ModelCapabilitiesSerializer::class)
data class ModelCapabilities(val rawValue: Int = 0) {
    operator fun plus(other: ModelCapabilities) = ModelCapabilities(rawValue or other.rawValue)
    fun contains(flag: ModelCapabilities): Boolean =
        rawValue and flag.rawValue == flag.rawValue

    companion object {
        val NONE = ModelCapabilities(0)
        /** The model can call function/tools (OpenAI `tools`). */
        val TOOLS = ModelCapabilities(1 shl 0)
        val VISION = ModelCapabilities(1 shl 1)
        val UPLOADS = ModelCapabilities(1 shl 2)
        /**
         * Both live sources report it and teemoon dropped it: Ollama lists `audio`
         * for gemma4 (`/api/show`), and near.ai lists it in `input_modalities`.
         */
        val AUDIO = ModelCapabilities(1 shl 3)
        /**
         * Ollama calls it `thinking`, near.ai calls it `reasoning`. One capability,
         * two names on the wire.
         */
        val THINKING = ModelCapabilities(1 shl 4)
        // Role flags — ABSENCE is meaningful. Never write
        // `if (!caps.contains(COMPLETION))` and conclude a model can't chat:
        // you would be reading "this source didn't say" as "no".
        val COMPLETION = ModelCapabilities(1 shl 5)
        val EMBEDDING = ModelCapabilities(1 shl 6)
        val INSERT = ModelCapabilities(1 shl 7)

        /**
         * Build from Ollama `/api/show` `capabilities`. Always returns a *known*
         * (non-null) value — Ollama tells us explicitly. Unrecognised strings are
         * ignored, so a newer Ollama can add a word without breaking the decode.
         */
        fun fromOllama(raw: List<String>): ModelCapabilities {
            var caps = 0
            for (c in raw) {
                caps = caps or when (c.lowercase()) {
                    "tools" -> TOOLS.rawValue
                    "vision" -> VISION.rawValue
                    "audio" -> AUDIO.rawValue
                    "thinking" -> THINKING.rawValue
                    "completion" -> COMPLETION.rawValue
                    "embedding" -> EMBEDDING.rawValue
                    "insert" -> INSERT.rawValue
                    else -> 0
                }
            }
            return ModelCapabilities(caps)
        }
    }
}

/**
 * Flag names on a catalogue row. [KnownModel.capabilities] is a [Set] of these;
 * [ModelCapabilities] is the persisted bitset (Swift OptionSet).
 */
enum class ModelCapability {
    TOOLS, VISION, UPLOADS, AUDIO, THINKING, COMPLETION, EMBEDDING, INSERT,
}

fun ModelCapabilities.toCapabilitySet(): Set<ModelCapability> {
    val s = mutableSetOf<ModelCapability>()
    if (contains(ModelCapabilities.TOOLS)) s += ModelCapability.TOOLS
    if (contains(ModelCapabilities.VISION)) s += ModelCapability.VISION
    if (contains(ModelCapabilities.UPLOADS)) s += ModelCapability.UPLOADS
    if (contains(ModelCapabilities.AUDIO)) s += ModelCapability.AUDIO
    if (contains(ModelCapabilities.THINKING)) s += ModelCapability.THINKING
    if (contains(ModelCapabilities.COMPLETION)) s += ModelCapability.COMPLETION
    if (contains(ModelCapabilities.EMBEDDING)) s += ModelCapability.EMBEDDING
    if (contains(ModelCapabilities.INSERT)) s += ModelCapability.INSERT
    return s
}

fun ModelCapabilities.Companion.fromCapabilitySet(s: Set<ModelCapability>?): ModelCapabilities? {
    if (s == null) return null
    var v = 0
    if (ModelCapability.TOOLS in s) v = v or TOOLS.rawValue
    if (ModelCapability.VISION in s) v = v or VISION.rawValue
    if (ModelCapability.UPLOADS in s) v = v or UPLOADS.rawValue
    if (ModelCapability.AUDIO in s) v = v or AUDIO.rawValue
    if (ModelCapability.THINKING in s) v = v or THINKING.rawValue
    if (ModelCapability.COMPLETION in s) v = v or COMPLETION.rawValue
    if (ModelCapability.EMBEDDING in s) v = v or EMBEDDING.rawValue
    if (ModelCapability.INSERT in s) v = v or INSERT.rawValue
    return ModelCapabilities(v)
}

object ModelCapabilitiesSerializer : KSerializer<ModelCapabilities> {
    override val descriptor = PrimitiveSerialDescriptor("ModelCapabilities", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: ModelCapabilities) =
        encoder.encodeInt(value.rawValue)
    override fun deserialize(decoder: Decoder) = ModelCapabilities(decoder.decodeInt())
}
