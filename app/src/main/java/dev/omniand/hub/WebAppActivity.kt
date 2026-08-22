package dev.omniand.hub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.omniand.hub.contacts.ContactsSetupManager
import dev.omniand.hub.media.MediaSetupManager
import dev.omniand.hub.sms.SmsNotifications
import dev.omniand.hub.sms.SmsSetupManager
import dev.omniand.hub.webapps.WebAppRegistry

class WebAppActivity : Activity() {
    private lateinit var webView: WebView
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private var currentAppId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val app = WebAppRegistry.apps(this).firstOrNull { it.id == appId }
        if (app == null) {
            finish()
            return
        }
        currentAppId = app.id
        title = app.name
        webView =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "${settings.userAgentString} OmniAndPlatform/1.0"
                if (app.id == "store" && BuildConfig.STORE_URL.startsWith("http://")) {
                    // The locally routed Store shell has an HTTPS origin, while the
                    // development Vite catalog is intentionally served over HTTP.
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams,
                        ): Boolean {
                            fileResult?.onReceiveValue(null)
                            fileResult = callback
                            return try {
                                val chooser = params.createIntent()
                                if (app.id == "gallery") {
                                    chooser.type = "*/*"
                                    chooser.putExtra(
                                        Intent.EXTRA_MIME_TYPES,
                                        arrayOf("image/*", "video/*"),
                                    )
                                    chooser.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                } else {
                                    chooser.type = "image/*"
                                }
                                startActivityForResult(
                                    chooser,
                                    FILE_CHOOSER,
                                )
                                true
                            } catch (_: Exception) {
                                fileResult = null
                                false
                            }
                        }
                    }
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (!request.isForMainFrame) return false
                            val uri = request.url
                            if (uri.scheme == "tel" || uri.scheme == "mailto") {
                                startActivity(Intent(Intent.ACTION_VIEW, uri))
                                return true
                            }
                            if (
                                uri.scheme == "https" &&
                                    uri.host == "messages.${BuildConfig.PLATFORM_HOST}"
                            ) {
                                val route = uri.fragment?.let { "#$it" }.orEmpty()
                                if (validRoute(route))
                                    startActivity(
                                        Intent(this@WebAppActivity, WebAppActivity::class.java)
                                            .putExtra(EXTRA_APP_ID, "messages")
                                            .putExtra(EXTRA_ROUTE, route)
                                    )
                                return true
                            }
                            if (
                                uri.scheme == "http" &&
                                    uri.port == 8080 &&
                                    uri.host == "${app.id}.localhost"
                            )
                                return false
                            val targetApp =
                                uri.host
                                    ?.takeIf { uri.scheme == "http" && uri.port == 8080 }
                                    ?.removeSuffix(".localhost")
                                    ?.let { id ->
                                        WebAppRegistry.apps(applicationContext).firstOrNull {
                                            it.id == id
                                        }
                                    }
                            if (targetApp != null) {
                                startActivity(
                                    Intent(this@WebAppActivity, WebAppActivity::class.java)
                                        .putExtra(EXTRA_APP_ID, targetApp.id)
                                )
                            } else {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                            return true
                        }
                    }
            }
        setContentView(webView)
        val route = intent.getStringExtra(EXTRA_ROUTE)?.takeIf(::validRoute).orEmpty()
        LocalWebViewBootstrap.load(this, webView, "${app.id}.localhost", route)
        if (app.id == "messages")
            intent.getStringExtra(EXTRA_THREAD_ID)?.let { SmsNotifications.cancelThread(this, it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (currentAppId == "store") {
            isStoreActive = true
            if (
                !MediaSetupManager.openPendingSetup(this) &&
                    !ContactsSetupManager.openPendingSetup(this)
            )
                SmsSetupManager.openPendingSetup(this)
        }
    }

    override fun onPause() {
        if (currentAppId == "store") isStoreActive = false
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER) {
            fileResult?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            fileResult = null
        }
    }

    companion object {
        @Volatile
        var isStoreActive: Boolean = false
            private set

        const val EXTRA_APP_ID = "appId"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_THREAD_ID = "threadId"
        private const val FILE_CHOOSER = 91

        fun validRoute(route: String): Boolean =
            route.matches(
                Regex("^#/(thread\\?id=[0-9]+|compose\\?to=[^#&]{0,100}(&body=[^#]{0,2000})?)$")
            )
    }
}
