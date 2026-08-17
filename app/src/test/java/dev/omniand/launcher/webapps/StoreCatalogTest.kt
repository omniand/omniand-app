package dev.omniand.launcher.webapps

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCatalogTest {
    @Test
    fun semanticVersionsFollowSemverOrdering() {
        assertTrue(SemanticVersion.compare("2.0.0", "1.9.9")!! > 0)
        assertTrue(SemanticVersion.compare("1.3.0", "1.2.9")!! > 0)
        assertTrue(SemanticVersion.compare("1.2.4", "1.2.3")!! > 0)
        assertTrue(SemanticVersion.compare("1.0.0-alpha.2", "1.0.0-alpha.10")!! < 0)
        assertTrue(SemanticVersion.compare("1.0.0-rc.1", "1.0.0")!! < 0)
        assertEquals(0, SemanticVersion.compare("1.0.0+one", "1.0.0+two"))
        assertNull(SemanticVersion.compare("1.0", "1.0.0"))
        assertNull(SemanticVersion.compare("01.0.0", "1.0.0"))
        assertTrue(SemanticVersion.compare("999999999999999999999.0.0", "2.0.0")!! > 0)
    }

    @Test
    fun onlyStrictlyNewerCatalogEntryIsAnUpdateAndCapabilitiesAreDiffed() {
        val installed = installedApp("1.2.0", setOf("sms.read"))
        val newer =
            CatalogApp(
                "messages",
                "1.3.0",
                setOf("sms.read", "sms.send"),
                "https://store.example/packages/app.zip",
            )
        val update = StoreCatalog.findUpdate(installed, listOf(newer))
        assertTrue(update.available)
        assertEquals(setOf("sms.send"), update.addedCapabilities)
        assertFalse(
            StoreCatalog.findUpdate(installed, listOf(newer.copy(version = "1.2.0"))).available
        )
        assertFalse(
            StoreCatalog.findUpdate(installed, listOf(newer.copy(version = "1.1.9"))).available
        )
        assertFalse(
            StoreCatalog.findUpdate(installed, listOf(newer.copy(version = "latest"))).available
        )
        assertFalse(StoreCatalog.findUpdate(installed, emptyList()).available)
    }

    @Test
    fun catalogParsesRelativePackagesAndRejectsForeignOrigins() {
        val json =
            """[{"id":"messages","version":"2.0.0","permissions":["sms.read"],"packageUrl":"packages/messages.zip"}]"""
        val entry = StoreCatalog.parse(json.toByteArray(), "https://store.example/base/").single()
        assertEquals("https://store.example/base/packages/messages.zip", entry.packageUrl)
        val foreign = json.replace("packages/messages.zip", "https://evil.example/messages.zip")
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse(foreign.toByteArray(), "https://store.example/")
        }
    }

    @Test
    fun malformedAndOversizedCatalogsAreRejected() {
        assertThrows(Exception::class.java) {
            StoreCatalog.parse("not json".toByteArray(), "https://store.example/")
        }
        assertThrows(IllegalStateException::class.java) {
            StoreCatalog.parse(
                ByteArray(StoreCatalog.MAX_CATALOG_BYTES + 1),
                "https://store.example/",
            )
        }
    }

    private fun installedApp(version: String, permissions: Set<String>) =
        WebApp("messages", "Messages", version, permissions, fileRoot = File("messages"))
}
