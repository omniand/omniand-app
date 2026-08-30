package dev.omniand.hub.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLinkSessionTest {
    @Test
    fun parsesStablePlatformAndApplicationHostsOnly() {
        val link = "abcdefghijklmnopqrstuvwxyz"
        assertEquals(
            StableHost("platform", link),
            RemoteLinkSession.parseHost("platform-$link.phone.example.org", "phone.example.org"),
        )
        assertEquals(
            StableHost("my-app", link),
            RemoteLinkSession.parseHost("my-app-$link.phone.example.org", "phone.example.org"),
        )
        assertNull(RemoteLinkSession.parseHost("my-app.phone.example.org", "phone.example.org"))
        assertNull(
            RemoteLinkSession.parseHost("my-app-$link.evil.example.org", "phone.example.org")
        )
    }
}
