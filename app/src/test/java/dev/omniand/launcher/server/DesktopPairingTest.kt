package dev.omniand.launcher.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPairingTest {
    @After fun reset() = DesktopPairing.reset()

    /** Verifies approval is single-use and produces only a server-recognized session token. */
    @Test
    fun approvalCreatesAClaimableSession() {
        val request = DesktopPairing.create("192.0.2.4", "Browser")
        assertEquals(
            request.id,
            DesktopPairing.requestId("${DesktopPairing.REQUEST_COOKIE}=${request.id}"),
        )
        assertTrue(DesktopPairing.decide(request.id, approved = true))

        val claim = DesktopPairing.claim(request.id)

        assertEquals(DesktopPairing.Decision.APPROVED, claim.decision)
        assertTrue(
            DesktopPairing.verify("other=x; ${DesktopPairing.SESSION_COOKIE}=${claim.token}")
        )
        assertFalse(DesktopPairing.verify("${DesktopPairing.SESSION_COOKIE}=invalid"))
        assertEquals(DesktopPairing.Decision.DENIED, DesktopPairing.claim(request.id).decision)
    }

    @Test
    fun denialNeverCreatesASession() {
        val request = DesktopPairing.create("192.0.2.5", "Browser")
        assertTrue(DesktopPairing.decide(request.id, approved = false))

        val claim = DesktopPairing.claim(request.id)

        assertEquals(DesktopPairing.Decision.DENIED, claim.decision)
        assertNull(claim.token)
        assertFalse(DesktopPairing.verify(null))
    }

    @Test
    fun rateLimitsSeparateRequestsFromTheSamePeer() {
        val first = DesktopPairing.create("192.0.2.6", "Browser A")

        assertEquals(first, DesktopPairing.pending(first.id))
        assertTrue(
            runCatching { DesktopPairing.create("192.0.2.6", "Browser B") }.exceptionOrNull()
                is DesktopPairing.Throttled
        )
        assertEquals(listOf(first), DesktopPairing.pending())
    }
}
