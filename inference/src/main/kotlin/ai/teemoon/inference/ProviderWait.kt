// ports: teemoon/Inference/ProviderWait.swift @ v1.0.6
package ai.teemoon.inference

/**
 * A request that has gone out and has no response headers yet. Says only that: no "queue" or "busy" unless the
 * provider tells us so. Fireworks held requests 2–69 s before headers and NVIDIA's free tier 4–5 min (2026-10-19),
 * with "thinking..." on screen the whole time.
 */
data class ProviderWait(val turn: Int, val sentAtMs: Long, val providerName: String) {
    /**
     * Seconds this request has waited, once past the threshold; null before. Decides WHEN the chip says so; the number
     * on it is the whole turn's (the maintainer).
     */
    fun shownSeconds(nowMs: Long): Int? {
        val waited = nowMs - sentAtMs
        return if (waited >= THRESHOLD_MS) (waited / 1000).toInt() else null
    }

    companion object {
        /** Below this a missing response is ordinary latency. */
        const val THRESHOLD_MS = 5_000L

        fun label(providerName: String, seconds: Int) = "waiting for $providerName\u2026 ${seconds}s"
    }
}
