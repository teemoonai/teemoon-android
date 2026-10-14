// Paparazzi baselines for the screens with layout risk (CONTRIBUTING.md: snapshots for anything with layout
// risk). Record with `./gradlew recordPaparazziDebug`, verify with `verifyPaparazziDebug`. The narrow
// device is deliberate: a 320 px screen is what squeezed the chip out of the chat bar.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import ai.teemoon.confidential.EncryptedModelCTA
import ai.teemoon.providers.EndpointDirectory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier

class TrustScreenSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 1400, density = com.android.resources.Density.MEDIUM))

    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun session(provider: Provider?) = ConfidentialSession(scope, { provider }, services = ConfidentialSession.Services(skipLiveAttestation = true))

    private fun seeded(): Pair<ConfidentialSession, Provider> {
        val preview = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = session(p); s.attestation = preview; s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.NotPerformed
        return s to p
    }

    @Test fun trustScreen_everyday_seededRecord() {
        val (s, p) = seeded()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustScreen_proof_seededRecord() {
        val (s, p) = seeded()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}, onCopyScript = {}, initialRung = TrustRung.EXPERT) } } }
    }

    /**
     * The expert rung's last two sections at 320 px — "what you still trust" with its three symbols, and the script card,
     * whose header holds a middle-truncating filename, the language pill and the copy capsule on one line (iOS
     * SelfVerifyScriptCard: the filename yields, the pill and the button never wrap).
     */
    @Test fun expertTail_residualTrustAndScriptCard() {
        val (s, p) = seeded()
        val snap = trustSnapshot(s, p)
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            EvidenceCard("what you still trust", "verification shrinks what you trust — it can't take it to zero. this is the part no proof removes.") {
                snap.expert.residualExpert.forEach { (icon, text) -> TrustRow(icon, text) }
            }
            ReverifySection(snap, onCopyScript = {})
        } } } }
    }

    @Test fun trustScreen_verifying() {
        val p = Provider.nearAI
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustScreen_notAttestable() {
        val p = Provider(id = "x", name = "acme", endpoint = "https://api.acme.example/v1", model = "acme/big-model")
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {}) } } }
    }

    /**
     * The not-attestable screen WITH its way out — the state a real user is in on Fireworks, NVIDIA or
     * OpenRouter, and the one `trustScreen_notAttestable` above does not cover because it passes no CTA.
     *
     * Both halves are here: the expert reason card, and the green offer under it. Neither had a pixel record
     * until 2026-09-21, which is part of why `offersEncryptedModels` could be wrong for weeks.
     */
    @Test fun trustScreen_notAttestable_withTheWayOut() {
        val p = Provider(id = "f", name = "fireworks", endpoint = "https://api.fireworks.ai/inference/v1", model = "kimi-k2p6")
        paparazzi.snapshot {
            TeemoonTheme {
                Surface {
                    TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {},
                        encryptedModelCTA = EncryptedModelCTA.resolve(Provider.nearAI, hasKey = true),
                        onEncryptedModelCTA = {}, initialRung = TrustRung.EXPERT)
                }
            }
        }
    }

    @Test fun trustScreen_selfHosted() {
        val p = Provider(id = "h", name = "home", endpoint = "http://192.168.1.20:11434/v1", model = "llama3", requiresAPIKey = false)
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {}) } } }
    }

    /** The chat bar on a narrow screen: the chip must be visible on its own row under the title. */
    @Test fun chatScreen_chipOnNarrowScreen() {
        val (s, p) = seeded()
        val vm = ChatViewModel(provider = { p })
        paparazzi.snapshot { TeemoonTheme { Surface { ChatScreen(focusOnEmpty = false, vm = vm, title = p.name, trust = trustSnapshot(s, p), hasProvider = true) } } }
    }
}
