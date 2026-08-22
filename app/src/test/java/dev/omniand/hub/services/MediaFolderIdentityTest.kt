package dev.omniand.hub.services

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock

class MediaFolderIdentityTest {
    private val service = MediaService(mock(Context::class.java))

    @Test
    fun folderIdentityRoundTripsWithoutExposingPath() {
        val ref = MediaService.FolderRef("external_primary", "-1739773001")
        val encoded = service.encodeFolderId(ref)

        assertEquals(ref, service.decodeFolderId(encoded))
        check(!encoded.contains("external_primary"))
    }

    @Test
    fun malformedFolderIdentityIsRejectedWithStableCode() {
        try {
            service.decodeFolderId("Pictures/Camera")
            fail("Expected invalid folder")
        } catch (error: MediaService.Invalid) {
            assertEquals("invalid-folder", error.code)
        }
    }

    @Test
    fun mediaOwnershipUsesProviderOwnerOnScopedStorage() {
        assertEquals(true, MediaService.ownsMedia(28, "", "dev.omniand.launcher"))
        assertEquals(
            true,
            MediaService.ownsMedia(35, "dev.omniand.launcher", "dev.omniand.launcher"),
        )
        assertEquals(
            false,
            MediaService.ownsMedia(35, "com.example.camera", "dev.omniand.launcher"),
        )
    }
}
