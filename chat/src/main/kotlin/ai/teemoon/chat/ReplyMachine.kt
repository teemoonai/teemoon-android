// ports: teemoon/Chat/ReplyMachine.swift @ f6d44c1b
//
// The reply (turn) lifecycle of conformance/reply-machine.json. `handle` is the only writer of reply state; it
// returns effects and never touches Room (or anything else). Change the kit first, then this file.
//
// Kotlin shapes for Swift ones: enums keep Swift's raw values as `rawValue` (the kit's words; ReplyStatus's are what
// messages.status holds — MessageStatus names them); ReplyEvent/ReplyEffect are sealed interfaces of data classes;
// ReplyMachine and EngineCacheEpochs are classes (Swift: structs with mutating methods) — one per chat, owned by
// whoever owns the chat's turn, never copied.
package ai.teemoon.chat

/** A turn's id. Increases per chat; an event carrying any other id than the live one changes nothing. */
@JvmInline
value class ReplyGeneration(val value: Int) : Comparable<ReplyGeneration> {
    override fun compareTo(other: ReplyGeneration): Int = value.compareTo(other.value)
}

enum class ReplyPlace(val rawValue: String) {
    REMOTE("remote"), LOCAL("local");

    companion object { fun fromRaw(raw: String?): ReplyPlace? = entries.firstOrNull { it.rawValue == raw } }
}

enum class FinishReason(val rawValue: String) {
    STOP("stop"), LENGTH("length"), CONTENT_FILTER("contentFilter"), NONE("none");

    companion object { fun fromRaw(raw: String?): FinishReason? = entries.firstOrNull { it.rawValue == raw } }
}

/** messages.status ([MessageEntity.status]; iOS Message.statusRaw). null (rows from before the column) reads as complete. */
enum class ReplyStatus(val rawValue: String) {
    INTERRUPTED("interrupted"), COMPLETE("complete"), STOPPED("stopped"), CUT_OFF("cutOff"), FAILED("failed"),
    DECRYPT_FAILED("decryptFailed");

    companion object {
        fun fromRaw(raw: String?): ReplyStatus? = entries.firstOrNull { it.rawValue == raw }

        /** The marker a saved row shows when it is read back. */
        fun reloadMarker(status: ReplyStatus?): ReplyMarker? = when (status) {
            null, COMPLETE -> null
            INTERRUPTED, FAILED -> ReplyMarker.DIDNT_FINISH
            STOPPED -> ReplyMarker.STOPPED
            CUT_OFF -> ReplyMarker.CUT_SHORT
            DECRYPT_FAILED -> ReplyMarker.DECRYPT_FAILED
        }
    }
}

/** Marker ids; their words are decided elsewhere. */
enum class ReplyMarker(val rawValue: String) {
    DIDNT_FINISH("marker.didntFinish"),
    STOPPED("marker.stopped"),
    CUT_SHORT("marker.cutShort"),
    DECRYPT_FAILED("marker.decryptFailed"),
}

enum class ReplyState(val rawValue: String) {
    IDLE("idle"),
    GATING("gating"), AWAITING_CONFIRM("awaitingConfirm"), REQUESTING("requesting"), STREAMING("streaming"),
    TOOL_RUNNING("toolRunning"),
    COMPLETE("complete"), STOPPED("stopped"), CUT_OFF("cutOff"), FAILED("failed"), DECRYPT_FAILED("decryptFailed"),
    INTERRUPTED("interrupted"), RESTORED("restored"), DISCARDED("discarded"), RETURNED("returned");

    val isLive: Boolean get() = this in LIVE
    val isTerminal: Boolean get() = this != IDLE && !isLive
    /** Engine work is under way: the stream or the on-device decode exists. */
    val isEngineLive: Boolean get() = this in ENGINE_LIVE

    /** What a terminal state writes to the reply row; null = no row (restored, discarded) or not terminal. */
    val status: ReplyStatus?
        get() = when (this) {
            COMPLETE -> ReplyStatus.COMPLETE
            STOPPED -> ReplyStatus.STOPPED
            CUT_OFF -> ReplyStatus.CUT_OFF
            FAILED -> ReplyStatus.FAILED
            DECRYPT_FAILED -> ReplyStatus.DECRYPT_FAILED
            INTERRUPTED -> ReplyStatus.INTERRUPTED
            else -> null
        }

