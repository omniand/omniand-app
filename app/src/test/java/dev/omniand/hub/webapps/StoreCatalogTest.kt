package dev.omniand.hub.webapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCatalogTest {
    @Test
    fun semanticVersionsFollowSemverOrdering() {
        assertTrue(SemanticVersion.compare("2.0.0", "1.9.9")!! > 0)
        assertTrue(SemanticVersion.compare("1.0.0-alpha.2", "1.0.0-alpha.10")!! < 0)
        assertTrue(SemanticVersion.compare("1.0.0-rc.1", "1.0.0")!! < 0)
        assertEquals(0, SemanticVersion.compare("1.0.0+one", "1.0.0+two"))
        assertNull(SemanticVersion.compare("1.0", "1.0.0"))
        assertNull(SemanticVersion.compare("01.0.0", "1.0.0"))
    }

    @Test
    fun catalogStateReflectsInstalledAndUpdateVersions() {
        assertEquals("available", StoreCatalog.state("2.0.0", null))
        assertEquals("installed", StoreCatalog.state("2.0.0", "2.0.0"))
        assertEquals("installed", StoreCatalog.state("1.0.0", "2.0.0"))
        assertEquals("update-available", StoreCatalog.state("2.0.0", "1.0.0"))
    }

    @Test
    fun catalogParsesCompleteSameOriginMetadata() {
        val entry = StoreCatalog.parse(catalog(), "https://catalog.example/").single()
        assertEquals("messages", entry.id)
        assertEquals("Messages", entry.name)
        assertEquals("One line", entry.tagline)
        assertEquals("Messagerie", entry.displayName("fr-FR,fr;q=0.9"))
        assertEquals("Messages", entry.displayName("en-US,en;q=0.9"))
        assertEquals(setOf("sms.read"), entry.permissions)
        assertEquals("https://catalog.example/packages/messages-2.0.0.zip", entry.packageUrl)
        assertEquals("https://catalog.example/catalog/icons/messages.png", entry.iconUrl)
    }

    @Test
    fun catalogRejectsDuplicateIdsUnknownCapabilitiesAndForeignResources() {
        val item = catalog().toString(Charsets.UTF_8).removePrefix("[").removeSuffix("]")
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse("[$item,$item]".toByteArray(), "https://catalog.example/")
        }
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse(
                catalog().toString(Charsets.UTF_8).replace("sms.read", "unknown").toByteArray(),
                "https://catalog.example/",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse(
                catalog()
                    .toString(Charsets.UTF_8)
                    .replace("/catalog/icons/messages.png", "https://evil.example/icon.png")
                    .toByteArray(),
                "https://catalog.example/",
            )
        }
    }

    @Test
    fun catalogRejectsReservedIdsMismatchedVersionsAndOversize() {
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse(
                catalog().toString(Charsets.UTF_8).replace("messages", "store").toByteArray(),
                "https://catalog.example/",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse(
                catalog()
                    .toString(Charsets.UTF_8)
                    .replace("messages-2.0.0.zip", "messages-1.0.0.zip")
                    .toByteArray(),
                "https://catalog.example/",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse(
                ByteArray(StoreCatalog.MAX_CATALOG_BYTES + 1),
                "https://catalog.example/",
            )
        }
    }

    private fun catalog() =
        """[{"id":"messages","name":"Messages","locales":{"fr":{"name":"Messagerie"}},"tagline":"One line","version":"2.0.0","category":"Communication","permissions":["sms.read"],"packageUrl":"/packages/messages-2.0.0.zip","iconUrl":"/catalog/icons/messages.png"}]"""
            .toByteArray()
}
