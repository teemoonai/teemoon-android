package ai.teemoon.app

// android-only: Data Saver vs iOS's per-network Low Data Mode (NetworkPathObserver.dataSaverConstrains).

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPathObserverTest {
    @Test fun dataSaverDoesNotConstrainUnmeteredWifi() {
        assertFalse("Data Saver on, home wi-fi: Android restricts nothing here, and neither may the download gate",
            NetworkPathObserver.dataSaverConstrains(dataSaverOn = true, metered = false))
    }
    @Test fun dataSaverConstrainsAMeteredPath() {
        assertTrue(NetworkPathObserver.dataSaverConstrains(dataSaverOn = true, metered = true))
        assertFalse(NetworkPathObserver.dataSaverConstrains(dataSaverOn = false, metered = true))
    }
}
