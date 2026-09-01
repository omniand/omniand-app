package dev.omniand.hub.camera

import androidx.camera.core.CameraControl
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlFailureTest {
    @Test
    fun `superseded CameraX operation is not fatal`() {
        val cancellation = CameraControl.OperationCanceledException("superseded")

        assertTrue(isSupersededCameraControlFailure(ExecutionException(cancellation)))
        assertTrue(isSupersededCameraControlFailure(CancellationException("cancelled")))
    }

    @Test
    fun `genuine camera failure remains fatal`() {
        assertFalse(isSupersededCameraControlFailure(ExecutionException(IllegalStateException())))
    }
}