    /** Shown in session when the turn ends here. */
    val marker: ReplyMarker?
        get() = when (this) {
            STOPPED -> ReplyMarker.STOPPED
            CUT_OFF -> ReplyMarker.CUT_SHORT
            DECRYPT_FAILED -> ReplyMarker.DECRYPT_FAILED
            INTERRUPTED -> ReplyMarker.DIDNT_FINISH
            else -> null
        }

    /** Text kept on the row; failures keep none (never ciphertext, never an error string). */
    internal val keepsText: Boolean get() = this in KEEPS_TEXT

    private companion object {
        val LIVE = setOf(GATING, AWAITING_CONFIRM, REQUESTING, STREAMING, TOOL_RUNNING)
        val ENGINE_LIVE = setOf(REQUESTING, STREAMING, TOOL_RUNNING)
        val KEEPS_TEXT = setOf(COMPLETE, STOPPED, CUT_OFF, INTERRUPTED)
    }
}

sealed interface ReplyEvent {
    data class Send(val generation: ReplyGeneration, val place: ReplyPlace, val images: Int = 0) : ReplyEvent
    /** [restorable]: a replaced row has content and is not interrupted, failed or decryptFailed (the caller reads the store). */
    data class Retry(
        val generation: ReplyGeneration, val place: ReplyPlace, val replaces: List<ReplyGeneration>, val restorable: Boolean = false,
    ) : ReplyEvent
    data class ThinkHarder(val generation: ReplyGeneration, val place: ReplyPlace, val supersedes: List<ReplyGeneration>) : ReplyEvent
    data class ReverifyRetry(
        val generation: ReplyGeneration, val place: ReplyPlace, val replaces: List<ReplyGeneration>, val captured: ReplyGeneration,
        val restorable: Boolean = false,
    ) : ReplyEvent
    data class AutoResume(
        val generation: ReplyGeneration, val place: ReplyPlace, val replaces: List<ReplyGeneration>, val restorable: Boolean = false,
    ) : ReplyEvent
    data class GatePassed(val generation: ReplyGeneration, val sealed: Boolean, val sentBeforeConfirmed: String?) : ReplyEvent
    data class GateNeedsConfirm(val generation: ReplyGeneration) : ReplyEvent
    data class GateRefused(val generation: ReplyGeneration) : ReplyEvent
    /** The turn started attestable and the send would now leave unsealed: nothing is sent, it ends like a decline. */
    data class GateWouldGoPlain(val generation: ReplyGeneration) : ReplyEvent
    data class Confirm(val generation: ReplyGeneration, val sentBeforeConfirmed: String?) : ReplyEvent
    data class Decline(val generation: ReplyGeneration) : ReplyEvent
    data class FirstToken(val generation: ReplyGeneration, val text: String) : ReplyEvent
    data class Token(val generation: ReplyGeneration, val text: String) : ReplyEvent
    data class ToolRound(val generation: ReplyGeneration) : ReplyEvent
    data class ToolResult(val generation: ReplyGeneration, val sources: Int) : ReplyEvent
    data class ToolRoundEnded(val generation: ReplyGeneration) : ReplyEvent
    data class Finish(val generation: ReplyGeneration, val reason: FinishReason) : ReplyEvent
    data class StreamEndedWithoutDone(val generation: ReplyGeneration) : ReplyEvent
    data class DecryptFailed(val generation: ReplyGeneration, val field: String) : ReplyEvent
    data class Error(val generation: ReplyGeneration, val kind: String) : ReplyEvent
    data class EngineAbandoned(val generation: ReplyGeneration, val reason: String) : ReplyEvent
    data class EngineLateCallback(val generation: ReplyGeneration, val kind: String) : ReplyEvent
    data object UserStop : ReplyEvent
    data object AppBackgrounded : ReplyEvent
    data object BackgroundTimeExpired : ReplyEvent
    data object AppKilled : ReplyEvent
    data object LaunchRecovery : ReplyEvent
    data object DeleteChat : ReplyEvent
    data object DraftEdited : ReplyEvent

