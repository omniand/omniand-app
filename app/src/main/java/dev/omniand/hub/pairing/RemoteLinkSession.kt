package dev.omniand.hub.pairing

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class StableHost(val appId: String, val publicLinkId: String)

/** Independently verifies host-only relay sessions before Android serves remote content. */
object RemoteLinkSession {
    private const val COOKIE = "omniand_link"

    fun parseHost(host: String, baseHost: String): StableHost? {
        val label = host.lowercase().removeSuffix(".${baseHost.lowercase()}")
        if (label == host.lowercase() || label.contains('.')) return null
        val split = label.lastIndexOf('-')
        if (split <= 0) return null
        val app = label.substring(0, split)
        val link = label.substring(split + 1)
        if (!link.matches(Regex("[a-z2-7]{26}"))) return null
        if (app != "platform" && !app.matches(Regex("[a-z][a-z0-9-]{0,31}"))) return null
        return StableHost(app, link)
    }

    fun verify(
        context: Context,
        host: StableHost,
        cookieHeader: String?,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        val credential = DeviceIdentity(context).credential() ?: return false
        val token =
            cookieHeader
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("$COOKIE=") }
                ?.substringAfter('=') ?: return false
        val pieces = token.split('.')
        if (pieces.size != 2) return false
        return runCatching {
                val keyMac = MessageDigest.getInstance("SHA-256")
                keyMac.update("omniand-link-session-v1\u0000".toByteArray(StandardCharsets.UTF_8))
                val key = keyMac.digest(credential.toByteArray(StandardCharsets.UTF_8))
                val mac =
                    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                val expected = mac.doFinal(pieces[0].toByteArray(StandardCharsets.US_ASCII))
                val actual =
                    Base64.decode(pieces[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                if (!MessageDigest.isEqual(expected, actual)) return false
                val payload =
                    String(
                            Base64.decode(
                                pieces[0],
                                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                            ),
                            StandardCharsets.UTF_8,
                        )
                        .split('\n')
                payload.size == 4 &&
                    payload[0] == DeviceIdentity(context).deviceId &&
                    payload[1] == host.publicLinkId &&
                    payload[2] == host.appId &&
                    payload[3].toLong() > nowSeconds
            }
            .getOrDefault(false)
    }
}
