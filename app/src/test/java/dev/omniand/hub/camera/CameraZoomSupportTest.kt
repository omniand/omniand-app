package dev.omniand.hub.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraZoomSupportTest {
    @Test
    fun `emulator camera zoom is treated as unreliable`() {
        assertFalse(hasReliableCameraZoom("goldfish"))
        assertFalse(hasReliableCameraZoom("ranchu"))
    }

    @Test
    fun `physical camera zoom remains available`() {
        assertTrue(hasReliableCameraZoom("qcom"))
        assertTrue(hasReliableCameraZoom("exynos"))
    }
}
