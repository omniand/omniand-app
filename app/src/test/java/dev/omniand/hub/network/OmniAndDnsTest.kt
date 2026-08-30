package dev.omniand.hub.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniAndDnsTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val emulatorHost = InetAddress.getByName("10.0.2.2")
    private val publicAddress = InetAddress.getByName("192.0.2.4")

    @Test
    fun reroutesOnlyNonLocalhostLoopbackDomainsOnDebugEmulators() {
        assertEquals(
            listOf(emulatorHost),
            OmniAndDns.route("connect.dev.example", listOf(loopback), true, emulatorHost),
        )
        assertEquals(
            listOf(loopback),
            OmniAndDns.route("localhost", listOf(loopback), true, emulatorHost),
        )
        assertEquals(
            listOf(loopback),
            OmniAndDns.route("connect.dev.example", listOf(loopback), false, emulatorHost),
        )
        assertEquals(
            listOf(publicAddress),
            OmniAndDns.route("connect.example", listOf(publicAddress), true, emulatorHost),
        )
    }
}
