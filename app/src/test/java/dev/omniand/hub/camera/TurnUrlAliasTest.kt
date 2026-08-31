package dev.omniand.hub.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnUrlAliasTest {
    @Test
    fun `debug alias replaces only the host and preserves port and transport`() {
        assertEquals(
            "turn:10.0.2.2:3478?transport=udp",
            replaceTurnHost("turn:turn.dev.omniand.net:3478?transport=udp", "10.0.2.2"),
        )
        assertEquals(
            "turns:10.0.2.2:5349?transport=tcp",
            replaceTurnHost("turns:turn.dev.omniand.net:5349?transport=tcp", "10.0.2.2"),
        )
    }
}
