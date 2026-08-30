package dev.omniand.hub.pairing

import android.content.Context
import dev.omniand.hub.network.OmniAndDns
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Calls the enrolled Relay control plane without disclosing its credential to Web content. */
class RemoteLinksClient(private val context: Context) {
    fun list(): JSONArray = request("GET") as JSONArray

    fun rename(publicLinkId: String, name: String?): JSONObject =
        request("PUT", publicLinkId, JSONObject().put("name", name)) as JSONObject

    fun revoke(publicLinkId: String) {
        request("DELETE", publicLinkId)
    }

    private fun request(
        method: String,
        publicLinkId: String? = null,
        body: JSONObject? = null,
    ): Any {
        val identity = DeviceIdentity(context)
        val origin =
            identity.connectOrigin()
                ?: throw RemoteLinksFailure(
                    "relay-unenrolled",
                    "This phone is not enrolled with a Relay",
                )
        val credential =
            identity.credential()
                ?: throw RemoteLinksFailure(
                    "relay-unenrolled",
                    "This phone is not enrolled with a Relay",
                )
        val suffix = publicLinkId?.let { "/${validateId(it)}" }.orEmpty()
        val client =
            HttpClient(OkHttp) {
                engine { config { dns(OmniAndDns) } }
                install(HttpTimeout) {
                    requestTimeoutMillis = TIMEOUT
                    connectTimeoutMillis = TIMEOUT
                    socketTimeoutMillis = TIMEOUT
                }
            }
        return try {
            runBlocking(Dispatchers.IO) {
                val url = "$origin/api/device/links$suffix"
                val response =
                    when (method) {
                        "GET" -> client.get(url) { authenticate(identity.deviceId, credential) }
                        "PUT" ->
                            client.put(url) {
                                authenticate(identity.deviceId, credential)
                                contentType(ContentType.Application.Json)
                                setBody(body.toString())
                            }
                        else -> client.delete(url) { authenticate(identity.deviceId, credential) }
                    }
                val text = response.body<String>()
                if (response.status.value !in 200..299) {
                    val message =
                        runCatching { JSONObject(text).optString("error") }
                            .getOrNull()
                            .takeUnless { it.isNullOrBlank() } ?: "Relay request failed"
                    throw RemoteLinksFailure(
                        if (response.status.value == 401) "relay-authentication"
                        else "relay-request-failed",
                        message,
                    )
                }
                if (method == "GET") JSONArray(text)
                else if (method == "PUT") JSONObject(text) else JSONObject()
            }
        } catch (error: RemoteLinksFailure) {
            throw error
        } catch (error: Exception) {
            val message =
                if (error is SocketTimeoutException) "The Relay timed out"
                else "The Relay is unreachable"
            throw RemoteLinksFailure("relay-unreachable", message)
        } finally {
            client.close()
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticate(
        deviceId: String,
        credential: String,
    ) {
        header("Authorization", "Bearer $credential")
        header("X-OmniAnd-Device-Id", deviceId)
    }

    private fun validateId(value: String): String {
        require(value.matches(Regex("[a-z2-7]{26}"))) { "invalid public link ID" }
        return value
    }

    private companion object {
        const val TIMEOUT = 8_000L
    }
}

class RemoteLinksFailure(val code: String, message: String) : IllegalStateException(message)
