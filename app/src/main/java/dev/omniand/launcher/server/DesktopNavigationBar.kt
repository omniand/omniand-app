package dev.omniand.launcher.server

/** Adds server-owned navigation chrome to application documents served to desktop browsers. */
object DesktopNavigationBar {
    fun platformHref(appId: String, appAuthority: String): String {
        val prefix = "$appId."
        val platformAuthority =
            appAuthority.takeIf { it.startsWith(prefix, ignoreCase = true) }?.drop(prefix.length)
                ?: appAuthority
        return "//$platformAuthority/"
    }

    fun inject(document: ByteArray, appName: String, platformOrigin: String): ByteArray {
        val html = document.toString(Charsets.UTF_8)
        val bodyStart =
            Regex("<body(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html) ?: return document
        val insertionPoint = bodyStart.range.last + 1
        val bar = markup(escape(appName), escape(platformOrigin))
        return html
            .substring(0, insertionPoint)
            .plus(bar)
            .plus(html.substring(insertionPoint))
            .toByteArray()
    }

    /**
     * Builds isolated, dependency-free chrome that remains usable when application scripts fail.
     */
    private fun markup(appName: String, platformOrigin: String) =
        """
        <style id="omniand-desktop-navigation-style">
          body { padding-top: 56px !important; }
          .omniand-desktop-navigation {
            position: fixed; z-index: 2147483647; top: 0; right: 0; left: 0;
            display: flex; box-sizing: border-box; height: 56px; padding: 0 16px;
            align-items: center; gap: 12px; color: #1f1f1f; background: #f7f5fa;
            box-shadow: 0 2px 6px rgb(0 0 0 / 18%); font: 18px sans-serif;
          }
          .omniand-desktop-navigation a {
            display: grid; width: 40px; height: 40px; border-radius: 50%; place-items: center;
            color: inherit; text-decoration: none;
          }
          .omniand-desktop-navigation a:hover,
          .omniand-desktop-navigation a:focus-visible { background: rgb(0 0 0 / 8%); outline: none; }
          .omniand-desktop-navigation span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        </style>
        <nav class="omniand-desktop-navigation" aria-label="Platform navigation">
          <a href="$platformOrigin" aria-label="Applications">&#8592;</a><span>$appName</span>
        </nav>
        """
            .trimIndent()

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
