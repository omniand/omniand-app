package dev.omniand.launcher.server

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.util.cio.toByteArray

/** Adapts network HTTP calls to the same host-aware router used by local Android WebViews. */
object KtorServer {
    fun start(context: Context) {
        embeddedServer(CIO, host = "0.0.0.0", port = PlatformServer.PORT) {
                platformModule(context.applicationContext)
            }
            .start(wait = false)
    }

    /** Enforces transport limits before passing normalized request data to the Platform router. */
    private fun Application.platformModule(context: Context) {
        intercept(ApplicationCallPipeline.Call) {
            val declaredLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (declaredLength != null && declaredLength > PlatformServer.MAX_REQUEST_BODY) {
                call.respondBytes(
                    "Request body is too large".toByteArray(),
                    ContentType.Text.Plain,
                    HttpStatusCode.PayloadTooLarge,
                )
                finish()
                return@intercept
            }
            val body = call.receiveChannel().toByteArray(PlatformServer.MAX_REQUEST_BODY + 1)
            if (body.size > PlatformServer.MAX_REQUEST_BODY) {
                call.respondBytes(
                    "Request body is too large".toByteArray(),
                    ContentType.Text.Plain,
                    HttpStatusCode.PayloadTooLarge,
                )
                finish()
                return@intercept
            }
            val headers =
                call.request.headers.entries().associate { (name, values) ->
                    name.lowercase() to values.joinToString(",")
                }
            val host = call.request.headers["Host"]?.substringBefore(':') ?: "127.0.0.1"
            val response =
                PlatformServer.networkResponse(
                    context,
                    call.request.httpMethod.value,
                    call.request.uri,
                    host,
                    headers,
                    body,
                )
            response.headers.forEach { (name, value) -> call.response.headers.append(name, value) }
            val status = HttpStatusCode.fromValue(response.statusCode)
            val contentType = ContentType.parse(response.contentType)
            if (response.contentLength != null) {
                call.respondBytes(response.openBody().use { it.readBytes() }, contentType, status)
            } else {
                call.respondOutputStream(contentType, status) {
                    response.openBody().use { it.copyTo(this) }
                }
            }
            finish()
        }
    }
}
