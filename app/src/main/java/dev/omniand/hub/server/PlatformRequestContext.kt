package dev.omniand.hub.server

import dev.omniand.hub.webapps.WebApp

/** Carries the authenticated transport identity separately from Web capability identity. */
data class PlatformRequestContext(
    val authority: String,
    val hostname: String,
    val transport: Transport,
    val phoneClient: Boolean,
    val app: WebApp?,
) {
    enum class Transport {
        LOOPBACK_HTTP,
        DESKTOP_HTTP,
    }
}
