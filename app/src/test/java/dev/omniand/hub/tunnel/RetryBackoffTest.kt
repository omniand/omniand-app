package dev.omniand.hub.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryBackoffTest {
    @Test
    fun capsExponentialBackoffAtSixtySecondsAndUsesFullJitter() {
        assertEquals(1_000, RetryBackoff.maximumMillis(0))
        assertEquals(32_000, RetryBackoff.maximumMillis(5))
        assertEquals(60_000, RetryBackoff.maximumMillis(6))
        assertEquals(60_000, RetryBackoff.maximumMillis(100))
        repeat(100) { assertTrue(RetryBackoff.fullJitterMillis(4) in 0..16_000) }
    }
}
