package dev.omniand.hub.pairing

import java.net.URI

/** A trusted-transport pairing destination carried entirely by a scanned QR code. */
data class PairingTarget(val connectOrigin: String, val secret: String) {
    companion object {
        /**
         * Accepts any HTTPS host while rejecting ambiguous URL components and malformed secrets.
         */
        fun parse(value: String): PairingTarget? =
            runCatching {
                    val uri = URI(value)
                    if (
                        uri.scheme != "https" ||
                            uri.host.isNullOrBlank() ||
                            uri.port != -1 ||
                            uri.rawQuery != null ||
                            uri.rawFragment != null ||
                            uri.userInfo != null
                    )
                        return null
                    val secret =
                        Regex("^/pair/([0-9a-f]{64})$")
                            .matchEntire(uri.rawPath)
                            ?.groupValues
                            ?.get(1) ?: return null
                    val origin = URI("https", null, uri.host.lowercase(), -1, null, null, null)
                    PairingTarget(origin.toASCIIString(), secret)
                }
                .getOrNull()
    }
}
