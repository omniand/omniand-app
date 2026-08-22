package dev.omniand.launcher

import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.TextView
import dev.omniand.launcher.server.LocalSessionAuthenticator
import dev.omniand.launcher.server.PlatformServer

/** Starts the loopback server and installs a host-only credential before first navigation. */
object LocalWebViewBootstrap {
    fun load(activity: Activity, webView: WebView, hostname: String, route: String = "") {
        if (!PlatformServer.start(activity.applicationContext)) {
            showFailure(activity, "OmniAnd could not start its local server.")
            return
        }
        val origin = "http://$hostname:${PlatformServer.PORT}"
        val cookie =
            "${LocalSessionAuthenticator.COOKIE_NAME}=${LocalSessionAuthenticator.tokenFor(hostname)}; " +
                "Path=/; HttpOnly; SameSite=Strict"
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
            setCookie(origin, cookie) { accepted ->
                activity.runOnUiThread {
                    if (accepted) webView.loadUrl("$origin/$route")
                    else showFailure(activity, "OmniAnd could not authenticate its local WebView.")
                }
            }
        }
    }

    private fun showFailure(activity: Activity, message: String) {
        activity.setContentView(TextView(activity).apply { text = message })
    }
}
