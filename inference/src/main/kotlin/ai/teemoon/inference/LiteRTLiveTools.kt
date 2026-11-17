// ports: teemoon/Inference/LiteRTToolBridge.swift @ b6598639 (the registry's close/open and its live caller: a stopped turn's tool call runs
//        nothing, in iOS's words; a call from any conversation but the live one, the G7 residual — Android first at 5e12b83c)
//
// LiteRT runs the tool loop itself and calls the shim from its own thread, so a decode the user stopped can still call a
// tool after its turn has ended: cancelProcess asks the runtime to stop, it does not wait for it. The shim used to look up
// "this turn's tool" at call time, which ran the stopped turn's search — or, once the next turn had started, the NEXT
// turn's, under its chip and into its sources: a search the user never asked for in that turn.
package ai.teemoon.inference

/**
 * One engine's tools as its shims may call them. A call runs only while a turn is running ([begin]..[end]) AND comes
 * from the conversation that turn runs on. A stopped turn's conversation is never kept, so the next turn always builds a
 * new one ([newConversation]) and the stopped decode's shims stop matching; a kept conversation carries its shims into
 * the next turn, which is why the tools are looked up per call rather than bound into the shim.
 */
class LiteRTLiveTools {
    private var tools: Map<String, BridgedTool> = emptyMap()
    private var conversation = 0
    private var running = false

    /** A conversation is being built: its shims carry the returned id. Every earlier conversation's calls run nothing. */
    @Synchronized fun newConversation(): Int = ++conversation

    /** A turn starts on the current conversation, with its own tools. */
    @Synchronized fun begin(turnTools: List<BridgedTool>) { tools = turnTools.associateBy { it.name }; running = true }

    /** The turn ended — finished, stopped or failed: until the next [begin], no call runs. */
    @Synchronized fun end() { tools = emptyMap(); running = false }

    /** The tool [name] for a call from [from]'s shim, or the refusal the model reads instead. Never throws. */
    suspend fun run(from: Int, name: String, argumentsJson: String): String {
        val tool = synchronized(this) {
            if (!running || from != conversation) return STOPPED
            tools[name]
        } ?: return "[Tool '$name' not found]"
        return tool.execute(argumentsJson)
    }

    companion object {
        /** iOS's string (LiteRTToolBridge.execute @ b6598639): a result, not a throw, so the runtime's loop ends quietly. */
        const val STOPPED = "[This turn was stopped. Do not search.]"
    }
}
