package dev.omniand.hub.camera

import android.content.Context
import dev.omniand.hub.network.OmniAndDns
import dev.omniand.hub.pairing.DeviceIdentity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TurnCredentials(
    val urls: List<String>,
    val androidUsername: String,
    val androidCredential: String,
    val browserUsername: String,
    val browserCredential: String,
    val expiresAtMillis: Long,
)

/** Retrieves one non-persistent, link-bound pair of TURN credentials from the Relay. */
class TurnCredentialsClient(private val context: Context) {
    suspend fun issue(publicLinkId: String): TurnCredentials =
        withContext(Dispatchers.IO) {
            val identity = DeviceIdentity(context)
            val origin = identity.connectOrigin() ?: error("Relay enrollment is required")
            val credential = identity.credential() ?: error("Relay enrollment is required")
            val client =
                HttpClient(OkHttp) {
                    engine { config { dns(OmniAndDns) } }
                    install(HttpTimeout) {
                        requestTimeoutMillis = TIMEOUT
                        connectTimeoutMillis = TIMEOUT
                        socketTimeoutMillis = TIMEOUT
                    }
                }
            try {
                val response =
                    client.post("$origin/api/device/turn-credentials") {
                        header("Authorization", "Bearer $credential")
                        header("X-OmniAnd-Device-Id", identity.deviceId)
                        contentType(ContentType.Application.Json)
                        setBody(JSONObject().put("publicLinkId", publicLinkId).toString())
                    }
                check(response.status.value in 200..299) { "TURN credentials unavailable" }
                val value = JSONObject(response.body<String>())
                check(value.optInt("version") == 1) { "Invalid TURN credentials" }
                val android = value.getJSONObject("android")
                val browser = value.getJSONObject("browser")
                TurnCredentials(
                    List(value.getJSONArray("urls").length()) {
                        value.getJSONArray("urls").getString(it)
                    },
                    android.getString("username"),
                    android.getString("credential"),
                    browser.getString("username"),
                    browser.getString("credential"),
                    value.getLong("expiresAt") * 1000,
                )
            } finally {
                client.close()
            }
        }

    private companion object {
        const val TIMEOUT = 8_000L
    }
}
