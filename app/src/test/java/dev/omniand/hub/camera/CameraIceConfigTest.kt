package dev.omniand.hub.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraIceConfigTest {
    @Test
    fun `local direct ICE advertises no empty server URI`() {
        val credentials = TurnCredentials(emptyList(), "", "", "", "", Long.MAX_VALUE)
        assertEquals(0, browserIceServers(credentials).length())
    }

    @Test
    fun `remote ICE advertises its TURN entry`() {
        val credentials =
            TurnCredentials(
                listOf("turn:relay.example:3478"),
                "android",
                "android-secret",
                "browser",
                "browser-secret",
                1234,
            )
        val server = browserIceServers(credentials).getJSONObject(0)
        assertEquals("turn:relay.example:3478", server.getJSONArray("urls").getString(0))
        assertEquals("browser", server.getString("username"))
    }
}
