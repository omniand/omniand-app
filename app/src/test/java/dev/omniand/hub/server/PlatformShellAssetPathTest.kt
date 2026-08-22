package dev.omniand.hub.server

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformShellAssetPathTest {
    @Test
    fun `shell routes resolve to the SPA entry document`() {
        assertEquals("/", PlatformServer.platformShellAssetPath("/discover"))
        assertEquals("/", PlatformServer.platformShellAssetPath("/discover/messages"))
    }

    @Test
    fun `assets and unrelated paths retain their requested path`() {
        assertEquals("/assets/index.js", PlatformServer.platformShellAssetPath("/assets/index.js"))
        assertEquals("/missing", PlatformServer.platformShellAssetPath("/missing"))
        assertEquals(
            "/discover/messages/extra",
            PlatformServer.platformShellAssetPath("/discover/messages/extra"),
        )
    }
}
