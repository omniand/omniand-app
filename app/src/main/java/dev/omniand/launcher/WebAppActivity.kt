package dev.omniand.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import dev.omniand.launcher.contacts.ContactsSetupManager
import dev.omniand.launcher.media.MediaSetupManager
import dev.omniand.launcher.server.LocalOriginRouter
import dev.omniand.launcher.server.PlatformServer
import dev.omniand.launcher.sms.SmsNotifications
import dev.omniand.launcher.sms.SmsSetupManager
import dev.omniand.launcher.webapps.WebAppRegistry

class WebAppActivity : Activity() {
    private lateinit var webView: WebView
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private var currentAppId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformServer.start(applicationContext)
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
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = LocalOriginRouter.intercept(applicationContext, request)
                    }
                val route = intent.getStringExtra(EXTRA_ROUTE)?.takeIf(::validRoute).orEmpty()
                loadUrl(WebAppRegistry.originFor(app) + route)
            }
        if (intent.getBooleanExtra(EXTRA_ANDROID_INTEGRATION, false)) {
            setContentView(webView)
        } else {
            setContentView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        createNavigationBar(app.name),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(56),
                        ),
                    )
                    addView(
                        webView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f,
                        ),
                    )
                }
            )
        }
        if (app.id == "messages")
            intent.getStringExtra(EXTRA_THREAD_ID)?.let { SmsNotifications.cancelThread(this, it) }
    }

    private fun createNavigationBar(appName: String) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(16), 0)
            setBackgroundColor(Color.rgb(247, 245, 250))
            elevation = dp(3).toFloat()

            addView(
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    contentDescription = "Revenir à la liste des applications"
                    val attributes =
                        context.obtainStyledAttributes(
                            intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                        )
                    background = attributes.getDrawable(0)
                    attributes.recycle()
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    setOnClickListener { openPlatformHome() }
                },
                LinearLayout.LayoutParams(
                    dp(48),
                    dp(48),
                ),
            )

            addView(
                TextView(context).apply {
                    text = appName
                    textSize = 18f
                    setTextColor(Color.rgb(31, 31, 31))
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
        }

    private fun openPlatformHome() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        const val EXTRA_ANDROID_INTEGRATION = "androidIntegration"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_THREAD_ID = "threadId"
        private const val FILE_CHOOSER = 91

        fun validRoute(route: String): Boolean =
            route.matches(
                Regex("^#/(thread\\?id=[0-9]+|compose\\?to=[^#&]{0,100}(&body=[^#]{0,2000})?)$")
            )
    }
}
