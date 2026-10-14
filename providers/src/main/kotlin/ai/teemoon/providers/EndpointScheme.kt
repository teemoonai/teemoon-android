// ports: teemoon/Views/Settings/AddEditProviderView.swift @ v1.0.4 (EndpointScheme, splitEndpoint — the two
//        pieces of the view file that are policy, not layout)
package ai.teemoon.providers

enum class EndpointScheme(val prefix: String) {
    HTTPS("https://"), HTTP("http://");
    val isSecure: Boolean get() = this == HTTPS

    companion object {
        /** A stored endpoint split into its scheme chip and the host+path field; a bare host reads as https. */
        fun split(endpoint: String): Pair<EndpointScheme, String> = when {
            endpoint.startsWith("http://") -> HTTP to endpoint.removePrefix("http://")
            endpoint.startsWith("https://") -> HTTPS to endpoint.removePrefix("https://")
            else -> HTTPS to endpoint
        }
    }
}
