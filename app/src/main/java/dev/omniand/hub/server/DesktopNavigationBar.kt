package dev.omniand.hub.server

/** Adds server-owned navigation chrome to application documents served to desktop browsers. */
object DesktopNavigationBar {
    const val SCRIPT_PATH = "/__omniand/desktop-navigation.js"

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
          .omniand-desktop-navigation a,
          .omniand-desktop-navigation button {
            display: grid; width: 40px; height: 40px; border-radius: 50%; place-items: center;
            border: 0; color: inherit; background: transparent; text-decoration: none;
            font: inherit; font-size: 24px; cursor: pointer;
          }
          .omniand-desktop-navigation a:hover,
          .omniand-desktop-navigation a:focus-visible,
          .omniand-desktop-navigation button:hover,
          .omniand-desktop-navigation button:focus-visible {
            background: rgb(0 0 0 / 8%); outline: none;
          }
          .omniand-desktop-navigation svg { width: 22px; height: 22px; fill: currentColor; }
          .omniand-desktop-navigation span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        </style>
        <nav class="omniand-desktop-navigation" aria-label="Platform navigation">
          <button type="button" data-omniand-back data-home="$platformOrigin" aria-label="Back" title="Back">&#8592;</button>
          <a href="$platformOrigin" aria-label="Home" title="Home">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 2.5 11h2.3v9h5.4v-5.5h3.6V20h5.4v-9h2.3L12 3Z"/></svg>
          </a>
          <span>$appName</span>
        </nav>
        <script src="$SCRIPT_PATH" defer></script>
        """
            .trimIndent()

    /** Implements browser-like Back while giving semantic application dialogs first refusal. */
    fun script(): ByteArray =
        """
        (() => {
          const back = document.querySelector('[data-omniand-back]');
          if (!back) return;
          back.addEventListener('click', () => {
            const overlay = [...document.querySelectorAll('[role="dialog"], [role="alertdialog"]')]
              .find((element) => {
                const style = getComputedStyle(element);
                return style.display !== 'none' && style.visibility !== 'hidden';
              });
            if (overlay) {
              document.dispatchEvent(new KeyboardEvent('keydown', {
                key: 'Escape', code: 'Escape', bubbles: true, cancelable: true
              }));
              return;
            }
            if (history.length > 1) history.back();
            else location.assign(back.dataset.home);
          });
        })();
        """
            .trimIndent()
            .toByteArray()

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
