package dev.omniand.hub.camera

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSignalValidatorTest {
    @Test
    fun `accepts only the versioned browser vocabulary`() {
        assertNull(
            CameraSignalValidator.error(
                JSONObject().put("version", 1).put("type", "offer").put("sdp", "v=0")
            )
        )
        assertNull(
            CameraSignalValidator.error(
                JSONObject().put("version", 1).put("type", "control").put("flashMode", "auto")
            )
        )
        assertNull(
            CameraSignalValidator.error(
                JSONObject()
                    .put("version", 1)
                    .put("type", "capture-photo")
                    .put("requestId", "shot_1")
            )
        )
        assertEquals(
            "invalid-type",
            CameraSignalValidator.error(JSONObject().put("version", 1).put("type", "ready")),
        )
        assertEquals(
            "invalid-version",
            CameraSignalValidator.error(JSONObject().put("version", 2).put("type", "stop")),
        )
    }

    @Test
    fun `bounds SDP candidates and control shapes`() {
        assertNull(
            CameraSignalValidator.error(
                JSONObject()
                    .put("version", 1)
                    .put("type", "ice-candidate")
                    .put(
                        "candidate",
                        JSONObject()
                            .put("candidate", "candidate:1")
                            .put("sdpMid", "0")
                            .put("sdpMLineIndex", 0)
                            .put("usernameFragment", "browser-generated-fragment"),
                    )
            )
        )
        assertEquals(
            "invalid-offer",
            CameraSignalValidator.error(
                JSONObject()
                    .put("version", 1)
                    .put("type", "offer")
                    .put("sdp", "x".repeat(CameraSignalValidator.MAX_SDP + 1))
            ),
        )
        assertEquals(
            "invalid-candidate",
            CameraSignalValidator.error(
                JSONObject()
                    .put("version", 1)
                    .put("type", "ice-candidate")
                    .put(
                        "candidate",
                        JSONObject().put("candidate", "candidate:1").put("sdpMLineIndex", -1),
                    )
            ),
        )
        assertEquals(
            "invalid-control",
            CameraSignalValidator.error(
                JSONObject().put("version", 1).put("type", "control").put("zoom", "two")
            ),
        )
    }
}
