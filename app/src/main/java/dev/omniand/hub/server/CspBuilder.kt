package dev.omniand.hub.server

import dev.omniand.hub.BuildConfig
import dev.omniand.hub.webapps.WebApp

object CspBuilder {
    fun buildPairing(): String =
        listOf(
                "default-src 'none'",
                "script-src 'self'",
                "style-src 'self'",
                "connect-src 'self'",
                "form-action 'none'",
                "base-uri 'none'",
                "frame-ancestors 'none'",
            )
            .joinToString("; ")

    fun build(app: WebApp): String =
        listOf(
                "default-src 'self'",
                "script-src 'self'",
                "style-src 'self' 'unsafe-inline'",
                "img-src 'self' data:",
                "connect-src 'self'",
                if (app.id == "store") "frame-src ${BuildConfig.STORE_URL}" else "frame-src 'none'",
                "base-uri 'none'",
                "form-action 'self'",
                "object-src 'none'",
            )
            .joinToString("; ")

    fun buildPlatform(): String =
        listOf(
                "default-src 'self'",
                "script-src 'self'",
                "style-src 'self' 'unsafe-inline'",
                "img-src 'self' data:",
                "connect-src 'self'",
                "frame-src 'none'",
                "base-uri 'none'",
                "form-action 'self'",
                "object-src 'none'",
            )
            .joinToString("; ")
}
