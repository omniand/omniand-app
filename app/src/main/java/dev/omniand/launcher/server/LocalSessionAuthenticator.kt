package dev.omniand.launcher.server

import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Issues and verifies process-lifetime, host-bound credentials for OmniAnd WebViews. */
object LocalSessionAuthenticator {
    const val COOKIE_NAME = "omniand_local_session"
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)

    fun tokenFor(hostname: String): String = deriveToken(secret, normalizeHost(hostname))

    fun verify(hostname: String, cookieHeader: String?): Boolean {
        val supplied =
            cookieHeader
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.substringBefore('=') == COOKIE_NAME }
                ?.substringAfter('=', "") ?: return false
        return constantTimeEquals(tokenFor(hostname), supplied)
    }

    internal fun deriveToken(key: ByteArray, hostname: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(normalizeHost(hostname).toByteArray(Charsets.UTF_8)).toHex()
    }

    internal fun constantTimeEquals(expected: String, supplied: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            supplied.toByteArray(Charsets.US_ASCII),
        )

    internal fun isLoopback(address: String): Boolean =
        runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)

    private fun normalizeHost(hostname: String) = hostname.trim().lowercase()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
