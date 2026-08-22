package dev.omniand.hub.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopNavigationBarTest {
    @Test
    fun derivesPlatformHomeFromApplicationAuthority() {
        assertTrue(
            DesktopNavigationBar.platformHref("messages", "messages.localhost:8080") ==
                "//localhost:8080/"
        )
        assertTrue(
            DesktopNavigationBar.platformHref("messages", "messages.phone.example.org") ==
                "//phone.example.org/"
        )
    }

    @Test
    fun injectsHomeLinkAfterBodyTag() {
        val result =
            DesktopNavigationBar.inject(
                    "<!doctype html><body class=\"app\"><main>Messages</main></body>".toByteArray(),
                    "Messages",
                    "https://phone.example.org/",
                )
                .toString(Charsets.UTF_8)

        assertTrue(result.contains("<body class=\"app\"><style"))
        assertTrue(result.contains("href=\"https://phone.example.org/\""))
        assertTrue(result.contains("<span>Messages</span>"))
    }

    @Test
    fun escapesServerOwnedValues() {
        val result =
            DesktopNavigationBar.inject(
                    "<body></body>".toByteArray(),
                    "Chat <unsafe>",
                    "https://phone.example.org/?a=1&b=2",
                )
                .toString(Charsets.UTF_8)

        assertTrue(result.contains("Chat &lt;unsafe&gt;"))
        assertTrue(result.contains("?a=1&amp;b=2"))
    }

    @Test
    fun leavesNonHtmlContentUnchanged() {
        val document = "plain text".toByteArray()

        assertArrayEquals(
            document,
            DesktopNavigationBar.inject(document, "Messages", "https://phone.example.org/"),
        )
    }
}
