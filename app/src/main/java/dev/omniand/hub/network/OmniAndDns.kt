package dev.omniand.hub.network

import android.os.Build
import dev.omniand.hub.BuildConfig
import java.net.InetAddress
import okhttp3.Dns

/**
 * Keeps TLS host validation while making host-loopback development domains reach the emulator host.
 */
object OmniAndDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        return route(
            hostname = hostname,
            resolved = resolved,
            debugEmulator = BuildConfig.DEBUG && isEmulator(),
            emulatorHost = InetAddress.getByName(EMULATOR_HOST),
        )
    }

    internal fun route(
        hostname: String,
        resolved: List<InetAddress>,
        debugEmulator: Boolean,
        emulatorHost: InetAddress,
    ): List<InetAddress> =
        if (
            debugEmulator &&
                hostname != "localhost" &&
                resolved.isNotEmpty() &&
                resolved.all(InetAddress::isLoopbackAddress)
        ) {
            listOf(emulatorHost)
        } else {
            resolved
        }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.PRODUCT.contains("sdk")

    private const val EMULATOR_HOST = "10.0.2.2"
}
