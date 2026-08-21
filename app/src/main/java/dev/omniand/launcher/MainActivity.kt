package dev.omniand.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.omniand.launcher.contacts.ContactsSetupManager
import dev.omniand.launcher.media.MediaSetupManager
import dev.omniand.launcher.server.LocalOriginRouter
import dev.omniand.launcher.server.PlatformServer
import dev.omniand.launcher.sms.SmsSetupManager
import dev.omniand.launcher.webapps.WebAppRegistry

class MainActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformServer.start(applicationContext)

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
                            val app =
                                request.url.host?.let {
                                    WebAppRegistry.byHost(applicationContext, it)
                                } ?: return false
                            startActivity(
                                Intent(this@MainActivity, WebAppActivity::class.java)
                                    .putExtra(WebAppActivity.EXTRA_APP_ID, app.id)
                            )
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = LocalOriginRouter.intercept(applicationContext, request)
                    }
                webChromeClient = WebChromeClient()
                loadUrl("https://${BuildConfig.PLATFORM_HOST}/")
            }
        setContentView(webView)

        openPendingSetup()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        WebAppRegistry.invalidate()
        openPendingSetup()
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
        super.onPause()
    }

    override fun onDestroy() {
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
    }
}
