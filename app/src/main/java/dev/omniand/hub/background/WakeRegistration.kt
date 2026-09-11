package dev.omniand.hub.background

import android.content.Context
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.omniand.hub.network.OmniAndDns
import dev.omniand.hub.pairing.DeviceIdentity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.http.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Durable, network-constrained synchronization for token, mode and enrollment changes. */
object WakeRegistration {
    private fun prefs(c: Context) =
        c.getSharedPreferences("wake-registration", Context.MODE_PRIVATE)

    fun readiness(c: Context): String =
        if (FirebaseApp.getApps(c).isEmpty()) "not-configured"
        else prefs(c).getString("status", "pending")!!

    fun status(c: Context, value: String) {
        prefs(c).edit().putString("status", value).apply()
    }

    fun enqueue(c: Context) {
        status(c, "pending")
        WorkManager.getInstance(c)
            .enqueueUniqueWork(
                "wake-registration",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WakeRegistrationWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
    }
}

/** Retries enrollment updates even when token retrieval fails, keeping Disabled authoritative. */
class WakeRegistrationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (DeviceIdentity(c).credential() == null) {
            WakeRegistration.status(c, "not-paired")
            return Result.success()
        }
        return try {
            var tokenFailure = false
            val token =
                try {
                    if (FirebaseApp.getApps(c).isEmpty()) null
                    else
                        kotlinx.coroutines.withTimeout(20_000) {
                            FirebaseMessaging.getInstance().token.await()
                        }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    tokenFailure = true
                    null
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    tokenFailure = true
                    null
                }
            val response =
                WakeClient(c)
                    .request(
                        "",
                        "PUT",
                        JSONObject()
                            .put("mode", BackgroundHostingManager.mode(c))
                            .put("token", token ?: JSONObject.NULL),
                    )
            WakeRegistration.status(
                c,
                if (tokenFailure) "failed"
                else if (token == null) "not-configured"
                else if (response.optBoolean("deliveryConfigured")) "ready"
                else "relay-not-configured",
            )
            if (tokenFailure) Result.retry() else Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WakeRegistration.status(c, "failed")
            Result.retry()
        }
    }
}

/** Wake APIs use only the enrolled HTTPS origin and device credentials, never app transport. */
class WakeClient(private val context: Context) {
    suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject {
        val identity = DeviceIdentity(context)
        val origin = checkNotNull(identity.connectOrigin()) { "Pair a browser first" }
        val credential = checkNotNull(identity.credential()) { "Device enrollment is unavailable" }
        val client =
            HttpClient(OkHttp) {
                followRedirects = false
                engine {
                    config {
                        dns(OmniAndDns)
                        callTimeout(15, TimeUnit.SECONDS)
                        followRedirects(false)
                    }
                }
            }
        try {
            val response =
                client.request("$origin/api/device/wake$path") {
                    this.method = HttpMethod.parse(method)
                    header("Authorization", "Bearer $credential")
                    header("X-OmniAnd-Device-Id", identity.deviceId)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            check(response.status.value in 200..299) {
                "Request is unavailable, expired, or already answered (${response.status.value})"
            }
            return if (response.status.value == 204) JSONObject()
            else JSONObject(response.body<String>())
        } finally {
            client.close()
        }
    }
}
