package dev.omniand.hub.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {
    private val secret = "ab".repeat(32)

    @Test
    fun acceptsAnyExactHttpsPairingDestination() {
        assertEquals(
            PairingTarget("https://connect.instance-one.example", secret),
            PairingTarget.parse("https://connect.instance-one.example/pair/$secret"),
        )
        assertEquals(
            PairingTarget("https://pairing.other-domain.fr", secret),
            PairingTarget.parse("https://pairing.other-domain.fr/pair/$secret"),
        )
        for (invalid in
            listOf(
                "http://connect.phone.example.org/pair/$secret",
                "https://connect.phone.example.org:8443/pair/$secret",
                "https://connect.phone.example.org/pair/$secret?next=evil",
                "https://connect.phone.example.org/pair/${"z".repeat(64)}",
            )) assertNull(PairingTarget.parse(invalid))
    }
}
