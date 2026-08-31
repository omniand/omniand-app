package dev.omniand.hub.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnRenewalTest {
    @Test
    fun `refreshes normal credentials fifteen minutes early`() {
        assertEquals(45 * 60 * 1000L, TurnRenewal.delayMillis(0, 60 * 60 * 1000L))
    }

    @Test
    fun `refreshes short credentials one quarter early`() {
        assertEquals(45_000L, TurnRenewal.delayMillis(10_000, 70_000))
    }
}
