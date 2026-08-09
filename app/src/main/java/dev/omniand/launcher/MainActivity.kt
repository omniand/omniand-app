package dev.omniand.launcher

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.omniand.launcher.server.PlatformServer

class MainActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformServer.start(applicationContext)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "${settings.userAgentString} OmniAndLauncher/1.0"
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("http://localhost:${PlatformServer.PORT}/")
        }
        setContentView(webView)

        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS), SMS_PERMISSION_REQUEST)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object { private const val SMS_PERMISSION_REQUEST = 10 }
}
