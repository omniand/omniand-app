package dev.omniand.hub.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionAuthenticatorTest {
    @Test
    fun derivesStableSeparatedHostTokens() {
        val key = ByteArray(32) { it.toByte() }
        val messages = LocalSessionAuthenticator.deriveToken(key, "messages.localhost")

        assertTrue(messages == LocalSessionAuthenticator.deriveToken(key, "MESSAGES.LOCALHOST"))
        assertNotEquals(messages, LocalSessionAuthenticator.deriveToken(key, "test.localhost"))
    }

    @Test
    fun acceptsOnlyExactHostBoundCookie() {
        val messages = LocalSessionAuthenticator.tokenFor("messages.localhost")
        val test = LocalSessionAuthenticator.tokenFor("test.localhost")

        assertTrue(
            LocalSessionAuthenticator.verify(
                "messages.localhost",
                "unrelated=value; ${LocalSessionAuthenticator.COOKIE_NAME}=$messages",
            )
        )
        assertFalse(LocalSessionAuthenticator.verify("messages.localhost", null))
        assertFalse(LocalSessionAuthenticator.verify("messages.localhost", "malformed"))
        assertFalse(
            LocalSessionAuthenticator.verify(
                "messages.localhost",
                "${LocalSessionAuthenticator.COOKIE_NAME}=$test",
            )
        )
    }

    @Test
    fun comparesCredentialsAndRecognizesOnlyLoopbackAddresses() {
        assertTrue(LocalSessionAuthenticator.constantTimeEquals("same", "same"))
        assertFalse(LocalSessionAuthenticator.constantTimeEquals("same", "different"))
        assertTrue(LocalSessionAuthenticator.isLoopback("127.0.0.1"))
        assertTrue(LocalSessionAuthenticator.isLoopback("::1"))
        assertFalse(LocalSessionAuthenticator.isLoopback("192.168.1.20"))
        assertFalse(LocalSessionAuthenticator.isLoopback("not an address"))
    }
}
