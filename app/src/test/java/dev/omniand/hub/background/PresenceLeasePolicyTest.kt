package dev.omniand.hub.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceLeasePolicyTest {
    @Test
    fun `background preference transitions preserve explicit opt-in state`() {
        assertEquals(
            BackgroundHostingPreferenceTransition.ENABLE,
            BackgroundHostingPreferenceTransition.from(previous = false, requested = true),
        )
        assertEquals(
            BackgroundHostingPreferenceTransition.DISABLE,
            BackgroundHostingPreferenceTransition.from(previous = true, requested = false),
        )
        assertEquals(
            BackgroundHostingPreferenceTransition.KEEP_ENABLED,
            BackgroundHostingPreferenceTransition.from(previous = true, requested = true),
        )
        assertEquals(
            BackgroundHostingPreferenceTransition.KEEP_DISABLED,
            BackgroundHostingPreferenceTransition.from(previous = false, requested = false),
        )
    }

    @Test
    fun `lease requires opt-in running service and at least one client`() {
        val policy = PresenceLeasePolicy()
        assertFalse(policy.shouldHold(enabled = true, serviceRunning = true))
        policy.connected()
        assertFalse(policy.shouldHold(enabled = false, serviceRunning = true))
        assertFalse(policy.shouldHold(enabled = true, serviceRunning = false))
        assertTrue(policy.shouldHold(enabled = true, serviceRunning = true))
    }

    @Test
    fun `references remain active until the final disconnect`() {
        val policy = PresenceLeasePolicy()
        policy.connected()
        policy.connected()
        assertEquals(2, policy.connectedClients())
        policy.disconnected()
        assertEquals(1, policy.connectedClients())
        assertTrue(policy.shouldHold(enabled = true, serviceRunning = true))
        policy.disconnected()
        policy.disconnected()
        assertEquals(0, policy.connectedClients())
        assertFalse(policy.shouldHold(enabled = true, serviceRunning = true))
    }
}
