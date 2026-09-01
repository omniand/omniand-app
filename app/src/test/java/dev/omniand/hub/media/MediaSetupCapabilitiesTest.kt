package dev.omniand.hub.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSetupCapabilitiesTest {
    @Test
    fun `only newly introduced media capabilities require automatic setup`() {
        val existing = setOf("media.read", "media.write")

        assertTrue(newMediaCapabilities(existing, existing).isEmpty())
        assertEquals(setOf("media.write"), newMediaCapabilities(setOf("media.read"), existing))
        assertTrue(newMediaCapabilities(existing, setOf("camera.capture")).isEmpty())
    }
}
