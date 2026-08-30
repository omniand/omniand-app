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
    suspend fun claim(secret: String) {
        val existing = identity.credential()
        val endpoint = if (existing == null) "device/bootstrap" else "pairing/claim"
        val client = HttpClient(CIO)
        try {
            val response =
                client.post("https://connect.${BuildConfig.PLATFORM_HOST}/api/$endpoint") {
                    contentType(ContentType.Application.Json)
                    if (existing != null) header("Authorization", "Bearer $existing")
                    setBody(
                        JSONObject()
                            .put("deviceId", identity.deviceId)
                            .put("secret", secret)
                            .toString()
                    )
                }
            val result = JSONObject(response.body<String>())
            if (response.status.value !in 200..299)
                error(result.optString("error", "Pairing failed"))
            if (existing == null) identity.storeCredential(result.getString("credential"))
            PairingState.completed()
        } finally {
            client.close()
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
}
