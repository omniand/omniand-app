package dev.omniand.launcher.webapps

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppInstallerTest {
    @Test fun catalogMetadataMustExactlyMatchPackage() {
        val packageApp = WebAppInstaller.Installed("messages", "Messages", "2.0.0", setOf("sms.read"))
        WebAppInstaller.validateExpected(packageApp, WebAppInstaller.Expected("messages", "2.0.0", setOf("sms.read")))
        assertThrows(IllegalStateException::class.java) {
            WebAppInstaller.validateExpected(packageApp, WebAppInstaller.Expected("other", "2.0.0", setOf("sms.read")))
        }
        assertThrows(IllegalStateException::class.java) {
            WebAppInstaller.validateExpected(packageApp, WebAppInstaller.Expected("messages", "2.1.0", setOf("sms.read")))
        }
        assertThrows(IllegalStateException::class.java) {
            WebAppInstaller.validateExpected(packageApp, WebAppInstaller.Expected("messages", "2.0.0", setOf("sms.send")))
        }
    }

    @Test fun failedActivationRestoresPreviousPackage() {
        val root = Files.createTempDirectory("omniand-update-test").toFile()
        try {
            val staged = File(root, "staged").apply { mkdir(); resolve("new").writeText("new") }
            val target = File(root, "messages").apply { mkdir(); resolve("old").writeText("old") }
            val backup = File(root, ".messages.backup")
            var calls = 0
            assertThrows(IllegalStateException::class.java) {
                WebAppInstaller.activate(staged, target, backup) { from, to ->
                    calls++
                    if (calls == 2) false else from.renameTo(to)
                }
            }
            assertTrue(File(target, "old").isFile)
            assertEquals(false, backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