    /** The generation an engine, gate or confirm event speaks for; null for user/app events. */
    val speaksFor: ReplyGeneration?
        get() = when (this) {
            is GatePassed -> generation
            is GateNeedsConfirm -> generation
            is GateRefused -> generation
            is GateWouldGoPlain -> generation
            is Confirm -> generation
            is Decline -> generation
            is FirstToken -> generation
            is Token -> generation
            is ToolRound -> generation
            is ToolResult -> generation
            is ToolRoundEnded -> generation
            is Finish -> generation
            is StreamEndedWithoutDone -> generation
            is DecryptFailed -> generation
            is Error -> generation
            is EngineAbandoned -> generation
            is EngineLateCallback -> generation
            else -> null
        }
}

/** A reply row as the store should hold it after an effect. */
data class ReplyRow(
    val status: ReplyStatus,
    val content: String,
    val sources: Int,
    val sentBeforeConfirmed: String?,
)

sealed interface ReplyEffect {
    data class IgnoreStale(val generation: ReplyGeneration?) : ReplyEffect
    data object RefuseSendKeepDraft : ReplyEffect
    data class CancelEngine(val generation: ReplyGeneration) : ReplyEffect
    data class DeleteRows(val generations: List<ReplyGeneration>) : ReplyEffect
    data class PersistRow(val generation: ReplyGeneration, val row: ReplyRow) : ReplyEffect
    data class PersistSentBeforeConfirmed(val generation: ReplyGeneration, val value: String?) : ReplyEffect
    data class PersistQuestion(val generation: ReplyGeneration, val images: Int) : ReplyEffect
    data class StartGate(val generation: ReplyGeneration) : ReplyEffect
    data class AskUser(val generation: ReplyGeneration) : ReplyEffect
    data class SendRequest(val generation: ReplyGeneration, val images: Int) : ReplyEffect
    /**
     * Declined on a fresh send: delete the question and its reply row; the question's text and images go back to
     * the composer ([ComposerReturn.afterReturn]; iOS ChatViewModel.composerAfterReturn). Nothing persisted, no marker.
     */
    data class ReturnQuestionToComposer(val generation: ReplyGeneration) : ReplyEffect
    data class ShowMarker(val marker: ReplyMarker) : ReplyEffect
    data object ShowLastRequestCard : ReplyEffect
    data object ArmResume : ReplyEffect
    data object DisarmResume : ReplyEffect
    data class SwapSupersededDisplay(val generation: ReplyGeneration) : ReplyEffect
}

/** One chat's reply slot. [handle] is the only writer. Not thread-safe: one owner calls it, on one thread. */
class ReplyMachine(highest: ReplyGeneration = ReplyGeneration(0)) {
    /** The event that started the live generation (the kit's `origin` guard). */
    enum class Origin { SEND, RETRY, THINK_HARDER, REVERIFY_RETRY, AUTO_RESUME }

    private class Live(
        val id: ReplyGeneration,
        val place: ReplyPlace,
        val supersedes: List<ReplyGeneration>,
        val origin: Origin,
        val images: Int = 0,
        val replaces: List<ReplyGeneration> = emptyList(),
        val restorable: Boolean = false,
        var sent: Boolean = false,
        var text: String = "",
        var sources: Int = 0,
        var sentBeforeConfirmed: String? = null,
    ) {
        // Swift: text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty. isBlank is this repo's reading of
        // that (SendPrep.isPromptEmpty); the two differ only on U+0085 and U+001C–U+001F.
        val hasText: Boolean get() = text.isNotBlank()

        /** A retry, re-verify retry or auto-resume whose request has not gone out: it has changed nothing on disk. */
        val unsentRetry: Boolean get() = origin in RETRY_FAMILY && !sent
    }

    private companion object {
        val RETRY_FAMILY = setOf(Origin.RETRY, Origin.REVERIFY_RETRY, Origin.AUTO_RESUME)
    }

    var state: ReplyState = ReplyState.IDLE
        private set
    var highest: ReplyGeneration = highest
        private set
    var resumeArmed: Boolean = false
        private set
    private var current: Live? = null

