package dev.omniand.hub.server

import android.content.Context
import dev.omniand.hub.media.MediaUploadStore
import dev.omniand.hub.sms.MmsUploadStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.cio.toByteArray
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Runs the CIO transport with explicit route registration for every Platform API family. */
object KtorServer {
    fun start(context: Context) {
        embeddedServer(CIO, host = "0.0.0.0", port = PlatformServer.PORT) {
                platformModule(context.applicationContext)
            }
            .start(wait = false)
    }

    /** Registers API methods first and reserves the final wildcard for non-API assets. */
    internal fun Application.platformModule(context: Context) {
        routing {
            pairingRoutes(context)
            mediaRoutes(context)
            contactsRoutes(context)
            smsRoutes(context)
            applicationRoutes(context)
            route("/{remaining...}") { get { call.staticOrNotFound(context) } }
        }
    }

    private suspend fun ApplicationCall.staticOrNotFound(context: Context) {
        if (request.uri.substringBefore('?').startsWith("/api/")) {
            respondJson(404, "not-found", "API route not found")
            return
        }
        forward(context)
    }

    private fun Route.pairingRoutes(context: Context) {
        route("/api/pairing") {
            post("/request") { call.forward(context) }
            get("/status") { call.forward(context) }
        }
    }

    private fun Route.mediaRoutes(context: Context) {
        route("/api/media") {
            get { call.forward(context) }
            get("/folders") { call.forward(context) }
            get("/setup") { call.forward(context) }
            post("/setup/request") { call.forward(context) }
            get("/events") { call.forward(context) }
            post("/delete") { call.forward(context) }
            post("/uploads") { call.receiveUpload(context, UploadKind.MEDIA) }
            get("/{id}") { call.forward(context) }
            get("/{id}/thumbnail") { call.forward(context) }
            get("/{id}/content") { call.forward(context) }
        }
    }

    private fun Route.contactsRoutes(context: Context) {
        route("/api/contacts") {
            get { call.forward(context) }
            post { call.forward(context) }
            get("/setup") { call.forward(context) }
            post("/setup/request") { call.forward(context) }
            get("/events") { call.forward(context) }
            get("/accounts") { call.forward(context) }
            post("/matches") { call.forward(context) }
            get("/{key}") { call.forward(context) }
            put("/{key}") { call.forward(context) }
            delete("/{key}") { call.forward(context) }
            get("/{key}/photo") { call.forward(context) }
            put("/{key}/photo") { call.forward(context) }
            delete("/{key}/photo") { call.forward(context) }
        }
    }

    private fun Route.smsRoutes(context: Context) {
        route("/api/sms") {
            get { call.forward(context) }
            get("/setup") { call.forward(context) }
            post("/setup/request") { call.forward(context) }
            get("/events") { call.forward(context) }
            get("/threads") { call.forward(context) }
            get("/threads/{id}/messages") { call.forward(context) }
            delete("/threads/{id}") { call.forward(context) }
            post("/threads/{id}/read") { call.forward(context) }
            post("/uploads") { call.receiveUpload(context, UploadKind.SMS) }
            post("/messages") { call.forward(context) }
            get("/messages/{id}") { call.forward(context) }
            delete("/messages/{id}") { call.forward(context) }
            post("/messages/{id}/read") { call.forward(context) }
            post("/messages/{id}/download") { call.forward(context) }
            get("/messages/{id}/parts/{partId}") { call.forward(context) }
        }
    }

    private fun Route.applicationRoutes(context: Context) {
        route("/api/apps") {
            get("/catalog") { call.forward(context) }
            get("/catalog/{id}/icon") { call.forward(context) }
            post("/catalog/{id}/install") { call.forward(context) }
            get("/operations/{id}") { call.forward(context) }
            get("/web") { call.forward(context) }
            get("/web/{id}/icon") { call.forward(context) }
            post("/web/{id}/uninstall") { call.forward(context) }
        }
    }

    /** Authenticates and bounds ordinary requests before invoking existing business handlers. */
    private suspend fun ApplicationCall.forward(context: Context) {
        val headers = request.platformHeaders()
        val requestPath = request.uri.substringBefore('?')
        if (requestPath.startsWith("/api/") && !requestPath.startsWith("/api/pairing/")) {
            val authenticated =
                PlatformServer.authenticateRequest(
                    context,
                    request.headers["Host"].orEmpty(),
                    request.local.remoteAddress,
                    request.httpMethod.value,
                    requestPath,
                    headers,
                )
            if (authenticated == null) {
                respond(
                    PlatformServer.networkResponse(
                        context,
                        request.httpMethod.value,
                        request.uri,
                        request.headers["Host"].orEmpty(),
                        headers,
                        byteArrayOf(),
                        request.local.remoteAddress,
                    )
                )
                return
            }
        }
        val body = receiveLimitedBody() ?: return
        respond(
            PlatformServer.networkResponse(
                context,
                request.httpMethod.value,
                request.uri,
                request.headers["Host"].orEmpty(),
                headers,
                body,
                request.local.remoteAddress,
            )
        )
    }

