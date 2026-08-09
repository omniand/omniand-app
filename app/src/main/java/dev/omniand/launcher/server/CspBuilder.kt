package dev.omniand.launcher.server

import dev.omniand.launcher.webapps.WebApp

object CspBuilder {
    fun build(app: WebApp): String = listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data:",
        "connect-src 'self'",
        "frame-src 'none'",
        "base-uri 'none'",
        "object-src 'none'"
    ).joinToString("; ")

    fun buildShell(host: String): String = listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data:",
        "connect-src 'self'",
        "frame-src http://$host:8081 http://$host:8082",
        "base-uri 'none'",
        "object-src 'none'"
    ).joinToString("; ")
}
