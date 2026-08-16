package dev.omniand.launcher.server

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import dev.omniand.launcher.BuildConfig

/** Routes canonical HTTPS origins directly to the in-process server for Android WebViews. */
object LocalOriginRouter {
    fun isPlatformOrigin(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host ?: return false
        return host == BuildConfig.PLATFORM_HOST || host.endsWith(".${BuildConfig.PLATFORM_HOST}")
    }

    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!isPlatformOrigin(uri)) return null
        val host = uri.host ?: return null
        val path = uri.encodedPath.orEmpty().ifEmpty { "/" } +
            uri.encodedQuery?.let { "?$it" }.orEmpty()
        val response = PlatformServer.localResponse(context, request.method, path, host, request.requestHeaders)
        val contentType = response.contentType.substringBefore(';')
        val encoding = response.contentType.substringAfter("charset=", "utf-8")
        return WebResourceResponse(
            contentType,
            encoding,
            response.statusCode,
            response.reason,
            response.headers,
            response.openBody()
        )
    }
}
