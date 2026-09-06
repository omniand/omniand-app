package dev.omniand.hub.server

import dev.omniand.hub.webapps.WebApp
import org.junit.Assert.assertTrue
import org.junit.Test

class CspBuilderTest {
    @Test
    fun onlyFilesMayFrameSameOriginContent() {
        val files = WebApp("files", "Files", "1.0.0", setOf("files.read"))
        val other = WebApp("test", "Test", "1.0.0", emptySet())

        assertTrue(CspBuilder.build(files).contains("frame-src 'self'"))
        assertTrue(CspBuilder.build(other).contains("frame-src 'none'"))
        assertTrue(CspBuilder.build(files).contains("object-src 'none'"))
    }
}
