// ports: teemoon/Inference/ReasoningLevel.swift @ 0e98aaf1
//
// Developer mode's reasoning level per model (docs/REASONING_LEVEL.md upstream, the maintainer 2026-11-13). Read per request
// by the transport, so the app hands it a [chooser] (iOS reads UserDefaults here; this module has no settings). Never a
// compiled list of levels: the model's own, or the control's.
package ai.teemoon.inference

import ai.teemoon.providers.Provider
import java.net.URI

object ReasoningLevel {
    /** Low to high, for "is the choice above high"; a level outside it (a provider's new name) ranks as low. */
    private val order = listOf("none", "minimal", "low", "medium", "high", "xhigh")

    /**
     * The level in force for a setup and model: null with developer mode off, so everyone sends today's low. The app
     * sets it (AppSettings.chosenReasoningLevel); tests set it or pass `chosen` to [ReasoningEffort.bodyFields].
     */
    @Volatile var chooser: (Provider) -> String? = { null }

    fun chosen(provider: Provider): String? = chooser(provider)

    fun menu(control: ReasoningControl, offered: List<String>): List<String> = when (control) {
        is ReasoningControl.Effort -> listOf("low", "medium", "high")
        // Low is what goes without a choice, so it stays on the menu even where the model's list omits it.
        ReasoningControl.OpenRouter -> if (offered.isEmpty()) listOf("low", "medium", "high") else if ("low" in offered) offered else offered + "low"
        ReasoningControl.ThinkingSwitch, ReasoningControl.OnDevice -> listOf("off", "on")
        ReasoningControl.None -> emptyList()
    }

    fun defaultLevel(control: ReasoningControl): String = when (control) {
        ReasoningControl.ThinkingSwitch, ReasoningControl.OnDevice -> "off"
        is ReasoningControl.Effort, ReasoningControl.OpenRouter, ReasoningControl.None -> "low"
    }

    /** API host, lowercased, `|` model id: the refusal key, and the key a choice is stored under. */
    fun key(provider: Provider): String {
        val host = provider.openAIBaseUrl?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase().orEmpty()
        return "$host|${provider.model}"
    }

    /** Think Harder never thinks less than the plain send. */
    fun sent(effort: ReasoningEffort, chosen: String?): String {
        if (effort != ReasoningEffort.THINK_HARDER) return chosen ?: "low"
        val rank = chosen?.let { order.indexOf(it) } ?: -1
        return if (rank > order.indexOf("high")) chosen!! else "high"
    }

    fun thinksOnDevice(effort: ReasoningEffort, chosen: String?): Boolean = effort == ReasoningEffort.THINK_HARDER || chosen == "on"

    /** A stored level the control can't send (the setup changed kind) counts as no choice. */
    fun usable(chosen: String?, control: ReasoningControl): String? {
        chosen ?: return null
        // OpenRouter's list is per model and lives in the catalogue, so any of its names is taken as given.
        val levels = if (control == ReasoningControl.OpenRouter) listOf(chosen) else menu(control, emptyList())
        return chosen.takeIf { it in levels }
    }

    /**
     * What the row and its footer say after a refusal, in one place (pm's wording, card approved 2026-11-13). No field
     * is sent then, so the row says the model's own default: not "none", which is a level some models list.
     */
    const val REFUSED_VALUE = "model's default"
    /** The where sheet's bar and the levels page's title (the maintainer 2026-11-13, card composer-reasoning-chip; draft words). */
    const val TITLE = "reasoning"
    /** The levels page's footer (pm's draft, 2026-11-13); a refusal note follows it. */
    const val LEVELS_FOOTER = "higher levels think longer before answering, so replies take longer and can cost more."
    fun refusedFooter(level: String) = "the server refused $level, so teemoon leaves the setting out and this model uses its own default for now."

    /** A model on a setup, as the detail page needs it: the setup moved to that model, and how it is told to think. */
    data class Setup(val provider: Provider, val control: ReasoningControl)

    /** The detail page's row; [of] null means today's read-only row. */
    data class Row(val levels: List<String>, val defaultLevel: String, val refused: Boolean) {
        fun label(level: String) = if (level == defaultLevel) "$level (default)" else level
        /** The picked level, or the default when nothing is picked or the list no longer has it. */
        fun value(stored: String?): String = stored?.takeIf { it in levels } ?: defaultLevel
        /** What the row reads: after a refusal what is sent ([REFUSED_VALUE]), else [value]. */
        fun shown(stored: String?): String = if (refused) REFUSED_VALUE else value(stored)
        /** After a refusal this names the pick the server turned down; the row keeps one line. */
        fun footer(stored: String?): String? = if (refused) refusedFooter(value(stored)) else null

        companion object {
            fun of(setup: Setup?, developerMode: Boolean, offered: List<String>): Row? {
                if (!developerMode || setup == null) return null
                val levels = menu(setup.control, offered)
                if (levels.isEmpty()) return null
                return Row(levels, defaultLevel(setup.control), ReasoningEffort.wasRefused(setup.provider))
            }
        }
    }
}
