// ports: teemoon/App/NetworkPathObserver.swift @ v1.0.4 — REBUILD over ConnectivityManager
//        (+isMobileData / +isConstrained, the two the download gate switches on)
package ai.teemoon.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether a network path is up. Optimistic at start, as on iOS: the first send should not be gated on a callback that has not fired yet. */
class NetworkPathObserver private constructor(initial: Boolean, metered: Boolean = false, cellular: Boolean = false, constrained: Boolean = false) {
    private val _isSatisfied = MutableStateFlow(initial)
    val isSatisfied: StateFlow<Boolean> = _isSatisfied

    private val _isMetered = MutableStateFlow(metered)
    private val _isCellular = MutableStateFlow(cellular)
    private val _isConstrained = MutableStateFlow(constrained)

    /**
     * THE CELLULAR TRANSPORT OR THE METERED FLAG, never the flag alone.
     *
     * iOS learned this from a phone on an unlimited plan reporting cellular as
     * `expensive=false`, so neither the prompt nor the park fired — and Android
     * has the same hole from the other side: a carrier may mark an unlimited
     * plan NOT_METERED, and a personal hotspot is metered without being
     * cellular. Either signal alone misses a real case, so it is the union.
     */
    val isMobileData: Boolean get() = _mobileData.value

    /** [isMobileData] as a flow: the downloader parks a wi-fi-only job on it and resumes when it drops. */
    private val _mobileData = MutableStateFlow(cellular || metered)
    val mobileData: StateFlow<Boolean> = _mobileData
    /**
     * Debug-only (journeys): pin the path to mobile data or wi-fi, or null to follow the system again. A pinned path is
     * also unconstrained: the dev phone runs with Data Saver on, and every download journey met the low-data prompt.
     */
    private var override: Boolean? = null
    fun uiTestOverrideMobileData(value: Boolean?) { override = value; recompute(); if (value != null) _isConstrained.value = false }
    private fun recompute() { _mobileData.value = override ?: (_isCellular.value || _isMetered.value) }

    /** Debug-only (journeys, C9): pin the path offline or online, or null to follow the system again. A callback cannot undo it. */
    private var satisfiedOverride: Boolean? = null
    private var systemSatisfied = initial
    fun uiTestOverrideSatisfied(value: Boolean?) { satisfiedOverride = value; _isSatisfied.value = value ?: systemSatisfied }
    private fun setSatisfied(value: Boolean) { systemSatisfied = value; _isSatisfied.value = satisfiedOverride ?: value }

    /** Data Saver — the user asked for less traffic, on any interface. Android's Low Data Mode. */
    val isConstrained: Boolean get() = _isConstrained.value

    constructor(context: Context) : this(true) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { setSatisfied(true) }
                override fun onLost(network: Network) { setSatisfied(false) }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    setSatisfied(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    _isMetered.value = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    _isCellular.value = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    recompute()
                    // Data Saver is a per-app restriction rather than a path property, so it is read from the manager —
                    // and it only ever applies to METERED networks (Android restricts nothing on unmetered wi-fi).
                    _isConstrained.value = override == null && dataSaverConstrains(
                        dataSaverOn = manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                }
            })
        }
    }

    companion object {
        /**
         * iOS's Low Data Mode is set PER NETWORK, so on iOS "constrained" is a fact about the path. Android's Data Saver is
         * one switch that the system applies only on metered networks — reading the switch alone put the low-data prompt
         * in front of every download on home wi-fi (the dev phone runs with it on, 2026-10-05). The path is constrained
         * when the switch is on AND the path is metered.
         */
        fun dataSaverConstrains(dataSaverOn: Boolean, metered: Boolean): Boolean = dataSaverOn && metered

        fun simulating(
            satisfied: Boolean, metered: Boolean = false,
            cellular: Boolean = false, constrained: Boolean = false,
        ) = NetworkPathObserver(satisfied, metered, cellular, constrained)
    }
}
