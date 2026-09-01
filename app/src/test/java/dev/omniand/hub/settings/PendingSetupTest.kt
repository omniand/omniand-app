package dev.omniand.hub.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSetupTest {
    @Test
    fun `automatic setup is handed off only once`() {
        var pending = true

        assertTrue(PendingSetup.consume(isPending = { pending }, clear = { pending = false }))
        assertFalse(PendingSetup.consume(isPending = { pending }, clear = { pending = false }))
    }

    @Test
    fun `setup is not cleared when none is pending`() {
        var cleared = false

        assertFalse(PendingSetup.consume(isPending = { false }, clear = { cleared = true }))
        assertFalse(cleared)
    }
}
