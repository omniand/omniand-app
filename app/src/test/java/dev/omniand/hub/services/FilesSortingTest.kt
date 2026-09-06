package dev.omniand.hub.services

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilesSortingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun typeSortKeepsPreviousNameDirectionAsTieBreaker() {
        val folder = temporary.newFolder("folder")
        val alpha = temporary.newFile("alpha.txt")
        val zulu = temporary.newFile("zulu.txt")
        val image = temporary.newFile("photo.jpg")
        val extensionless = temporary.newFile("README")
        val entries = listOf(alpha, extensionless, folder, image, zulu)

        val ascending =
            entries.sortedWith(FilesService.fileComparator("type", "asc", "name", "desc"))
        assertEquals(
            listOf("folder", "README", "photo.jpg", "zulu.txt", "alpha.txt"),
            ascending.map(File::getName),
        )

        val descending =
            entries.sortedWith(FilesService.fileComparator("type", "desc", "name", "desc"))
        assertEquals(
            listOf("zulu.txt", "alpha.txt", "photo.jpg", "README", "folder"),
            descending.map(File::getName),
        )
    }
}
