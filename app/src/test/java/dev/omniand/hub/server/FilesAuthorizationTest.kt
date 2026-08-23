package dev.omniand.hub.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilesAuthorizationTest {
    @Test
    fun readsAndMutationsRequireIndependentCapabilities() {
        assertEquals(
            "files.read",
            PlatformServer.requiredFilesCapability("GET", "/api/files/roots"),
        )
        assertEquals(
            "files.read",
            PlatformServer.requiredFilesCapability("GET", "/api/files/entries/opaque/content"),
        )
        assertEquals(
            "files.write",
            PlatformServer.requiredFilesCapability("POST", "/api/files/folders"),
        )
        assertEquals(
            "files.write",
            PlatformServer.requiredFilesCapability("GET", "/api/files/jobs/job-id"),
        )
        assertNull(PlatformServer.requiredFilesCapability("GET", "/api/files/setup"))
        assertNull(PlatformServer.requiredFilesCapability("GET", "/api/media"))
    }
}
