package dev.omniand.launcher

import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.omniand.launcher.BuildConfig
import dev.omniand.launcher.server.LocalOriginRouter
import dev.omniand.launcher.server.PlatformServer
import dev.omniand.launcher.webapps.WebAppRegistry

class WebAppActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformServer.start(applicationContext)
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val app = WebAppRegistry.apps(this).firstOrNull { it.id == appId }
        if (app == null) {
            finish()
            return
        }
        title = app.name
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "${settings.userAgentString} OmniAndPlatform/1.0"
            if (app.id == "store" && BuildConfig.STORE_URL.startsWith("http://")) {
                // The locally routed Store shell has an HTTPS origin, while the
                // development Vite catalog is intentionally served over HTTP.
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    LocalOriginRouter.intercept(applicationContext, request)
            }
            loadUrl(WebAppRegistry.originFor(app))
        }
        setContentView(webView)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_APP_ID = "appId" }
}
