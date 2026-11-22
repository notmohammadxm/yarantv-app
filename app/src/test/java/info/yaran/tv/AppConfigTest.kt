package info.yaran.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {
    @Test
    fun startPageUsesTheConfiguredAdminUrl() {
        assertEquals("https://yaran.info/tv/admin", AppConfig.START_URL)
    }
}