    /**
     * Authenticates before consuming a single-file multipart upload into bounded temporary storage.
     */
    private suspend fun ApplicationCall.receiveUpload(context: Context, kind: UploadKind) {
        val headers = request.platformHeaders()
        val requestContext =
            PlatformServer.authenticateRequest(
                context,
                request.headers["Host"].orEmpty(),
                request.local.remoteAddress,
                request.httpMethod.value,
                request.uri.substringBefore('?'),
                headers,
            )
        if (requestContext == null) {
            respondJson(401, "authentication-required", "Unauthorized")
            return
        }
        val capability = if (kind == UploadKind.MEDIA) "media.write" else "sms.send"
        if (!PlatformServer.hasCapability(context, requestContext, capability)) {
            respondJson(403, "missing-capability", "Missing capability: $capability")
            return
        }

        val limit =
            if (kind == UploadKind.MEDIA) MediaUploadStore.MAX_FILE else MmsUploadStore.MAX_SIZE
        val directory = File(context.cacheDir, "multipart-uploads").apply { mkdirs() }
        val temporary = File(directory, UUID.randomUUID().toString())
        var fileName: String? = null
        var mime: String? = null
        var digest: String? = null
        var folder: String? = null
        var sawFile = false
        var invalid = false
        try {
            val multipart = receiveMultipart(formFieldLimit = MediaUploadStore.MAX_FILE + 64 * 1024)
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "sha256" ->
                                    if (digest != null) invalid = true else digest = part.value
                                "folder" ->
                                    if (kind != UploadKind.MEDIA || folder != null) invalid = true
                                    else folder = part.value
                                else -> invalid = true
                            }
                        }
                        is PartData.FileItem -> {
                            if (part.name != "file" || sawFile) {
                                invalid = true
                            } else {
                                sawFile = true
                                fileName = part.originalFileName
                                mime = part.contentType?.toString()
                                FileOutputStream(temporary).use { output ->
                                    val channel = part.provider()
                                    val buffer = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val count = channel.readAvailable(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        total += count
                                        if (total > limit) throw UploadTooLarge()
                                        output.write(buffer, 0, count)
                                    }
                                    output.fd.sync()
                                }
                            }
                        }
                        else -> invalid = true
                    }
                } finally {
                    part.dispose()
                }
            }
            if (
                invalid ||
                    !sawFile ||
                    fileName.isNullOrBlank() ||
                    mime.isNullOrBlank() ||
                    digest == null
            )
                throw InvalidMultipart()
            val result =
                if (kind == UploadKind.MEDIA)
                    MediaUploadStore(context)
                        .publish(
                            requestContext.app!!.id,
                            fileName!!,
                            mime!!,
                            digest!!,
                            temporary,
                            folder,
                        )
                else
                    MmsUploadStore(context)
                        .stage(
                            requestContext.app!!.id,
                            fileName!!,
                            mime!!,
                            digest!!,
                            temporary,
                        )
            respondJson(HttpStatusCode.OK, result)
        } catch (_: UploadTooLarge) {
            respondJson(413, "upload-too-large", "Upload exceeds the file-size limit")
        } catch (_: InvalidMultipart) {
            respondJson(400, "invalid-upload", "Exactly one file and sha256 field are required")
        } catch (error: MediaUploadStore.Invalid) {
            respondJson(
                if (error.code == "staging-limit") 413 else 400,
                error.code,
                "Invalid media upload",
            )
        } catch (error: MmsUploadStore.Invalid) {
            respondJson(400, error.code, "Invalid MMS attachment upload")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            respondJson(400, "invalid-upload", "Invalid multipart upload")
        } finally {
            temporary.delete()
        }
    }

    /** Reads a non-upload body with the Platform's fixed 256 KiB ceiling. */
    private suspend fun ApplicationCall.receiveLimitedBody(): ByteArray? {
        val declaredLength = request.headers["Content-Length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > PlatformServer.MAX_REQUEST_BODY) {
            respondJson(413, "request-too-large", "Request body is too large")
            return null
        }
        val body = receiveChannel().toByteArray(PlatformServer.MAX_REQUEST_BODY + 1)
        if (body.size <= PlatformServer.MAX_REQUEST_BODY) return body
        respondJson(413, "request-too-large", "Request body is too large")
        return null
    }

    private fun io.ktor.server.request.ApplicationRequest.platformHeaders(): Map<String, String> =
        headers.entries().associate { (name, values) ->
            name.lowercase() to values.joinToString(",")
        }

    private suspend fun ApplicationCall.respondJson(
        code: Int,
        stableCode: String,
        message: String,
    ) =
        respondJson(
            HttpStatusCode.fromValue(code),
            JSONObject().put("error", message).put("code", stableCode),
        )

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, value: JSONObject) {
        response.headers.append("Cache-Control", "no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        response.headers.append("Referrer-Policy", "no-referrer")
        respondBytes(value.toString().toByteArray(), ContentType.Application.Json, status)
    }

    private enum class UploadKind {
        MEDIA,
        SMS,
    }

    private class UploadTooLarge : Exception()

    private class InvalidMultipart : Exception()
}
