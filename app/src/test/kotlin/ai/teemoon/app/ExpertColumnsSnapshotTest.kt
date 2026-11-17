// the maintainer, 2026-11-01, on the Pixel: in the trust screen's expert rung, the image rows and the miniaudit blocks had a
// right-hand column squeezed to one character a line. Real long image names, digests and audit titles at a Pixel 10
// Pro's 412 dp, at the default font and the largest (200 %).
package ai.teemoon.app

import ai.teemoon.confidential.AuditVerdictClass
import ai.teemoon.confidential.ImageEntry
import ai.teemoon.confidential.RunLink
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

private val launcher = ImageEntry("l", "nearaidev/compose-manager-launcher", ImageEntry.Kind.THIRD_PARTY,
    digest = "sha256:079a14b41deb11e0e27f9b69648e8b3d89bd6fb9ccf6071643b8f447fa415085",
    digestFull = "079a14b41deb11e0e27f9b69648e8b3d89bd6fb9ccf6071643b8f447fa415085")
private val proxy = ImageEntry("p", "docker.io/nearaidev/vllm-proxy-rs-with-a-long-deployment-name", ImageEntry.Kind.REGISTRY,
    digest = "sha256:a320bb4882341c8e19ef1944436f6591ed73303732009ed5db9f6db6c9da756a",
    digestFull = "a320bb4882341c8e19ef1944436f6591ed73303732009ed5db9f6db6c9da756a")
// the maintainer's screenshot, 2026-11-01: each sglang image's link row ran its last link down one letter a line.
private fun sglang(commit: String, digest: String) = ImageEntry("s-$commit", "docker.io/nearaidev/sglang", ImageEntry.Kind.REGISTRY,
    digest = "sha256:${digest.take(24)}…", digestFull = digest,
    links = listOf(
        RunLink("main → $commit", "https://github.com/nearai/sglang/commit/$commit"),
        RunLink("sigstore", "https://search.sigstore.dev/?hash=$digest"),
        RunLink("nearai/cvm-compose-files @ $commit", "https://github.com/nearai/cvm-compose-files/tree/$commit"),
        RunLink("audit · private", "https://audits.example/sglang-$commit"),
    ))
private val sglangs = listOf(
    sglang("678a6b5", "5f7ebd85c2a0d6c4e1b9f3a7d8e2c6b1a4f9e0d3c7b2a6f1e5d9c3b7a2f6e0d4"),
    sglang("29a7db9", "91fdff3cfa3543d72656b2368c7d8a0a83d95a0f1087378c897aa1537acdba56"),
    sglang("79de066", "6e035c8f1b2d4a7c9e0f3a5b8d1c4e7f0a3b6d9c2e5f8a1b4d7c0e3f6a9b2d5c"),
)

private const val VERDICT = "egress is limited to the inference port; the proxy logs request ids and token counts but no " +
    "message text; the launcher can restart services but cannot read **decrypted** traffic."

@Composable
private fun Rows() = TeemoonTheme { Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    ImageRowView(launcher, TrustRung.EXPERT)
    ImageRowView(proxy, TrustRung.EXPERT)
    sglangs.forEach { ImageRowView(it, TrustRung.EXPERT) }
    FableAuditBlock("https://audits.example/a", "fable miniaudit · deployment config", "allowed_envs, egress & telemetry reviewed · this measurement", VERDICT, AuditVerdictClass.QUALIFIED_PASS)
    FableAuditBlock("https://audits.example/b", "fable miniaudit · guest OS", "image + disk-encryption posture reviewed · this measurement", VERDICT, AuditVerdictClass.COMPROMISABLE)
    FableAuditBlock("https://audits.example/c", "fable miniaudit · recipe", "launch flags, logging & egress reviewed · this exact file", null, AuditVerdictClass.INCONCLUSIVE)
} } }

class ExpertColumnsSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 412, screenHeight = 1600, density = com.android.resources.Density.MEDIUM))
    @Test fun imagesAndAudits_defaultText() { paparazzi.snapshot { Rows() } }
}

class ExpertColumnsLargestTextSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 412, screenHeight = 4000, density = com.android.resources.Density.MEDIUM, fontScale = 2f))
    @Test fun imagesAndAudits_largestText() { paparazzi.snapshot { Rows() } }
}
