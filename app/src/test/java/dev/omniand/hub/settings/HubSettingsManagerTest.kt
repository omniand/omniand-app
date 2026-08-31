package dev.omniand.hub.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class HubSettingsManagerTest {
    @Test
    fun `permission setup exposes only the documented groups`() {
        assertEquals(
            setOf(
                "sms",
                "contacts",
                "media",
                "camera",
                "notifications",
                "wrapper-installation",
                "background-hosting",
            ),
            HubSettingsManager.permissionGroups,
        )
    }
}