    val live: ReplyGeneration? get() = current?.id

    fun handle(event: ReplyEvent): List<ReplyEffect> {
        event.speaksFor?.let { g -> if (g != current?.id) return listOf(ReplyEffect.IgnoreStale(g)) }
        if (event is ReplyEvent.ReverifyRetry && event.captured != highest) return listOf(ReplyEffect.IgnoreStale(event.captured))

        when (event) {
            is ReplyEvent.Send -> {
                if (state.isLive) return listOf(ReplyEffect.RefuseSendKeepDraft)
                return listOf<ReplyEffect>(ReplyEffect.PersistQuestion(event.generation, event.images)) +
                    start(event.generation, event.place, replaces = emptyList(), supersedes = emptyList(), Origin.SEND, event.images)
            }
            is ReplyEvent.Retry ->
                return retire() + start(event.generation, event.place, event.replaces, supersedes = emptyList(), Origin.RETRY,
                    restorable = event.restorable)
            is ReplyEvent.ReverifyRetry ->
                return retire() + start(event.generation, event.place, event.replaces, supersedes = emptyList(), Origin.REVERIFY_RETRY,
                    restorable = event.restorable)
            is ReplyEvent.ThinkHarder ->
                return retire() + start(event.generation, event.place, replaces = emptyList(), supersedes = event.supersedes, Origin.THINK_HARDER)
            is ReplyEvent.AutoResume -> {
                if (state != ReplyState.INTERRUPTED || !resumeArmed) return emptyList()
                return start(event.generation, event.place, event.replaces, supersedes = emptyList(), Origin.AUTO_RESUME,
                    restorable = event.restorable)
            }
            ReplyEvent.LaunchRecovery -> {
                if (state != ReplyState.INTERRUPTED) return emptyList()
                resumeArmed = false
                return listOf(ReplyEffect.ShowMarker(ReplyMarker.DIDNT_FINISH), ReplyEffect.DisarmResume)
            }
            else -> {}
        }
        // Swift copies `current` into a local, mutates it and writes it back; every mutating case here writes it
        // back, so mutating the one Live in place is the same machine.
        val live = current ?: return emptyList()
        if (!state.isLive) return emptyList()

        // Swift's `switch (state, event)`: the first case that matches wins, in this order.
        return when {
            event == ReplyEvent.UserStop -> listOf<ReplyEffect>(ReplyEffect.CancelEngine(live.id)) + end(ReplyState.STOPPED)
            event == ReplyEvent.AppKilled -> {
                current = null
                state = if (live.supersedes.isEmpty() && !live.unsentRetry) ReplyState.INTERRUPTED else ReplyState.RESTORED
                emptyList()
            }
            event == ReplyEvent.DeleteChat -> {
                current = null
                state = ReplyState.DISCARDED
                listOf(ReplyEffect.CancelEngine(live.id), ReplyEffect.DeleteRows((if (live.unsentRetry) live.replaces else emptyList()) + live.id))
            }

            state == ReplyState.GATING && event is ReplyEvent.GatePassed -> sendOut(live, event.sentBeforeConfirmed)
            state == ReplyState.AWAITING_CONFIRM && event is ReplyEvent.Confirm -> sendOut(live, event.sentBeforeConfirmed)
            state == ReplyState.GATING && event is ReplyEvent.GateNeedsConfirm -> {
                state = ReplyState.AWAITING_CONFIRM
                listOf(ReplyEffect.AskUser(live.id))
            }
            state == ReplyState.GATING && event is ReplyEvent.GateRefused -> end(ReplyState.FAILED)
            // Confirm re-reads the gate: a hard verdict while the user was asked ends the turn like a decline. So does a
            // turn that started attestable and would now leave unsealed, asked or not (0.6.6 gateWouldGoPlain).
            (state == ReplyState.AWAITING_CONFIRM && (event is ReplyEvent.Decline || event is ReplyEvent.GateRefused || event is ReplyEvent.GateWouldGoPlain)) ||
                (state == ReplyState.GATING && event is ReplyEvent.GateWouldGoPlain) -> {
                if (live.origin != Origin.SEND) return end(ReplyState.STOPPED)   // retry family: restored when restorable
                current = null
                state = ReplyState.RETURNED
                listOf(ReplyEffect.ReturnQuestionToComposer(live.id), ReplyEffect.DeleteRows(listOf(live.id)))
            }

            (state == ReplyState.REQUESTING && event is ReplyEvent.FirstToken) ||
                ((state == ReplyState.REQUESTING || state == ReplyState.STREAMING) && event is ReplyEvent.Token) -> {
                val first = !live.hasText
                live.text += if (event is ReplyEvent.FirstToken) event.text else (event as ReplyEvent.Token).text
                state = ReplyState.STREAMING
                if (first && live.supersedes.isNotEmpty()) listOf(ReplyEffect.SwapSupersededDisplay(live.id)) else emptyList()
            }
            (state == ReplyState.REQUESTING || state == ReplyState.STREAMING) && event is ReplyEvent.ToolRound -> {
                state = ReplyState.TOOL_RUNNING
                emptyList()
            }
            state == ReplyState.TOOL_RUNNING && event is ReplyEvent.ToolResult -> {
                live.sources += event.sources
                emptyList()
            }
            state == ReplyState.TOOL_RUNNING && event is ReplyEvent.ToolRoundEnded -> {
                state = ReplyState.REQUESTING
                emptyList()
            }
            (state == ReplyState.REQUESTING || state == ReplyState.STREAMING) && event is ReplyEvent.Finish -> {
                if (!live.hasText) end(ReplyState.FAILED)
                else end(if (event.reason == FinishReason.LENGTH || event.reason == FinishReason.CONTENT_FILTER) ReplyState.CUT_OFF else ReplyState.COMPLETE)
            }
            (state == ReplyState.REQUESTING || state == ReplyState.STREAMING) && event is ReplyEvent.StreamEndedWithoutDone ->
                end(if (live.hasText) ReplyState.CUT_OFF else ReplyState.FAILED)

            event is ReplyEvent.DecryptFailed && state.isEngineLive -> end(ReplyState.DECRYPT_FAILED)
            event is ReplyEvent.Error && state.isEngineLive -> end(ReplyState.FAILED)
            event == ReplyEvent.AppBackgrounded && state.isEngineLive && live.place == ReplyPlace.LOCAL -> {
                val arm = live.supersedes.isEmpty()
                val effects = listOf<ReplyEffect>(ReplyEffect.CancelEngine(live.id)) + end(ReplyState.INTERRUPTED)
                resumeArmed = arm
                effects + if (arm) listOf(ReplyEffect.ArmResume) else emptyList()
            }
            event == ReplyEvent.BackgroundTimeExpired && state.isEngineLive && live.place == ReplyPlace.REMOTE ->
                listOf<ReplyEffect>(ReplyEffect.CancelEngine(live.id)) + end(ReplyState.INTERRUPTED)
            else -> emptyList()
        }
    }

