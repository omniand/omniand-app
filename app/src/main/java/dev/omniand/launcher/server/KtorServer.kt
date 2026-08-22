package dev.omniand.launcher.server

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.cio.toByteArray

/** Adapts HTTP calls to the host-aware router after transport authentication. */
object KtorServer {
    fun start(context: Context) {
        embeddedServer(CIO, host = "0.0.0.0", port = PlatformServer.PORT) {
                platformModule(context.applicationContext)
            }
            .start(wait = false)
    }

    /** Enforces transport limits before passing normalized request data to the Platform router. */
    private fun Application.platformModule(context: Context) {
        routing {
            route("/{remaining...}") {
                handle {
                    val body = call.receiveLimitedBody() ?: return@handle
                    call.respondPlatform(
                        PlatformServer.networkResponse(
                            context,
                            call.request.httpMethod.value,
                            call.request.uri,
                            call.request.headers["Host"].orEmpty(),
                            call.request.platformHeaders(),
                            body,
                            call.request.local.remoteAddress,
                        )
                    )
                }
            }
        }
    }

    /** Reads a bounded body and completes the call with 413 when the limit is exceeded. */
    private suspend fun io.ktor.server.application.ApplicationCall.receiveLimitedBody():
        ByteArray? {
        val declaredLength = request.headers["Content-Length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > PlatformServer.MAX_REQUEST_BODY) {
            respondBytes(
                "Request body is too large".toByteArray(),
                ContentType.Text.Plain,
                HttpStatusCode.PayloadTooLarge,
            )
            return null
        }
        val body = receiveChannel().toByteArray(PlatformServer.MAX_REQUEST_BODY + 1)
        if (body.size <= PlatformServer.MAX_REQUEST_BODY) return body
        respondBytes(
            "Request body is too large".toByteArray(),
            ContentType.Text.Plain,
            HttpStatusCode.PayloadTooLarge,
        )
        return null
    }

    private fun io.ktor.server.request.ApplicationRequest.platformHeaders(): Map<String, String> =
        headers.entries().associate { (name, values) ->
            name.lowercase() to values.joinToString(",")
        }

    /** Converts a framework-neutral Platform response into a Ktor response, preserving streams. */
    private suspend fun io.ktor.server.application.ApplicationCall.respondPlatform(
        response: PlatformServer.Response
    ) {
        response.headers.forEach { (name, value) -> this.response.headers.append(name, value) }
        val status = HttpStatusCode.fromValue(response.statusCode)
        val contentType = ContentType.parse(response.contentType)
        if (response.contentLength != null) {
            respondBytes(response.openBody().use { it.readBytes() }, contentType, status)
        } else {
            respondOutputStream(contentType, status) {
                response.openBody().use { it.copyTo(this) }
            }
        }
    }
}
