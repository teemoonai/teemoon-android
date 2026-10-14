// ports: teemoonTests/LocalModelDownloaderTests.swift @ v1.0.4 (the CellularDownloadGate suite;
//        the isMobileData case lives in app/ with the observer it tests)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellularDownloadGateTest {

    /**
     * On wi-fi a download starts without a word — and stays WIFI_ONLY, so leaving
     * the house pauses it instead of moving it onto the plan.
     */
    @Test fun wifiStartsSilentlyAsWifiOnly() {
        assertEquals(CellularDownloadGate.Decision.Download(DownloadNetwork.WIFI_ONLY),
            CellularDownloadGate.decision(pathIsExpensive = false, pathIsConstrained = false))
    }

    @Test fun mobileDataAsksFirst() {
        assertEquals(CellularDownloadGate.Decision.Ask(CellularDownloadGate.Reason.MOBILE_DATA),
            CellularDownloadGate.decision(pathIsExpensive = true, pathIsConstrained = false))
    }

    @Test fun lowDataModeAsksFirst() {
        assertEquals(CellularDownloadGate.Decision.Ask(CellularDownloadGate.Reason.LOW_DATA_MODE),
            CellularDownloadGate.decision(pathIsExpensive = false, pathIsConstrained = true))
    }

    /** Both at once is still a mobile-data question; that is the cost the user recognises. */
    @Test fun mobileDataOutranksLowDataMode() {
        assertEquals(CellularDownloadGate.Decision.Ask(CellularDownloadGate.Reason.MOBILE_DATA),
            CellularDownloadGate.decision(pathIsExpensive = true, pathIsConstrained = true))
    }

    /**
     * Only the downloader's parked verdict shows the label — a live path read
     * here flickered through every few-second route blip.
     */
    @Test fun onlyAParkedWifiOnlyTransferIsWaiting() {
        assertEquals("waiting for wi-fi",
            CellularDownloadGate.waitingLabel(DownloadNetwork.WIFI_ONLY, parked = true))
        assertNull(CellularDownloadGate.waitingLabel(DownloadNetwork.WIFI_ONLY, parked = false))
        assertNull(CellularDownloadGate.waitingLabel(DownloadNetwork.ANY, parked = true))
    }

    /** The alert names the model and its size: the question is "this much, now?" */
    @Test fun alertCopyNamesTheCost() {
        val e2b = LocalModelCatalog.all[0]
        val message = CellularDownloadGate.message(CellularDownloadGate.Reason.MOBILE_DATA, e2b)
        assertTrue(message.contains(e2b.displayName))
        assertTrue(message.contains(e2b.sizeLabel))
        assertEquals("download over mobile data?",
            CellularDownloadGate.title(CellularDownloadGate.Reason.MOBILE_DATA))
    }

    /** "2.4 GB" over a gigabyte, "512 MB" under it. */
    @Test fun sizeLabelPicksItsUnit() {
        assertEquals("2.4 GB", LocalModelCatalog.all[0].copy(sizeMB = 2468).sizeLabel)
        assertEquals("512 MB", LocalModelCatalog.all[0].copy(sizeMB = 512).sizeLabel)
        assertEquals("1.0 GB", LocalModelCatalog.all[0].copy(sizeMB = 1024).sizeLabel)
    }

    // MARK: - CellularDownloadRequest

    /** On an unmetered path there is nothing to ask: it starts, and returns null. */
    @Test fun anUnmeteredPathStartsWithoutAsking() {
        var started: Pair<LocalModel, DownloadNetwork>? = null
        val req = CellularDownloadRequest.resolve(
            LocalModelCatalog.all[0], pathIsExpensive = false, pathIsConstrained = false,
        ) { m, n -> started = m to n }
        assertNull(req)
        assertEquals(DownloadNetwork.WIFI_ONLY, started!!.second)
    }

    /** On mobile data nothing starts until the user answers. */
    @Test fun mobileDataStartsNothingYet() {
        var started = false
        val req = CellularDownloadRequest.resolve(
            LocalModelCatalog.all[0], pathIsExpensive = true, pathIsConstrained = false,
        ) { _, _ -> started = true }
        assertEquals(CellularDownloadGate.Reason.MOBILE_DATA, req!!.reason)
        assertEquals(LocalModelCatalog.all[0].id, req.id)
        org.junit.Assert.assertFalse("the bytes must not move before the answer", started)
    }
}