    private fun sendOut(live: Live, sentBeforeConfirmed: String?): List<ReplyEffect> {
        live.sentBeforeConfirmed = sentBeforeConfirmed
        // An unsent retry commits only now: the replaced rows go and its own row is written.
        val commit = if (live.unsentRetry) listOf(
            ReplyEffect.DeleteRows(live.replaces),
            ReplyEffect.PersistRow(live.id, ReplyRow(ReplyStatus.INTERRUPTED, content = "", sources = 0, sentBeforeConfirmed)),
        ) else emptyList()
        live.sent = true
        state = ReplyState.REQUESTING
        return commit + listOf(ReplyEffect.PersistSentBeforeConfirmed(live.id, sentBeforeConfirmed), ReplyEffect.SendRequest(live.id, live.images))
    }

    private fun start(
        g: ReplyGeneration, place: ReplyPlace, replaces: List<ReplyGeneration>, supersedes: List<ReplyGeneration>,
        origin: Origin, images: Int = 0, restorable: Boolean = false,
    ): List<ReplyEffect> {
        current = Live(g, place, supersedes, origin, images, replaces, restorable)
        highest = maxOf(highest, g)
        resumeArmed = false
        state = ReplyState.GATING
        // A retry family turn changes nothing on disk until its request goes out (gatePassed / confirm).
        if (origin != Origin.SEND && origin != Origin.THINK_HARDER) return listOf(ReplyEffect.StartGate(g))
        val effects = mutableListOf<ReplyEffect>()
        if (supersedes.isEmpty()) {
            effects += ReplyEffect.PersistRow(g, ReplyRow(ReplyStatus.INTERRUPTED, content = "", sources = 0, sentBeforeConfirmed = null))
        }
        return effects + ReplyEffect.StartGate(g)
    }

