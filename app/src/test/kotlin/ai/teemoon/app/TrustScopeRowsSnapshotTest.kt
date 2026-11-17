// near-trust-scope (the maintainer 2026-11-06; iOS aa84e369/711e006b): the model enclave group on the expert rung with trust scope's
// rows — (a) atop the recipe card, (b) under the binding, the guest OS's " · in the quote ✓", the compose manager's
// running line, and (c)'s "running now" section — in each state, at 320 dp and at 200% on 412 dp.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.BootLogReplay
import ai.teemoon.confidential.ComposeFileCheck
import ai.teemoon.confidential.ComposeFilePair
import ai.teemoon.confidential.ComposeManagerSeat
import ai.teemoon.confidential.DeployLogBinding
import ai.teemoon.confidential.EnclaveGrouping
import ai.teemoon.confidential.ImageRef
import ai.teemoon.confidential.LauncherIndex
import ai.teemoon.confidential.ManifestProvenance
import ai.teemoon.confidential.ModelLayerVerification
import ai.teemoon.confidential.NearTrustScope
import ai.teemoon.confidential.RunningService
import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test

internal object TrustScopeFixtures {
    private val d = "6e035c8f".padEnd(64, 'e'); private val r = "a0c07308".padEnd(64, 'a')
    private fun svc(name: String, file: String, commit: String, tag: String? = "v0.0.521") =
        RunningService(name, file, "s", commit, tag, "2026-10-29T00:11:24.857389316+00:00")
    private val v12 = "prod/GLM-5.3-Flash-SGL-TP2x4-W4AFP8-V12Q8.yaml"; private val canary = "prod/GLM-5.3-Flash-SGL-TP2x4-W4AFP8-Q8Canary.yaml"
    private val base = "prod/GLM-5.3-Flash-SGL-TP2x4-W4AFP8.yaml"; private val rotate = "prod/rotate-compose-manager-token.yaml"
    private val services = listOf(svc("proxy-glm53", v12, "7ee4a4c741259d0c"), svc("model-sg-glm53-w4afp8-tp2-r4", canary, "7ee4a4c741259d0c"),
        svc("nginx", base, "cd94860052fede8d"), svc("", rotate, "3c2f033b8d1e2a44", tag = null).copy(undecidable = true))
    private fun pair(s: RunningService, check: ComposeFileCheck) = ComposeFilePair(s.commit!!, s.file!!, "s", s.since, listOf(s.service), check)

    val proven = NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, ComposeManagerSeat(d, d, ComposeManagerSeat.Source.DEPLOY_LOG), services,
        LauncherIndex.NotChecked, listOf(pair(services[0], ComposeFileCheck.Matches), pair(services[1], ComposeFileCheck.Unpublished),
            pair(services[2], ComposeFileCheck.Inconclusive), pair(services[3], ComposeFileCheck.NotFetched)))
    val notBound = NearTrustScope(DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG), BootLogReplay.Absent,
        ComposeManagerSeat(d, null, ComposeManagerSeat.Source.DECLARED_ONLY), emptyList(), LauncherIndex.NotChecked)
    val unchecked = NearTrustScope(DeployLogBinding.Unchecked, BootLogReplay.Matches, ComposeManagerSeat(d, null, ComposeManagerSeat.Source.DECLARED_ONLY),
        emptyList(), LauncherIndex.NotChecked)
    val broken = NearTrustScope(DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH), BootLogReplay.Mismatch(BootLogReplay.What.RTMR3),
        ComposeManagerSeat(d, r, ComposeManagerSeat.Source.DEPLOY_LOG), services.take(1), LauncherIndex.NotChecked,
        listOf(pair(services[0], ComposeFileCheck.Mismatch("0".repeat(64)))))

    /** The preview record's model enclave group, with a compose-manager image so its row shows. */
    fun group(bound: Boolean) = EnclaveGrouping(AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()),
        ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/compose-manager", d, hosts = listOf("model")),
            ImageRef("nearaidev/cloud-api", "a".repeat(64), hosts = listOf("model"))), emptyList()),
        null, ModelLayerVerification.VERIFIED, ai.teemoon.confidential.AuditIndex(fixedIndex = null), actionLogBound = bound).groups().first { it.primary }
}

@androidx.compose.runtime.Composable
private fun Rows(scope: NearTrustScope) = TeemoonTheme { Surface {
    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        EnclaveGroupView(TrustScopeFixtures.group(bound = scope.deployLogBinding == DeployLogBinding.Bound), TrustRung.EXPERT, ai.teemoon.confidential.AuditIndex(fixedIndex = null), scope = scope)
    } } }

class TrustScopeRowsSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 2000, density = com.android.resources.Density.MEDIUM))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }
    @Test fun trustScope_proven() = paparazzi.snapshot { Rows(TrustScopeFixtures.proven) }
    @Test fun trustScope_notBound() = paparazzi.snapshot { Rows(TrustScopeFixtures.notBound) }
    @Test fun trustScope_unchecked() = paparazzi.snapshot { Rows(TrustScopeFixtures.unchecked) }
    @Test fun trustScope_broken() = paparazzi.snapshot { Rows(TrustScopeFixtures.broken) }
}

class TrustScopeRowsLargestTextSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 412, screenHeight = 3200, density = com.android.resources.Density.MEDIUM, fontScale = 2f))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }
    @Test fun trustScope_proven_largestText() = paparazzi.snapshot { Rows(TrustScopeFixtures.proven) }
    @Test fun trustScope_broken_largestText() = paparazzi.snapshot { Rows(TrustScopeFixtures.broken) }
}
