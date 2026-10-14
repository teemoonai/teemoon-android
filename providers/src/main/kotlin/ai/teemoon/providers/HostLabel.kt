// ports: teemoon/Providers/HostLabel.swift @ v1.0.1
package ai.teemoon.providers

/**
 * How a hostname becomes a place name. Lives with Provider so persistence and
 * the Where rows can name a machine without importing Presentation.
 *
 * A tailnet FQDN is mostly suffix: ringzero.tailnet-name.ts.net → "ringzero".
 * An IPv4 literal has no name to take, so it stays whole.
 */
object HostLabel {
    /** First DNS label of a hostname; the address itself for an IPv4 literal. */
    fun friendly(host: String): String {
        // Swift String.split(separator:) omits empty subsequences.
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) return host
        return parts.firstOrNull() ?: host
    }
}