    /** Ends the live generation before a restart: a think harder or an unsent retry writes nothing, any other turn is stopped. */
    private fun retire(): List<ReplyEffect> {
        val live = current ?: return emptyList()
        if (!state.isLive) return emptyList()
        current = null
        if (live.supersedes.isNotEmpty() || live.unsentRetry) return listOf(ReplyEffect.CancelEngine(live.id))
        return listOf(
            ReplyEffect.CancelEngine(live.id),
            ReplyEffect.PersistRow(live.id, ReplyRow(ReplyStatus.STOPPED, live.text, live.sources, live.sentBeforeConfirmed)),
        )
    }

    /**
     * The live generation ends in [target]; a think harder that did not produce a reply ends restored, and so does an
     * unsent retry whose replaced reply still has content.
     */
    private fun end(target: ReplyState): List<ReplyEffect> {
        val live = current ?: return emptyList()
        val status = target.status ?: return emptyList()
        current = null
        val produced = target == ReplyState.COMPLETE || target == ReplyState.CUT_OFF
        if (live.supersedes.isNotEmpty() && !produced) {
            state = ReplyState.RESTORED
            return if (target == ReplyState.FAILED || target == ReplyState.DECRYPT_FAILED) listOf(ReplyEffect.ShowLastRequestCard) else emptyList()
        }
        if (live.unsentRetry && live.restorable) {
            // Never destroy a reply the user already had: nothing was sent, it stands as it was.
            state = ReplyState.RESTORED
            return if (target == ReplyState.FAILED) listOf(ReplyEffect.ShowLastRequestCard) else emptyList()
        }
        state = target
        val effects = mutableListOf<ReplyEffect>()
        if (live.unsentRetry) effects += ReplyEffect.DeleteRows(live.replaces)
        effects += ReplyEffect.PersistRow(live.id, ReplyRow(status, if (target.keepsText) live.text else "", live.sources, live.sentBeforeConfirmed))
        if (produced && live.supersedes.isNotEmpty()) effects += ReplyEffect.DeleteRows(live.supersedes)
        target.marker?.let { effects += ReplyEffect.ShowMarker(it) }
        if (target == ReplyState.FAILED) effects += ReplyEffect.ShowLastRequestCard
        return effects
    }
}

/** G8: the on-device engine cache's epoch. Evict bumps it; a build that finishes under an older epoch is released. */
class EngineCacheEpochs {
    enum class State(val rawValue: String) { EMPTY("empty"), BUILDING("building"), RESIDENT("resident") }

    sealed interface Effect {
        data class StartBuild(val epoch: Int) : Effect
        data object JoinBuild : Effect
        data object Reuse : Effect
        data object Insert : Effect
        data object ReleaseStale : Effect
        data class Bumped(val epoch: Int) : Effect
    }

    var state: State = State.EMPTY
        private set
    var epoch: Int = 0
        private set
    val residentCount: Int get() = if (state == State.RESIDENT) 1 else 0

    fun build(): Effect = when (state) {
        State.EMPTY -> { state = State.BUILDING; Effect.StartBuild(epoch) }
        State.BUILDING -> Effect.JoinBuild
        State.RESIDENT -> Effect.Reuse
    }

    fun evict(): Effect {
        epoch += 1
        state = State.EMPTY
        return Effect.Bumped(epoch)
    }

    fun initialized(epoch: Int): Effect {
        if (epoch != this.epoch || state != State.BUILDING) return Effect.ReleaseStale
        state = State.RESIDENT
        return Effect.Insert
    }
}
