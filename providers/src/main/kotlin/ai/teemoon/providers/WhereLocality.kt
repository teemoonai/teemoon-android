// ports: teemoon/Providers/WhereLocality.swift @ v1.0.4
package ai.teemoon.providers

/**
 * Where inference runs, as a continuum — not a vendor list. Pure classification
 * over Provider; lives with Provider so the store can filter recents without
 * importing a view.
 */
enum class WhereLocality {
    /** Weights on the phone, run by LiteRT. Nothing leaves the device. */
    PHONE,
    /** A computer the user owns (LAN / Tailscale / Ollama). */
    HOME,
    /** Cloud API (near.ai, BYOK, …). */
    CLOUD,
    ;

    /** Phone above home above cloud: the ready list's tier order. */
    val rank: Int get() = ordinal

    val shortLabel: String get() = when (this) {
        PHONE -> "phone"
        HOME -> "home"
        CLOUD -> "cloud"
    }

    val systemImage: String get() = when (this) {
        PHONE -> "iphone"
        HOME -> "desktopcomputer"
        CLOUD -> "cloud"
    }

    companion object {
        /**
         * Classifies a saved provider: on-device → phone, a host on your own
         * network → home, everything else → cloud.
         *
         * [Provider.isLocal] MUST be checked before [Provider.isSelfHosted], and
         * cannot be replaced by it. `Provider.local` carries `endpoint: "on-device"`,
         * which is not a URL, so it has no host and `isSelfHosted` is false — an
         * on-device model tested as CLOUD, the most private place teemoon can run
         * rated as the least.
         */
        fun of(provider: Provider): WhereLocality {
            if (provider.isLocal) return PHONE
            return if (provider.isSelfHosted) HOME else CLOUD
        }

        /**
         * The setups "places & keys" actually MANAGES: home machines and cloud
         * keys, never the phone.
         */
        fun managed(inProviders: List<Provider>): List<Provider> =
            inProviders.filter { of(it) != PHONE }
    }
}
