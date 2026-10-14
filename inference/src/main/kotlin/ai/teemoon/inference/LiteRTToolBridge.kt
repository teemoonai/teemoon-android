// ports: teemoon/Inference/LiteRTToolBridge.swift @ v1.0.1
//
// Lets LiteRT-LM call teemoon's tools.
//
// ARCHITECTURAL EXCEPTION, stated plainly: for this transport the tool LOOP
// lives inside LiteRT, not in GenerationEngine. Everywhere else teemoon owns
// the loop precisely so local and remote cannot drift. LiteRT's API does not
// offer the choice — the conversation calls the tool internally and feeds the
// result back itself.
//
// What teemoon keeps despite that: its own tool implementations actually
// execute; flattenedToolSchema, so the model sees a self-contained schema with
// no $ref; coerceArgsToSchema, so a stringified scalar still decodes; the
// sources/queries callbacks, so the grounding rail still populates.
// What it loses: maxToolRounds bounding (re-imposed below as a string refusal),
// ToolCallFormat text recovery, and GenerationEngine.executeTool's rethrow of
// key rejection and cancellation — here, as on iOS, a 401 comes back to the
// model as result text and the round budget ends the retries.
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class LiteRTToolRegistry {
    class Registration(
        val tool: Tool,
        /** Called with (name, argumentsJson) BEFORE the tool runs — what turns the chip from "thinking" to "searching". */
        val onStarted: (String, String) -> Unit,
        /** Called with (name, argumentsJson, result) after each execution, so the transport can report the round. */
        val onExecuted: (String, String, String) -> Unit,
    )
    private val registrations = HashMap<String, Registration>()
    @Synchronized fun register(registration: Registration, name: String) { registrations[name] = registration }
    @Synchronized fun registration(name: String): Registration? = registrations[name]
    @Synchronized fun clear() { registrations.clear() }
    companion object { val shared = LiteRTToolRegistry() }
}

class LiteRTToolBridge(private val registry: LiteRTToolRegistry = LiteRTToolRegistry.shared) {
    /**
     * Rounds teemoon allows, mirroring GenerationEngine's on-device
     * maxToolRounds. LiteRT's own ceiling is 25; on a phone each round is a
     * live search plus a full re-prefill plus a decode, so twenty-five of them
     * is minutes of pinned GPU with the UI showing nothing.
     */
    val maxToolRounds: Int = LocalDefaults.MAX_TOOL_ROUNDS
    private var roundsUsed = 0
    private val lock = Any()
    private val schemaCache = HashMap<String, JsonObject>()

    /** THE ENVELOPE IS LOAD-BEARING: `{"type":"function","function":{name, description, parameters}}` — a flat shape is accepted and then carries no parameters. */
    fun descriptionJson(name: String, description: String): String {
        val parameters = synchronized(lock) { schemaCache[name] }
        val function = buildJsonObject { put("name", JsonPrimitive(name)); put("description", JsonPrimitive(description)); if (parameters != null) put("parameters", parameters) }
        return Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("type", JsonPrimitive("function")); put("function", function) })
    }

    /**
     * The Kotlin binding (litertlm-android 0.16.1) is the other way round from
     * the Swift one: `tool(OpenApiTool)` parses the description as the FLAT
     * `{name, description, parameters}` object (it reads `name` straight off
     * the top level and NPEs on the envelope), and `ToolManager` adds the
     * `{"type":"function","function":…}` envelope itself before the model sees
     * it. So the bridge keeps the iOS envelope as its contract and the binding
     * shim hands over the inner function object. Measured on the emulator:
     * passing the envelope through produced
     * "JsonElement.getAsString() on a null object reference" at createConversation.
     */
    /**
     * Registers teemoon's tools and returns the LiteRT shims. Only tools with a
     * bridge cross over (web_search today); the rest are logged, not silently
     * dropped. The round budget resets HERE — exactly once per turn, right
     * before the conversation is created — so it cannot drift from the thing
     * it bounds.
     */
    fun bridged(tools: List<Tool>, onStarted: (String, String) -> Unit = { _, _ -> }, onExecuted: (String, String, String) -> Unit, warn: (String) -> Unit = {}): List<BridgedTool> {
        synchronized(lock) { roundsUsed = 0 }
        val shims = ArrayList<BridgedTool>()
        for (tool in tools) {
            if (tool.name != BRIDGED_TOOL_NAME) { warn("[litert] no bridge for tool '${tool.name}' — it will not be offered"); continue }
            synchronized(lock) { schemaCache[tool.name] = GenerationEngine.flattenedToolSchema(tool) }
            registry.register(LiteRTToolRegistry.Registration(tool, onStarted, onExecuted), tool.name)
            shims += BridgedTool(tool.name, descriptionJson(tool.name, tool.description)) { args -> execute(tool.name, args) }
        }
        return shims
    }

    /**
     * Runs the registered teemoon tool and reports the round. NEVER throws: a
     * thrown error inside LiteRT's loop aborts the whole generation, whereas an
     * error STRING lets the model recover. Over budget, the call is refused with
     * a string that tells the model to answer from what it already has.
     */
    suspend fun execute(name: String, argumentsJson: String): String {
        val registration = registry.registration(name) ?: return "[Tool '$name' not found]"
        val over = synchronized(lock) { roundsUsed += 1; roundsUsed > maxToolRounds }
        if (over) return "[Search limit reached. Answer the user now, using the results you already have.]"
        // Announce the call BEFORE running it, so the UI can say "searching" for the seconds it actually takes.
        registration.onStarted(name, argumentsJson)
        val result = run(registration.tool, argumentsJson)
        registration.onExecuted(name, argumentsJson, result)
        return result
    }

    /** Same coercion path the shared engine uses, so a model that stringifies a scalar (`"count": "5"`) is tolerated identically on both runtimes. */
    private suspend fun run(tool: Tool, argumentsJson: String): String {
        val coerced = GenerationEngine.toolSchemaJson(tool)?.let { GenerationEngine.coerceArgsToSchema(argumentsJson, it) } ?: argumentsJson
        return try { tool.execute(coerced) } catch (e: Exception) { "[Tool call failed: ${e.message ?: e}]" }
    }

    companion object {
        const val BRIDGED_TOOL_NAME = "web_search"
        fun flatDescription(envelopeJson: String): String {
            val root = Json.parseToJsonElement(envelopeJson).jsonObject
            val function = root["function"]?.jsonObject ?: root
            return Json.encodeToString(JsonObject.serializer(), function)
        }
    }
}
