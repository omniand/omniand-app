package dev.omniand.hub.camera

import kotlin.math.min

/** Computes make-before-break refresh time from an issued credential's actual lifetime. */
object TurnRenewal {
    private const val NORMAL_MARGIN_MILLIS = 15 * 60 * 1000L

    fun delayMillis(issuedAtMillis: Long, expiresAtMillis: Long): Long {
        val lifetime = (expiresAtMillis - issuedAtMillis).coerceAtLeast(1L)
        val margin = min(NORMAL_MARGIN_MILLIS, lifetime / 4)
        return (lifetime - margin).coerceAtLeast(1L)
    }
}
