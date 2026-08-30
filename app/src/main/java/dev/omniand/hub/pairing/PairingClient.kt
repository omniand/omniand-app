package dev.omniand.hub.pairing

import dev.omniand.hub.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.json.JSONObject

/** Claims one scanned browser request, bootstrapping only when no credential exists. */
class PairingClient(private val identity: DeviceIdentity) {
    suspend fun claim(target: PairingTarget) {
        val existing =
            identity.credential()?.takeIf { identity.connectOrigin() == target.connectOrigin }
        val endpoint = if (existing == null) "device/bootstrap" else "pairing/claim"
        val client = HttpClient(CIO)
        try {
            val response =
                client.post("${target.connectOrigin}/api/$endpoint") {
                    contentType(ContentType.Application.Json)
                    if (existing != null) header("Authorization", "Bearer $existing")
                    setBody(
                        JSONObject()
                            .put("deviceId", identity.deviceId)
                            .put("secret", target.secret)
                            .toString()
                    )
                }
            val result = JSONObject(response.body<String>())
            if (response.status.value !in 200..299)
                error(result.optString("error", "Pairing failed"))
            val connectOrigin = result.getString("connectOrigin")
            require(connectOrigin == target.connectOrigin) { "Pairing relay origin mismatch" }
            val relayUrl = result.getString("relayUrl")
            val baseHost = result.getString("baseHost")
            validateRelayConfiguration(relayUrl, baseHost)
            identity.storeEnrollment(
                existing ?: result.getString("credential"),
                connectOrigin,
                relayUrl,
                baseHost,
            )
            PairingState.completed()
        } finally {
            client.close()
        }
    }

    private fun validateRelayConfiguration(relayUrl: String, baseHost: String) {
        val uri = java.net.URI(relayUrl)
        require(uri.scheme == "wss" || (BuildConfig.DEBUG && uri.scheme == "ws")) {
            "The pairing relay returned an insecure tunnel URL"
        }
        require(
            !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath == "/_omniand/tunnel/v1"
        ) {
            "The pairing relay returned an invalid tunnel URL"
        }
        require(
            baseHost == baseHost.lowercase() &&
                baseHost.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))
        ) {
            "The pairing relay returned an invalid platform host"
        }
    }
}

object PairingState {
    @Volatile
    var scanning = false
        private set

    @Volatile
    var error: String? = null
        private set

    fun started() {
        scanning = true
        error = null
    }

    fun failed(message: String) {
        scanning = false
        error = message
    }

    fun completed() {
        scanning = false
        error = null
    }

    fun stopped() {
        scanning = false
    }
}
