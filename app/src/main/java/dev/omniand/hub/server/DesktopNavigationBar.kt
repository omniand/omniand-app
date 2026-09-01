package dev.omniand.hub.server

/** Adds server-owned navigation chrome to application documents served to desktop browsers. */
object DesktopNavigationBar {
    const val SCRIPT_PATH = "/__omniand/desktop-navigation.js"

    fun platformHref(appId: String, appAuthority: String): String {
        val prefix = "$appId."
        val platformAuthority =
            when {
                appAuthority.startsWith(prefix, ignoreCase = true) ->
                    appAuthority.drop(prefix.length)
                appAuthority.startsWith("$appId-", ignoreCase = true) ->
                    "platform-${appAuthority.drop(appId.length + 1)}"
                else -> appAuthority
            }
        return "//$platformAuthority/"
    }

    fun inject(document: ByteArray, platformOrigin: String): ByteArray {
        val html = document.toString(Charsets.UTF_8)
        val headEnd = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html)
        val documentWithFavicon =
            if (headEnd == null) html
            else
                html
                    .substring(0, headEnd.range.first)
                    .plus("<link rel=\"icon\" type=\"image/png\" href=\"/favicon.ico\" />")
                    .plus(html.substring(headEnd.range.first))
        val bodyStart =
            Regex("<body(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(documentWithFavicon)
                ?: return document
        val insertionPoint = bodyStart.range.last + 1
        val bar = markup(escape(platformOrigin))
        return documentWithFavicon
            .substring(0, insertionPoint)
            .plus(bar)
            .plus(documentWithFavicon.substring(insertionPoint))
            .toByteArray()
    }

    /**
     * Builds isolated, dependency-free chrome that remains usable when application scripts fail.
     */
    private fun markup(platformOrigin: String) =
        """
        <style id="omniand-desktop-navigation-style">
          .omniand-desktop-navigation {
            position: fixed; z-index: 2147483647; top: 50%; left: max(8px, env(safe-area-inset-left));
            transform: translateY(-50%); display: flex; box-sizing: border-box; width: 48px;
            max-height: calc(100dvh - 16px); padding: 8px 4px; flex-direction: column;
            align-items: center; gap: 8px; color: #1f1f1f; background: #f7f5fa;
            border: 1px solid rgb(0 0 0 / 12%); border-radius: 24px;
            box-shadow: 0 3px 12px rgb(0 0 0 / 24%); font: 16px sans-serif;
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
          .omniand-desktop-navigation [data-omniand-drag] {
            height: 24px; cursor: grab; touch-action: none; color: #666; font-size: 18px;
          }
          .omniand-desktop-navigation [data-omniand-drag]:active { cursor: grabbing; }
        </style>
        <nav class="omniand-desktop-navigation" aria-label="Platform navigation">
          <a href="$platformOrigin" aria-label="Home" title="Home">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 2.5 11h2.3v9h5.4v-5.5h3.6V20h5.4v-9h2.3L12 3Z"/></svg>
          </a>
          <button type="button" data-omniand-back data-home="$platformOrigin" aria-label="Back" title="Back">&#8592;</button>
          <button type="button" data-omniand-drag aria-label="Move navigation" title="Move navigation">&#8942;</button>
        </nav>
        <script src="$SCRIPT_PATH" defer></script>
        """
            .trimIndent()

    /** Implements browser-like Back while giving semantic application dialogs first refusal. */
    fun script(): ByteArray =
        """
        (() => {
          window.__omniandPresence ||= new EventSource('/api/hub/presence');
          const back = document.querySelector('[data-omniand-back]');
          const navigation = document.querySelector('.omniand-desktop-navigation');
          const drag = document.querySelector('[data-omniand-drag]');
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
          if (!navigation || !drag) return;
          let offsetX = 0;
          let offsetY = 0;
          const positionKey = 'omniand_navigation_position';
          const clamp = (value, minimum, maximum) => Math.min(Math.max(value, minimum), maximum);
          const place = (left, top) => {
            navigation.style.transform = 'none';
            navigation.style.left = `${'$'}{clamp(left, 8, Math.max(8, innerWidth - navigation.offsetWidth - 8))}px`;
            navigation.style.top = `${'$'}{clamp(top, 8, Math.max(8, innerHeight - navigation.offsetHeight - 8))}px`;
          };
          const readPosition = () => {
            const cookie = document.cookie.split('; ').find((item) => item.startsWith(positionKey + '='));
            const stored = cookie?.substring(positionKey.length + 1)
              || window.name.match(/(?:^|;)omniand-navigation=([0-9]+,[0-9]+)(?:;|$)/)?.[1];
            const [left, top] = (stored || '').split(',').map(Number);
            return Number.isFinite(left) && Number.isFinite(top) ? { left, top } : null;
          };
          const savePosition = () => {
            const bounds = navigation.getBoundingClientRect();
            const value = `${'$'}{Math.round(bounds.left)},${'$'}{Math.round(bounds.top)}`;
            const parentDomain = location.hostname.includes('.')
              ? location.hostname.substring(location.hostname.indexOf('.') + 1) : '';
            document.cookie = positionKey + '=' + value + '; Max-Age=31536000; Path=/; SameSite=Lax'
              + (parentDomain ? '; Domain=' + parentDomain : '')
              + (location.protocol === 'https:' ? '; Secure' : '');
            const token = 'omniand-navigation=' + value;
            window.name = window.name.replace(/(?:^|;)omniand-navigation=[0-9]+,[0-9]+(?=;|$)/, '')
              .replace(/^;+|;+$/g, '');
            window.name = window.name ? window.name + ';' + token : token;
          };
          requestAnimationFrame(() => {
            const saved = readPosition();
            if (saved) place(saved.left, saved.top);
          });
          drag.addEventListener('pointerdown', (event) => {
            const bounds = navigation.getBoundingClientRect();
            offsetX = event.clientX - bounds.left;
            offsetY = event.clientY - bounds.top;
            drag.setPointerCapture(event.pointerId);
          });
          drag.addEventListener('pointermove', (event) => {
            if (!drag.hasPointerCapture(event.pointerId)) return;
            place(event.clientX - offsetX, event.clientY - offsetY);
          });
          drag.addEventListener('pointerup', savePosition);
          addEventListener('resize', () => {
            const bounds = navigation.getBoundingClientRect();
            place(bounds.left, bounds.top);
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
