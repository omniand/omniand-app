package dev.omniand.hub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.omniand.hub.background.BackgroundHostingManager
import dev.omniand.hub.contacts.ContactsSetupManager
import dev.omniand.hub.media.MediaSetupManager
import dev.omniand.hub.sms.SmsSetupManager
import dev.omniand.hub.webapps.WebAppRegistry

class MainActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "${settings.userAgentString} OmniAndPlatform/1.0"
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (!request.isForMainFrame) return false
                            val uri = request.url
                            if (uri.scheme == "http" && uri.port == 8080 && uri.host == "localhost")
                                return false
                            val app =
                                uri.host
                                    ?.takeIf { uri.scheme == "http" && uri.port == 8080 }
                                    ?.removeSuffix(".localhost")
                                    ?.let { id ->
                                        WebAppRegistry.apps(applicationContext).firstOrNull {
                                            it.id == id
                                        }
                                    }
                            if (app != null) {
                                val launchIntent =
                                    app.packageName?.let {
                                        packageManager.getLaunchIntentForPackage(it)
                                    }
                                        ?: Intent(this@MainActivity, WebAppActivity::class.java)
                                            .putExtra(WebAppActivity.EXTRA_APP_ID, app.id)
                                startActivity(launchIntent)
                            } else {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                            return true
                        }
                    }
                webChromeClient = WebChromeClient()
            }
        setContentView(webView)
        LocalWebViewBootstrap.load(this, webView, "localhost")

        openPendingSetup()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (BackgroundHostingManager.isEnabled(this)) BackgroundHostingManager.start(this)
        isActive = true
        activeInstance = this
        WebAppRegistry.invalidate()
        openPendingSetup()
        DesktopPairingNotifications.showPending(this)
        if (::webView.isInitialized) {
            webView.post {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new Event('omniand:resume'))",
                    null,
                )
            }
        }
    }

    override fun onPause() {
        isActive = false
        if (activeInstance === this) activeInstance = null
        super.onPause()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        webView.destroy()
        super.onDestroy()
    }

    private fun openPendingSetup() {
        if (MediaSetupManager.openPendingSetup(this)) return
        if (ContactsSetupManager.openPendingSetup(this)) return
        SmsSetupManager.openPendingSetup(this)
    }

    companion object {
        @Volatile
        var isActive: Boolean = false
            private set

        @Volatile
        internal var activeInstance: MainActivity? = null
            private set
    }
}
