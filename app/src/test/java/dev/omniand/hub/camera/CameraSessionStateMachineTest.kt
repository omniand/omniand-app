package dev.omniand.hub.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionStateMachineTest {
    private var now = 1_000L
    private val machine = CameraSessionStateMachine { now }

    @Test
    fun `moves through approval and streaming and excludes a second viewer`() {
        val pending = machine.begin("request", "viewer", "link", "Firefox", 60_000)
        assertNotNull(pending)
        assertNull(machine.begin("other", "second", "link", "Chromium", 60_000))
        assertTrue(machine.decide("request", true) is CameraSessionStateMachine.State.Streaming)
        assertNull(machine.begin("other", "second", "link", "Chromium", 60_000))
        assertTrue(machine.disconnect("viewer"))
        assertEquals(CameraSessionStateMachine.State.Idle, machine.state)
    }

    @Test
    fun `expires at the real deadline and rejects stale decisions`() {
        machine.begin("request", "viewer", "link", "Firefox", 60_000)
        now += 60_000
        assertNotNull(machine.expire())
        assertEquals(CameraSessionStateMachine.State.Idle, machine.state)
        assertNull(machine.decide("request", true))
    }

    @Test
    fun `denial and teardown are idempotent`() {
        machine.begin("request", "viewer", "link", "Firefox", 60_000)
        assertEquals(CameraSessionStateMachine.State.Idle, machine.decide("request", false))
        assertFalse(machine.stop())
        assertFalse(machine.stop())
    }
}
