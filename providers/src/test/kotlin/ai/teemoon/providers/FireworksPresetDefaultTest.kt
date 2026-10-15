// ports: teemoon/Providers/Provider+Presets.swift @ 54617ce (the Fireworks preset's default model)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class FireworksPresetDefaultTest {
    /** kimi-k2p6 left Fireworks serverless on 2026-10-15: a new Fireworks place must not start on a model that is gone. */
    @Test fun theFireworksPresetLeadsWithKimiK3() {
        assertEquals("accounts/fireworks/models/kimi-k3", Provider.fireworks.model)
    }
}
