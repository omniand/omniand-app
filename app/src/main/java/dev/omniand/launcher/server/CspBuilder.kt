package dev.omniand.launcher.server

import dev.omniand.launcher.BuildConfig
import dev.omniand.launcher.webapps.WebApp

object CspBuilder {
    fun build(app: WebApp): String = listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data:",
        "connect-src 'self'",
        if (app.id == "store") "frame-src ${BuildConfig.STORE_URL}" else "frame-src 'none'",
        "base-uri 'none'",
        "object-src 'none'"
    ).joinToString("; ")

    fun buildPlatform(): String = listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data:",
        "connect-src 'self'",
        "frame-src 'none'",
        "base-uri 'none'",
        "object-src 'none'"
    ).joinToString("; ")
}
