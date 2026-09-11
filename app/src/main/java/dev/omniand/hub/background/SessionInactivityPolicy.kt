package dev.omniand.hub.background

/** Monotonic absence deadline; reconnecting a tunnel does not extend an unattended session. */
internal class SessionInactivityPolicy {
    private var active = false
    private var absentSince: Long? = null

    @Synchronized
    fun start(now: Long) {
        active = true
        absentSince = now
    }

    @Synchronized
    fun stop() {
        active = false
        absentSince = null
    }

    @Synchronized
    fun presence(clients: Int, now: Long) {
        if (!active) return
        if (clients > 0) absentSince = null else if (absentSince == null) absentSince = now
    }

    @Synchronized fun deadline(): Long? = absentSince?.plus(300_000L)

    @Synchronized fun expired(now: Long): Boolean = active && deadline()?.let { now >= it } == true
}
