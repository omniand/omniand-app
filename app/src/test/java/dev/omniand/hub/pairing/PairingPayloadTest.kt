package dev.omniand.hub.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {
    private val secret = "ab".repeat(32)

    @Test
    fun acceptsOnlyExactTrustedHttpsPayload() {
        assertEquals(
            secret,
            PairingPayload.secret(
                "https://connect.phone.example.org/pair/$secret",
                "phone.example.org",
            ),
        )
        for (invalid in
            listOf(
                "http://connect.phone.example.org/pair/$secret",
                "https://evil.example/pair/$secret",
                "https://connect.phone.example.org:8443/pair/$secret",
                "https://connect.phone.example.org/pair/$secret?next=evil",
                "https://connect.phone.example.org/pair/${"z".repeat(64)}",
            )) assertNull(PairingPayload.secret(invalid, "phone.example.org"))
    }
}
