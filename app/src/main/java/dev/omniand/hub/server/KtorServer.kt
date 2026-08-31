package dev.omniand.hub.server

import android.content.Context
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.camera.CameraSessionManager
import dev.omniand.hub.camera.CameraSignalValidator
import dev.omniand.hub.media.MediaUploadStore
import dev.omniand.hub.pairing.DeviceIdentity
import dev.omniand.hub.pairing.RemoteLinkSession
import dev.omniand.hub.services.FilesService
import dev.omniand.hub.sms.MmsUploadStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.cio.toByteArray
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    internal fun Application.platformModule(
        context: Context,
        websocketAccess: suspend ApplicationCall.() -> WebSocketAccess = {
            authorizeTestWebSocket(context)
        },
        websocketTickPeriod: Duration = 10.seconds,
    ) {
        install(WebSockets) {
            pingPeriodMillis = 15.seconds.inWholeMilliseconds
            timeoutMillis = 10.seconds.inWholeMilliseconds
            maxFrameSize = CAMERA_WEBSOCKET_MAX_FRAME_SIZE
            masking = false
        }
        routing {
            mediaRoutes(context)
            contactsRoutes(context)
            filesRoutes(context)
            smsRoutes(context)
            applicationRoutes(context)
            hubRoutes(context)
            cameraRoutes(context)
            testRoutes(websocketAccess, websocketTickPeriod)
            route("/{remaining...}") { get { call.staticOrNotFound(context) } }
        }
    }

    /** Keeps the permanent diagnostic socket isolated to the authenticated OmniAnd Test origin. */
    private fun Route.testRoutes(
        access: suspend ApplicationCall.() -> WebSocketAccess,
        tickPeriod: Duration,
    ) {
        route("/api/test/websocket") {
            install(TestWebSocketAuthorization) {
                authorize = access
            }
            webSocket { runTestWebSocket(tickPeriod) }
        }
    }

    /** Emits server traffic independently while accepting only bounded JSON text probes. */
    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.runTestWebSocket(
        tickPeriod: Duration
    ) {
        send(Frame.Text(JSONObject().put("type", "ready").toString()))
        val ticker = launch {
            var sequence = 0L
            while (true) {
                delay(tickPeriod)
                send(
                    Frame.Text(
                        JSONObject().put("type", "tick").put("sequence", ++sequence).toString()
                    )
                )
            }
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Text probes required"))
                    break
                }
                val text = frame.readText()
                if (text.toByteArray().size > TEST_WEBSOCKET_MAX_FRAME_SIZE) {
                    close(CloseReason(CloseReason.Codes.TOO_BIG, "Probe is too large"))
                    break
                }
                val probe = runCatching { JSONObject(text) }.getOrNull()
                if (
                    probe == null ||
                        probe.optString("type") != "probe" ||
                        !probe.has("id") ||
                        probe.opt("id") !is String ||
                        probe.optString("id").isBlank() ||
                        probe.optString("id").length > TEST_WEBSOCKET_MAX_PROBE_ID ||
                        probe.length() != 2
                ) {
                    close(CloseReason(CloseReason.Codes.NOT_CONSISTENT, "Invalid probe"))
                    break
                }
                send(Frame.Text(text))
            }
        } finally {
            ticker.cancel()
        }
    }

    private fun ApplicationCall.authorizeTestWebSocket(context: Context): WebSocketAccess {
        val requestContext =
            PlatformServer.authenticateRequest(
                context,
                request.headers["Host"].orEmpty(),
                request.local.remoteAddress,
                "GET",
                "/api/test/websocket",
                request.platformHeaders(),
            )
        return authorizeTestWebSocket(requestContext, request.headers["Origin"])
    }

    internal fun authorizeTestWebSocket(
        request: PlatformRequestContext?,
        origin: String?,
    ): WebSocketAccess {
        if (request == null) return WebSocketAccess.UNAUTHORIZED
        if (request.app?.id != TEST_APP_ID) return WebSocketAccess.FORBIDDEN
        val expectedOrigin =
            when (request.transport) {
                PlatformRequestContext.Transport.LOOPBACK_HTTP -> "http://${request.authority}"
                PlatformRequestContext.Transport.DESKTOP_HTTP -> "https://${request.hostname}"
            }
        return if (origin == expectedOrigin) WebSocketAccess.ALLOWED else WebSocketAccess.FORBIDDEN
    }

    private suspend fun ApplicationCall.staticOrNotFound(context: Context) {
        if (request.uri.substringBefore('?').startsWith("/api/")) {
            respondJson(404, "not-found", "API route not found")
            return
        }
        forward(context)
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

    private fun Route.filesRoutes(context: Context) {
        route("/api/files") {
            get("/setup") { call.forward(context) }
            post("/setup/request") { call.forward(context) }
            get("/roots") { call.forward(context) }
            get("/entries") { call.forward(context) }
            get("/entries/{id}") { call.forward(context) }
            get("/entries/{id}/content") { call.forward(context) }
            get("/search") { call.forward(context) }
            get("/recents") { call.forward(context) }
            get("/favorites") { call.forward(context) }
            post("/favorites") { call.forward(context) }
            get("/events") { call.forward(context) }
            post("/folders") { call.forward(context) }
            post("/rename") { call.forward(context) }
            post("/jobs") { call.forward(context) }
            get("/jobs/{id}") { call.forward(context) }
            delete("/jobs/{id}") { call.forward(context) }
            post("/uploads") { call.receiveUpload(context, UploadKind.FILES) }
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

    private fun Route.hubRoutes(context: Context) {
        route("/api/hub") {
            get("/settings") { call.forward(context) }
            put("/settings/background-hosting") { call.forward(context) }
            post("/connect-computer") { call.forward(context) }
            get("/remote-links") { call.forward(context) }
            put("/remote-links/{id}") { call.forward(context) }
            delete("/remote-links/{id}") { call.forward(context) }
            post("/permissions/{group}/request") { call.forward(context) }
            get("/presence") { call.forward(context) }
        }
    }

    /** Camera signaling remains a normal authenticated WebSocket and never carries media bytes. */
    private fun Route.cameraRoutes(context: Context) {
        route("/api/camera/webrtc") {
            install(TestWebSocketAuthorization) {
                authorize = { authorizeCameraWebSocket(context) }
            }
            webSocket {
                android.util.Log.i("OmniAndCamera", "camera websocket connected")
                val manager = CameraSessionManager.instance(context)
                val baseHost = DeviceIdentity(context).baseHost() ?: BuildConfig.PLATFORM_HOST
                val stableHost =
                    RemoteLinkSession.parseHost(
                        call.request.headers["Host"].orEmpty().substringBefore(':').lowercase(),
                        baseHost,
                    )
                        ?: run {
                            close(
                                CloseReason(
                                    CloseReason.Codes.VIOLATED_POLICY,
                                    "Invalid remote host",
                                )
                            )
                            return@webSocket
                        }
                val viewer =
                    manager.openViewer(
                        call.request.headers["User-Agent"].orEmpty(),
                        stableHost.publicLinkId,
                    )
                val receiver = launch {
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.CANNOT_ACCEPT,
                                        "Camera signaling requires JSON text",
                                    )
                                )
                                break
                            }
                            val message = frame.readText()
                            if (message.toByteArray().size > CAMERA_WEBSOCKET_MAX_FRAME_SIZE) {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.TOO_BIG,
                                        "Camera signal is too large",
                                    )
                                )
                                break
                            }
                            val signal = runCatching { JSONObject(message) }.getOrNull()
                            val validationError =
                                if (signal == null) "invalid-json"
                                else CameraSignalValidator.error(signal)
                            if (validationError != null) {
                                android.util.Log.w(
                                    "OmniAndCamera",
                                    "Rejected browser signal: $validationError",
                                )
                                close(
                                    CloseReason(
                                        CloseReason.Codes.NOT_CONSISTENT,
                                        "Invalid camera signal",
                                    )
                                )
                                break
                            }
                            manager.signal(viewer.id, checkNotNull(signal))
                        }
                    } finally {
                        manager.disconnect(viewer.id)
                    }
                }
                try {
                    for (event in viewer.events) send(Frame.Text(event))
                } finally {
                    receiver.cancel()
                    manager.disconnect(viewer.id)
                    val reason = runCatching { closeReason.await() }.getOrNull()
                    android.util.Log.i(
                        "OmniAndCamera",
                        "camera websocket closed: ${reason?.code ?: "unknown"}",
                    )
                }
            }
        }
        route("/api/camera") {
            get("/requests") { call.forward(context) }
            post("/requests/{id}/decision") { call.forward(context) }
        }
    }

    private fun ApplicationCall.authorizeCameraWebSocket(context: Context): WebSocketAccess {
        val requestContext =
            PlatformServer.authenticateRequest(
                context,
                request.headers["Host"].orEmpty(),
                request.local.remoteAddress,
                "GET",
                "/api/camera/webrtc",
                request.platformHeaders(),
            )
        return authorizeCameraWebSocket(
            requestContext,
            request.headers["Origin"],
            requestContext != null &&
                PlatformServer.hasCapability(context, requestContext, "camera.stream"),
        )
    }

    internal fun authorizeCameraWebSocket(
        requestContext: PlatformRequestContext?,
        origin: String?,
        hasCapability: Boolean,
    ): WebSocketAccess {
        return when {
            requestContext == null -> WebSocketAccess.UNAUTHORIZED
            requestContext.transport != PlatformRequestContext.Transport.DESKTOP_HTTP ->
                WebSocketAccess.FORBIDDEN
            requestContext.app?.id != "camera" || !hasCapability -> WebSocketAccess.FORBIDDEN
            origin != "https://${requestContext.hostname}" -> WebSocketAccess.FORBIDDEN
            else -> WebSocketAccess.ALLOWED
        }
    }

    /** Authenticates and bounds ordinary requests before invoking existing business handlers. */
    private suspend fun ApplicationCall.forward(context: Context) {
        val headers = request.platformHeaders()
        val requestPath = request.uri.substringBefore('?')
        if (requestPath.startsWith("/api/")) {
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
        val capability =
            when (kind) {
                UploadKind.MEDIA -> "media.write"
                UploadKind.SMS -> "sms.send"
                UploadKind.FILES -> "files.write"
            }
        if (!PlatformServer.hasCapability(context, requestContext, capability)) {
            respondJson(403, "missing-capability", "Missing capability: $capability")
            return
        }

        val limit =
            when (kind) {
                UploadKind.MEDIA -> MediaUploadStore.MAX_FILE
                UploadKind.SMS -> MmsUploadStore.MAX_SIZE
                UploadKind.FILES -> FilesService.MAX_UPLOAD_SIZE
            }
        val directory = File(context.cacheDir, "multipart-uploads").apply { mkdirs() }
        val temporary = File(directory, UUID.randomUUID().toString())
        var fileName: String? = null
        var mime: String? = null
        var digest: String? = null
        var folder: String? = null
        var parent: String? = null
        var conflict = "fail"
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
                                "parent" ->
                                    if (kind != UploadKind.FILES || parent != null) invalid = true
                                    else parent = part.value
                                "conflict" ->
                                    if (kind != UploadKind.FILES || conflict != "fail")
                                        invalid = true
                                    else conflict = part.value
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
                when (kind) {
                    UploadKind.MEDIA ->
                        MediaUploadStore(context)
                            .publish(
                                requestContext.app!!.id,
                                fileName!!,
                                mime!!,
                                digest!!,
                                temporary,
                                folder,
                            )
                    UploadKind.SMS ->
                        MmsUploadStore(context)
                            .stage(requestContext.app!!.id, fileName!!, mime!!, digest!!, temporary)
                    UploadKind.FILES ->
                        FilesService(context)
                            .publishUpload(
                                parent ?: throw InvalidMultipart(),
                                fileName!!,
                                digest!!,
                                conflict,
                                temporary,
                            )
                }
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
        } catch (error: FilesService.Invalid) {
            respondJson(
                if (error.code == "conflict") 409 else 400,
                error.code,
                "Invalid file upload",
            )
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
        FILES,
    }

    private class UploadTooLarge : Exception()

    private class InvalidMultipart : Exception()

    internal enum class WebSocketAccess {
        ALLOWED,
        UNAUTHORIZED,
        FORBIDDEN,
    }

    private class TestWebSocketAuthorizationConfig {
        lateinit var authorize: suspend ApplicationCall.() -> WebSocketAccess
    }

    private val TestWebSocketAuthorization =
        createRouteScopedPlugin(
            "TestWebSocketAuthorization",
            ::TestWebSocketAuthorizationConfig,
        ) {
            val authorize = pluginConfig.authorize
            onCall { call ->
                when (call.authorize()) {
                    WebSocketAccess.ALLOWED -> Unit
                    WebSocketAccess.UNAUTHORIZED ->
                        call.respondJson(401, "authentication-required", "Unauthorized")
                    WebSocketAccess.FORBIDDEN -> call.respondJson(403, "forbidden", "Forbidden")
                }
            }
        }

    private const val TEST_APP_ID = "test"
    private const val TEST_WEBSOCKET_MAX_PROBE_ID = 200
    private const val TEST_WEBSOCKET_MAX_FRAME_SIZE = 4L * 1024
    private const val CAMERA_WEBSOCKET_MAX_FRAME_SIZE = 256L * 1024
}
