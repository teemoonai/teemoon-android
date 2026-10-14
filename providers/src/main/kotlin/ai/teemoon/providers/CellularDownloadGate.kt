// ports: teemoon/Providers/CellularDownloadGate.swift @ v1.0.4
package ai.teemoon.providers

/**
 * Whether tapping "download" on a model bundle starts it or asks first.
 *
 * The convention for multi-gigabyte downloads: on wi-fi they start, and pause if
 * the phone leaves wi-fi; on mobile data the user is asked, and may choose to
 * wait for wi-fi instead. Nothing here presents UI — the Where sheet and the
 * chat's restart button switch on the result.
 */
object CellularDownloadGate {

    /** Why the tap is being questioned; picks the alert copy. */
    enum class Reason {
        /** Cellular or a personal hotspot. */
        MOBILE_DATA,
        /** Data Saver is on — the user asked for less traffic, on any interface. */
        LOW_DATA_MODE,
    }

    sealed class Decision {
        /** Start now, under this allowance. */
        data class Download(val network: DownloadNetwork) : Decision()
        /** Ask first. The answer becomes a [DownloadNetwork], or nothing. */
        data class Ask(val reason: Reason) : Decision()
    }

    /**
     * A download the user just asked for, on the current path.
     *
     * Started on wi-fi it is WIFI_ONLY: walking out of the house pauses it rather
     * than moving gigabytes onto the plan without a word.
     *
     * [pathIsExpensive] must be the cellular TRANSPORT or the metered flag, never
     * the metered flag alone — iOS learned this from a phone on an unlimited plan
     * reporting cellular as `expensive=false`, and Android has the same hole:
     * a carrier can mark an unlimited plan un-metered.
     */
    fun decision(pathIsExpensive: Boolean, pathIsConstrained: Boolean): Decision = when {
        pathIsExpensive -> Decision.Ask(Reason.MOBILE_DATA)
        pathIsConstrained -> Decision.Ask(Reason.LOW_DATA_MODE)
        else -> Decision.Download(DownloadNetwork.WIFI_ONLY)
    }

    /**
     * A running download's row label. null means "show progress as usual".
     *
     * Keyed on the downloader's own [parked] state and nothing else: reading the
     * live path here made the label flicker through every few-second route blip
     * that the downloader deliberately waits out.
     */
    fun waitingLabel(network: DownloadNetwork, parked: Boolean): String? =
        if (network == DownloadNetwork.WIFI_ONLY && parked) "waiting for wi-fi" else null

    fun title(reason: Reason): String = when (reason) {
        Reason.MOBILE_DATA -> "download over mobile data?"
        Reason.LOW_DATA_MODE -> "low data mode is on"
    }

    fun message(reason: Reason, model: LocalModel): String = when (reason) {
        Reason.MOBILE_DATA ->
            "${model.displayName} is a ${model.sizeLabel} download. It can wait until you're on wi-fi."
        Reason.LOW_DATA_MODE ->
            "${model.displayName} is a ${model.sizeLabel} download. It can wait until you're on an unrestricted connection."
    }
}

/**
 * A download that is waiting on the user's answer.
 *
 * iOS resolves this in the View and hands the observer straight in; here it takes
 * the two booleans instead, so the decision lives in the state holder and this
 * type stays in the pure module with the policy it belongs to. Same decision,
 * one fewer dependency, and the Android shape — the holder decides, the UI renders.
 */
data class CellularDownloadRequest(
    val model: LocalModel,
    val reason: CellularDownloadGate.Reason,
) {
    val id: String get() = model.id

    companion object {
        /**
         * Starts the download or produces a request, from the current path.
         * Returns null when it started — there is nothing to ask.
         */
        fun resolve(
            model: LocalModel,
            pathIsExpensive: Boolean,
            pathIsConstrained: Boolean,
            start: (LocalModel, DownloadNetwork) -> Unit,
        ): CellularDownloadRequest? =
            when (val d = CellularDownloadGate.decision(pathIsExpensive, pathIsConstrained)) {
                is CellularDownloadGate.Decision.Download -> { start(model, d.network); null }
                is CellularDownloadGate.Decision.Ask -> CellularDownloadRequest(model, d.reason)
            }
    }
}
