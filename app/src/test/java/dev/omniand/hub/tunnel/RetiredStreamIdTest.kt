package dev.omniand.hub.tunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredStreamIdTest {
    @Test
    fun `only previously opened positive stream ids are retired`() {
        assertTrue(isRetiredStreamId(1, 3))
        assertTrue(isRetiredStreamId(3, 3))
        assertFalse(isRetiredStreamId(0, 3))
        assertFalse(isRetiredStreamId(4, 3))
        assertFalse(isRetiredStreamId(1, 0))
    }
}
