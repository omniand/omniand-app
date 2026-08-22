package dev.omniand.hub.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformAuthorityTest {
    @Test
    fun parsesExactHostnameAndPort() {
        assertEquals("localhost" to 8080, PlatformServer.parseAuthority("LOCALHOST:8080"))
        assertEquals(
            "messages.localhost" to 8080,
            PlatformServer.parseAuthority("messages.localhost:8080"),
        )
        assertEquals("::1" to 8080, PlatformServer.parseAuthority("[::1]:8080"))
    }

    @Test
    fun rejectsMalformedAuthorities() {
        assertNull(PlatformServer.parseAuthority(""))
        assertNull(PlatformServer.parseAuthority("localhost:not-a-port"))
        assertNull(PlatformServer.parseAuthority("localhost:8080/path"))
        assertNull(PlatformServer.parseAuthority("user@localhost:8080"))
        assertNull(PlatformServer.parseAuthority("::1:8080"))
    }
}
