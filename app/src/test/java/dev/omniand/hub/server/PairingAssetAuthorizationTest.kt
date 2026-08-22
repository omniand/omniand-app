package dev.omniand.hub.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingAssetAuthorizationTest {
    @Test
    fun exposesOnlyPairingRuntimeAssetsBeforeAuthentication() {
        assertEquals("index.html", PlatformServer.pairingAssetPath("/"))
        assertEquals("pairing.js", PlatformServer.pairingAssetPath("/pairing.js"))
        assertEquals("pairing.css", PlatformServer.pairingAssetPath("/pairing.css"))
        assertEquals("i18n.js", PlatformServer.pairingAssetPath("/i18n.js"))
        assertEquals("locales/fr.json", PlatformServer.pairingAssetPath("/locales/fr.json"))
        assertEquals(
            "vendor/i18next/i18next.min.js",
            PlatformServer.pairingAssetPath("/vendor/i18next/i18next.min.js"),
        )
        assertNull(PlatformServer.pairingAssetPath("/assets/shell.js"))
        assertNull(PlatformServer.pairingAssetPath("/api/apps/web"))
        assertNull(PlatformServer.pairingAssetPath("/locales/de.json"))
        assertNull(PlatformServer.pairingAssetPath("/vendor/i18next/LICENSE"))
    }
}
