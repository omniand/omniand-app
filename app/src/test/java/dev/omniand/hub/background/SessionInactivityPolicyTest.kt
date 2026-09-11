package dev.omniand.hub.background

import org.junit.Assert.*
import org.junit.Test

class SessionInactivityPolicyTest {
    @Test
    fun unattendedApprovalExpiresAndRepeatedAbsenceDoesNotExtendIt() {
        val policy = SessionInactivityPolicy()
        policy.start(100)
        policy.presence(0, 200_000)
        assertFalse(policy.expired(300_099))
        assertTrue(policy.expired(300_100))
        policy.stop()
        assertFalse(policy.expired(Long.MAX_VALUE))
    }

    @Test
    fun lastClientStartsDeadlineAndNewClientCancelsIt() {
        val policy = SessionInactivityPolicy()
        policy.start(0)
        policy.presence(2, 10)
        assertNull(policy.deadline())
        policy.presence(1, 20)
        assertFalse(policy.expired(1_000_000))
        policy.presence(0, 1_000_001)
        assertEquals(1_300_001L, policy.deadline())
        policy.presence(1, 1_200_000)
        assertFalse(policy.expired(1_400_000))
        policy.presence(0, 1_400_000)
        assertTrue(policy.expired(1_700_000))
    }
}
