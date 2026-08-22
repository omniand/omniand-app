package dev.omniand.launcher.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Owns process-lifetime desktop pairing requests and approved browser sessions. */
object DesktopPairing {
    const val SESSION_COOKIE = "omniand_desktop_session"
    const val REQUEST_COOKIE = "omniand_pairing_request"
    private const val REQUEST_LIFETIME_MILLIS = 2 * 60 * 1000L
    private const val REQUEST_COOLDOWN_MILLIS = 30 * 1000L
    private const val MAX_PENDING_REQUESTS = 8
    private val random = SecureRandom()
    private val requests = ConcurrentHashMap<String, Request>()
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val lastCreatedByPeer = ConcurrentHashMap<String, Long>()

    data class Request(
        val id: String,
        val peerAddress: String,
        val userAgent: String,
        val createdAt: Long,
        @Volatile var decision: Decision = Decision.PENDING,
    )

    enum class Decision {
        PENDING,
        APPROVED,
        DENIED,
    }

    @Synchronized
    fun create(
        peerAddress: String,
        userAgent: String,
        now: Long = System.currentTimeMillis(),
    ): Request {
        expire(now)
        if (
            requests.values.count { it.decision == Decision.PENDING } >= MAX_PENDING_REQUESTS ||
                lastCreatedByPeer[peerAddress]?.let { now - it < REQUEST_COOLDOWN_MILLIS } == true
        )
            throw Throttled()
        val request =
            Request(
                randomToken(16),
                peerAddress,
                userAgent.filterNot(Char::isISOControl).take(200),
                now,
            )
        requests[request.id] = request
        lastCreatedByPeer[peerAddress] = now
        return request
    }

    fun pending(): List<Request> {
        expire(System.currentTimeMillis())
        return requests.values.filter { it.decision == Decision.PENDING }.sortedBy { it.createdAt }
    }

    fun pending(id: String): Request? = requests[id]?.takeIf { it.decision == Decision.PENDING }

    @Synchronized
    fun decide(id: String, approved: Boolean): Boolean {
        val request = requests[id] ?: return false
        if (request.decision != Decision.PENDING) return false
        request.decision = if (approved) Decision.APPROVED else Decision.DENIED
        return true
    }

    @Synchronized
    fun claim(id: String): Claim {
        expire(System.currentTimeMillis())
        val request = requests[id] ?: return Claim(Decision.DENIED)
        return when (request.decision) {
            Decision.PENDING -> Claim(Decision.PENDING)
            Decision.DENIED -> {
                requests.remove(id)
                Claim(Decision.DENIED)
            }
            Decision.APPROVED -> {
                requests.remove(id)
                val token = randomToken(32)
                sessions += token
                Claim(Decision.APPROVED, token)
            }
        }
    }

    fun verify(cookieHeader: String?): Boolean {
        val token = cookie(cookieHeader, SESSION_COOKIE) ?: return false
        return sessions.any {
            MessageDigest.isEqual(
                it.toByteArray(Charsets.US_ASCII),
                token.toByteArray(Charsets.US_ASCII),
            )
        }
    }

    fun requestId(cookieHeader: String?): String? = cookie(cookieHeader, REQUEST_COOKIE)

    internal fun reset() {
        requests.clear()
        sessions.clear()
        lastCreatedByPeer.clear()
    }

    class Throttled : Exception()

    data class Claim(val decision: Decision, val token: String? = null)

    private fun expire(now: Long) {
        requests.entries.removeIf { now - it.value.createdAt > REQUEST_LIFETIME_MILLIS }
    }

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun cookie(header: String?, name: String): String? =
        header
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf(String::isNotEmpty)
}
