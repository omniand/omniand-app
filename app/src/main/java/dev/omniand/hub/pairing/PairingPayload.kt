package dev.omniand.hub.pairing

import java.net.URI

/** Strict parser for the only QR payload accepted by the native enrollment flow. */
object PairingPayload {
    fun secret(value: String, baseHost: String): String? =
        runCatching {
                val uri = URI(value)
                if (
                    uri.scheme != "https" ||
                        uri.host != "connect.${baseHost.lowercase()}" ||
                        uri.port != -1 ||
                        uri.rawQuery != null ||
                        uri.rawFragment != null ||
                        uri.userInfo != null
                )
                    return null
                Regex("^/pair/([0-9a-f]{64})$").matchEntire(uri.rawPath)?.groupValues?.get(1)
            }
            .getOrNull()
}
