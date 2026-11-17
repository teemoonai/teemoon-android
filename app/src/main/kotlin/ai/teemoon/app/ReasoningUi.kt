// The reasoning design (the maintainer 2026-11-13, card composer-reasoning-chip, the approved preview JWAYcGCPNbRKdJEKe9eNpi;
// docs/REASONING_LEVEL.md upstream @ 0e98aaf1 for the levels). Ahead of iOS's spec: pm asked Android to set the detail
// (2026-11-13), iOS to match it or flag a difference.
//
// Developer mode only, everywhere: with it off no reasoning text appears on the chip, in the where sheet or on model
// info. With it on, for a model that takes a level, the chip reads "<model> <level>", the where sheet pins a bar that
// pushes to the levels, and model info shows the model's own list, read-only. The words are ReasoningLevel's.
package ai.teemoon.app

import ai.teemoon.inference.ReasoningLevel

/** What the chip, the where sheet's bar and the levels page show for the selected model. */
data class ReasoningUi(
    /** The chip's secondary text after the name; null: the name alone (developer mode off, no level, or refused). */
    val chipLevel: String?,
    /** The where sheet's pinned bar; null: no bar. */
    val bar: Bar?,
    /** The levels page: the model's own, low to high as it lists them, the pick checked. */
    val levels: List<Level>,
    /** The levels page's footer paragraphs: after a refusal the refusal first, then what a higher level costs. */
    val levelsFooter: List<String>,
) {
    /** [model] heads the levels page; the bar itself doesn't name it (the sheet names it right above). */
    data class Bar(val title: String, val model: String, val value: String) {
        /**
         * What the row draws under its title and at its end: one line, "reasoning … <level>" (the maintainer 2026-11-13: naming the
         * model there was redundant), at any size where it fits; the level goes under the title only where it doesn't
         * (the maintainer, "Do 1 and 3").
         */
        data class Layout(val subtitle: String?, val value: String?)
        fun trailing(oneLineFits: Boolean): Layout = if (oneLineFits) Layout(null, value) else Layout(value, null)
    }
    /** [enabled] false after a refusal: nothing is sent, so no level is picked and none can be (iOS parity, pm 2026-11-13). */
    data class Level(val level: String, val label: String, val picked: Boolean, val enabled: Boolean = true)

    companion object {
        val NONE = ReasoningUi(null, null, emptyList(), listOf(ReasoningLevel.LEVELS_FOOTER))

        /** [stored] is the pick saved under this model (AppSettings), [offered] the model's own list (its catalogue row). */
        fun of(setup: ReasoningLevel.Setup?, developerMode: Boolean, stored: String?, offered: List<String>, modelName: String): ReasoningUi {
            val row = ReasoningLevel.Row.of(setup, developerMode, offered) ?: return NONE
            val value = row.value(stored)
            return ReasoningUi(
                // after a refusal no level is sent, so the chip claims none
                chipLevel = value.takeIf { !row.refused },
                bar = Bar(ReasoningLevel.TITLE, modelName, if (row.refused) ReasoningLevel.REFUSED_VALUE else row.label(value)),
                levels = row.levels.map { Level(it, row.label(it), picked = !row.refused && it == value, enabled = !row.refused) },
                levelsFooter = listOfNotNull(row.footer(stored), ReasoningLevel.LEVELS_FOOTER))
        }

        /**
         * Model info's capability chips: with developer mode off, none that names reasoning ("reasoning", "reasoning
         * (required)") — no reasoning information anywhere outside developer mode (the maintainer 2026-11-13, "Yes yes").
         */
        fun modelInfoFeatures(developerMode: Boolean, features: List<String>): List<String> =
            if (developerMode) features else features.filterNot { it == "reasoning" || it.startsWith("reasoning (") }

        /** Model info's request parameters: OpenRouter's "reasoning", "include_reasoning" and "reasoning_effort" are reasoning information too. */
        fun modelInfoParameters(developerMode: Boolean, parameters: List<String>): List<String> =
            if (developerMode) parameters else parameters.filterNot { it == "reasoning" || it == "include_reasoning" || it == "reasoning_effort" }

        /** Model info's read-only row: the model's own levels, and only in developer mode. */
        fun modelInfoRow(developerMode: Boolean, offered: List<String>): String? =
            offered.takeIf { developerMode && it.isNotEmpty() }?.joinToString(", ")
    }
}

/** A model on a setup and its stored pick: what the levels page reads and writes. */
class ReasoningChoice(val setup: ReasoningLevel.Setup, val stored: () -> String?, val pick: (String?) -> Unit)
