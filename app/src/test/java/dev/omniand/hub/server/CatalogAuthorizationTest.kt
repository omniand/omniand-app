package dev.omniand.hub.server

import dev.omniand.hub.webapps.WebApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAuthorizationTest {
    @Test
    fun onlyAuthenticatedAndroidPlatformHomeMayManageCatalog() {
        assertTrue(PlatformServer.canManageCatalog(request("localhost", true, null)))
        assertFalse(PlatformServer.canManageCatalog(request("phone.example.org", false, null)))
        assertFalse(
            PlatformServer.canManageCatalog(
                request(
                    "messages.localhost",
                    true,
                    WebApp("messages", "Messages", "1.0.0", emptySet()),
                )
            )
        )
    }

    @Test
    fun hubConfigurationMutationsRequireThePhonePlatformHome() {
        assertTrue(PlatformServer.canManageHub(request("localhost", true, null)))
        assertFalse(PlatformServer.canManageHub(request("phone.example.org", false, null)))
        assertFalse(
            PlatformServer.canManageHub(
                request(
                    "messages.localhost",
                    true,
                    WebApp("messages", "Messages", "1.0.0", emptySet()),
                )
            )
        )
    }

    @Test
    fun desktopPresenceRejectsPhoneClientsAndAcceptsAuthenticatedDesktopOrigins() {
        assertFalse(PlatformServer.canReadDesktopPresence(request("localhost", true, null)))
        assertTrue(PlatformServer.canReadDesktopPresence(request("phone.example.org", false, null)))
        assertTrue(
            PlatformServer.canReadDesktopPresence(
                request(
                    "messages.phone.example.org",
                    false,
                    WebApp("messages", "Messages", "1.0.0", emptySet()),
                )
            )
        )
    }

    private fun request(host: String, phone: Boolean, app: WebApp?) =
        PlatformRequestContext(
            authority = if (phone) "$host:8080" else host,
            hostname = host,
            transport =
                if (phone) PlatformRequestContext.Transport.LOOPBACK_HTTP
                else PlatformRequestContext.Transport.DESKTOP_HTTP,
            phoneClient = phone,
            app = app,
        )
}
