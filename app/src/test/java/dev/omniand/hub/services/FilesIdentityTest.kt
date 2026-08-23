package dev.omniand.hub.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class FilesIdentityTest {
    @Test
    fun opaqueIdentityRoundTripsRootAndNormalizedRelativePath() {
        val encoded = FilesService.encode("volume-identity", "Documents/report.pdf")

        assertEquals("volume-identity" to "Documents/report.pdf", FilesService.decode(encoded))
        assertFalse(encoded.contains("Documents"))
        assertEquals("Documents/report.pdf", FilesService.normalize("Documents//report.pdf"))
    }

    @Test
    fun absoluteTraversalBackslashAndMalformedIdentitiesAreRejected() {
        listOf("/etc/passwd", "Documents/../secret", "Documents\\secret").forEach { path ->
            try {
                FilesService.normalize(path)
                fail("Expected invalid path: $path")
            } catch (error: FilesService.Invalid) {
                check(error.code in setOf("invalid-path", "path-traversal"))
            }
        }
        try {
            FilesService.decode("not-an-entry")
            fail("Expected malformed identity")
        } catch (error: FilesService.Invalid) {
            assertEquals("invalid-id", error.code)
        }
    }
}
