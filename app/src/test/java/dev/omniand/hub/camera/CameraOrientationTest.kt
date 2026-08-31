package dev.omniand.hub.camera

import android.view.OrientationEventListener
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun `maps physical orientation to CameraX target rotation`() {
        assertEquals(Surface.ROTATION_0, targetRotationForOrientation(0))
        assertEquals(Surface.ROTATION_0, targetRotationForOrientation(44))
        assertEquals(Surface.ROTATION_270, targetRotationForOrientation(45))
        assertEquals(Surface.ROTATION_270, targetRotationForOrientation(134))
        assertEquals(Surface.ROTATION_180, targetRotationForOrientation(135))
        assertEquals(Surface.ROTATION_180, targetRotationForOrientation(224))
        assertEquals(Surface.ROTATION_90, targetRotationForOrientation(225))
        assertEquals(Surface.ROTATION_90, targetRotationForOrientation(314))
        assertEquals(Surface.ROTATION_0, targetRotationForOrientation(315))
        assertEquals(Surface.ROTATION_0, targetRotationForOrientation(359))
    }

    @Test
    fun `ignores unavailable or invalid orientation`() {
        assertNull(targetRotationForOrientation(OrientationEventListener.ORIENTATION_UNKNOWN))
        assertNull(targetRotationForOrientation(360))
    }
}
