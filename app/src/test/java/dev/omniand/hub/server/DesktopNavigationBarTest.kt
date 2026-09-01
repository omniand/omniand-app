package dev.omniand.hub.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        assertEquals(
            "//platform-abcdefghijklmnopqrstuvwxyz.phone.example.org/",
            DesktopNavigationBar.platformHref(
                "messages",
                "messages-abcdefghijklmnopqrstuvwxyz.phone.example.org",
            ),
        )
    }

    @Test
    fun injectsBackAndHomeControlsAfterBodyTag() {
        val result =
            DesktopNavigationBar.inject(
                    "<!doctype html><head><title>Messages</title></head><body class=\"app\"><main>Messages</main></body>"
                        .toByteArray(),
                    "https://phone.example.org/",
                )
                .toString(Charsets.UTF_8)

        assertTrue(
            result.contains("<link rel=\"icon\" type=\"image/png\" href=\"/favicon.ico\" />")
        )
        assertTrue(result.contains("<body class=\"app\"><style"))
        assertTrue(result.contains("href=\"https://phone.example.org/\""))
        assertTrue(result.contains("data-omniand-back"))
        assertTrue(result.contains("data-omniand-drag"))
        assertTrue(result.contains("<circle cx=\"9\" cy=\"6\" r=\"1.5\"/>"))
        assertTrue(!result.contains("&#8942;"))
        assertTrue(result.contains("position: fixed"))
        assertTrue(result.contains("top: 50%"))
        assertTrue(result.contains("flex-direction: column"))
        assertTrue(!result.contains("body { padding-top"))
        assertTrue(result.contains("aria-label=\"Home\""))
        assertTrue(result.indexOf("aria-label=\"Home\"") < result.indexOf("aria-label=\"Back\""))
        assertTrue(
            result.indexOf("aria-label=\"Back\"") < result.indexOf("aria-label=\"Move navigation\"")
        )
        assertTrue(result.contains("src=\"${DesktopNavigationBar.SCRIPT_PATH}\""))
        assertTrue(!result.contains("<span>Messages</span>"))
    }

    @Test
    fun backScriptClosesDialogsBeforeUsingBrowserHistory() {
        val script = DesktopNavigationBar.script().toString(Charsets.UTF_8)

        assertTrue(script.contains("[role=\"dialog\"]"))
        assertTrue(script.contains("key: 'Escape'"))
        assertTrue(script.contains("history.back()"))
        assertTrue(script.contains("location.assign(back.dataset.home)"))
        assertTrue(script.contains("setPointerCapture"))
        assertTrue(script.contains("omniand_navigation_position"))
        assertTrue(script.contains("document.cookie"))
        assertTrue(script.contains("window.name"))
        assertTrue(script.contains("pointerup', savePosition"))
    }

    @Test
    fun escapesServerOwnedValues() {
        val result =
            DesktopNavigationBar.inject(
                    "<body></body>".toByteArray(),
                    "https://phone.example.org/?a=1&b=2",
                )
                .toString(Charsets.UTF_8)

        assertTrue(result.contains("?a=1&amp;b=2"))
    }

    @Test
    fun leavesNonHtmlContentUnchanged() {
        val document = "plain text".toByteArray()

        assertArrayEquals(
            document,
            DesktopNavigationBar.inject(document, "https://phone.example.org/"),
        )
    }
}
