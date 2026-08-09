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

    fun buildShell(appOrigins: List<String>): String = listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data:",
        "connect-src 'self'",
        "frame-src ${appOrigins.joinToString(" ")}",
        "base-uri 'none'",
        "object-src 'none'"
    ).joinToString("; ")
}
