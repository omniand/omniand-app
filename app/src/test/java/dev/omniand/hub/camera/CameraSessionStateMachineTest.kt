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

    @Test
    fun `approved remote request takes over a local stream but denial preserves it`() {
        machine.begin("local-request", "local", "", "Phone", 60_000)
        machine.decide("local-request", true)

        val first = machine.begin("remote-1", "remote-1", "link", "Firefox", 60_000, true)
        assertNotNull(first)
        assertEquals("local", first?.incumbent?.viewerId)
        assertTrue(machine.decide("remote-1", false) is CameraSessionStateMachine.State.Streaming)
        assertEquals("local", (machine.state as CameraSessionStateMachine.State.Streaming).viewerId)

        machine.begin("remote-2", "remote-2", "link", "Firefox", 60_000, true)
        val remote = machine.decide("remote-2", true) as CameraSessionStateMachine.State.Streaming
        assertEquals("remote-2", remote.viewerId)
        assertEquals("link", remote.publicLinkId)
    }

    @Test
    fun `remote disconnect or expiry during takeover preserves the local stream`() {
        machine.begin("local-request", "local", "", "Phone", 60_000)
        machine.decide("local-request", true)
        machine.begin("remote", "remote", "link", "Firefox", 60_000, true)

        assertTrue(machine.disconnect("remote"))
        assertEquals("local", (machine.state as CameraSessionStateMachine.State.Streaming).viewerId)

        machine.begin("remote-2", "remote-2", "link", "Firefox", 60_000, true)
        now += 60_000
        assertNotNull(machine.expire())
        assertEquals("local", (machine.state as CameraSessionStateMachine.State.Streaming).viewerId)
    }

    @Test
    fun `leaving the phone UI stops only a local stream`() {
        machine.begin("local-request", "local", "", "Phone", 60_000)
        machine.decide("local-request", true)
        assertTrue(machine.stopLocal())
        assertEquals(CameraSessionStateMachine.State.Idle, machine.state)

        machine.begin("remote-request", "remote", "link", "Firefox", 60_000)
        machine.decide("remote-request", true)
        assertFalse(machine.stopLocal())
        assertEquals(
            "remote",
            (machine.state as CameraSessionStateMachine.State.Streaming).viewerId,
        )
    }
}
