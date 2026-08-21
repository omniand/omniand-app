package dev.omniand.launcher.server

import android.content.Context
import android.util.Log
import dev.omniand.launcher.BuildConfig
import dev.omniand.launcher.contacts.ContactsEventBroadcaster
import dev.omniand.launcher.contacts.ContactsSetupManager
import dev.omniand.launcher.media.MediaEventBroadcaster
import dev.omniand.launcher.media.MediaSetupManager
import dev.omniand.launcher.media.MediaUploadStore
import dev.omniand.launcher.permissions.PermissionManager
import dev.omniand.launcher.services.ContactsService
import dev.omniand.launcher.services.MediaService
import dev.omniand.launcher.services.SmsService
import dev.omniand.launcher.sms.MmsUploadStore
import dev.omniand.launcher.sms.SmsEventBroadcaster
import dev.omniand.launcher.sms.SmsNotifications
import dev.omniand.launcher.sms.SmsReadEventPublisher
import dev.omniand.launcher.sms.SmsSetupManager
import dev.omniand.launcher.webapps.StoreCatalog
import dev.omniand.launcher.webapps.WebApp
import dev.omniand.launcher.webapps.WebAppInstaller
import dev.omniand.launcher.webapps.WebAppRegistry
import dev.omniand.launcher.wrappers.WrapperInstaller
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serves Platform and installed-app origins through one HTTP implementation for WebView and LAN.
 *
 * Host resolution establishes the Web-app identity before protected routes are dispatched. Each
 * route then enforces the declared Web capability independently from Android permission or role
 * checks performed by its native service.
 */
object PlatformServer {
    const val PORT = 8080
    private val started = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        ContactsEventBroadcaster.start(appContext)
        MediaEventBroadcaster.start(appContext)
        val ready = CountDownLatch(1)
        Thread({ serve(appContext, ready) }, "platform-http-$PORT").apply {
            isDaemon = true
            start()
        }
        ready.await(2, TimeUnit.SECONDS)
    }

    private fun serve(context: Context, ready: CountDownLatch) {
        try {
            ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0")).use { server ->
                ready.countDown()
                while (!server.isClosed) {
                    val socket = server.accept()
                    workers.execute {
                        socket.use { client ->
                            runCatching {
                                    handle(
                                        context,
                                        BufferedInputStream(client.getInputStream()),
                                        client.getOutputStream(),
                                    )
                                }
                                .onFailure { Log.e(TAG, "HTTP request failed", it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            ready.countDown()
            started.set(false)
        }
    }

    private fun handle(context: Context, input: BufferedInputStream, output: java.io.OutputStream) {
        val request = readHttpLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        var host = "127.0.0.1"
        var contentLength = 0
        val requestHeaders = mutableMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':', "").trim().lowercase()
            if (name.isNotEmpty()) requestHeaders[name] = line.substringAfter(':').trim()
            if (line.startsWith("Host:", true))
                host = line.substringAfter(':').trim().substringBefore(':')
            if (line.startsWith("Content-Length:", true))
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (contentLength > MAX_REQUEST_BODY) {
            write(output, error(413, "Request body is too large"))
            return
        }
        val body = ByteArray(contentLength)
        var bodyOffset = 0
        while (bodyOffset < body.size) {
            val count = input.read(body, bodyOffset, body.size - bodyOffset)
            if (count < 0) break
            bodyOffset += count
        }

        val response =
            route(
                context,
                method,
                path,
                query,
                host.lowercase(),
                isLocalWebView = false,
                requestHeaders,
                body,
            )
        write(output, response)
    }

    private fun write(output: java.io.OutputStream, response: Response) {
        val headers = buildString {
            append("HTTP/1.1 ${response.status}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            response.contentLength?.let { append("Content-Length: $it\r\n") }
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        response.openBody().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        }
    }

    fun localResponse(
        context: Context,
        method: String,
        path: String,
        host: String,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        route(
            context.applicationContext,
            method,
            path.substringBefore('?'),
            parseQuery(path.substringAfter('?', "")),
            host.lowercase(),
            isLocalWebView = true,
            headers.mapKeys { it.key.lowercase() },
            byteArrayOf(),
        )

    private fun route(
        context: Context,
        method: String,
        path: String,
        query: Map<String, String>,
        host: String,
        isLocalWebView: Boolean,
        headers: Map<String, String>,
        body: ByteArray,
    ): Response {
        val app = WebAppRegistry.byHost(context, host)
        if (path == "/api/media/setup" && method == "GET") {
            if (!hasMediaCapability(context, app))
                return codedError(403, "missing-capability", "Missing Media capability")
            return json(200, MediaSetupManager.state(context, isLocalWebView))
        }
        if (path == "/api/media/setup/request" && method == "POST") {
            if (!hasMediaCapability(context, app))
                return codedError(403, "missing-capability", "Missing Media capability")
            if (!isLocalWebView)
                return codedError(
                    403,
                    "phone-local-required",
                    "Media setup can only be opened on the phone",
                )
            MediaSetupManager.request(context, app?.permissions.orEmpty())
            return json(200, JSONObject().put("opened", true))
        }
        if (path == "/api/media/events" && method == "GET") {
            if (!PermissionManager.hasCapability(context, app?.id, "media.read"))
                return codedError(403, "missing-capability", "Missing capability: media.read")
            return Response.stream(
                "200 OK",
                "text/event-stream; charset=utf-8",
                { MediaEventBroadcaster.subscribe(isLocalWebView) },
                mapOf("Cache-Control" to "no-cache, no-transform", "X-Accel-Buffering" to "no"),
            )
        }
        if (path == "/api/media" && method == "GET")
            return mediaRead(context, app) {
                it.list(
                    query["type"] ?: "all",
                    query["offset"]?.toIntOrNull() ?: 0,
                    query["limit"]?.toIntOrNull() ?: 60,
                )
            }
        if (path == "/api/media/delete" && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "media.write"))
                return codedError(403, "missing-capability", "Missing capability: media.write")
            return mediaOperation {
                val ids = JSONArray(decodedHeader(headers, "x-omniand-media-ids", 32 * 1024))
                MediaService(context).delete(List(ids.length()) { ids.getString(it) })
            }
        }
        if (path == "/api/media/uploads" && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "media.write"))
                return codedError(403, "missing-capability", "Missing capability: media.write")
            return mediaUpload {
                MediaUploadStore(context)
                    .create(
                        app!!.id,
                        decodedHeader(headers, "x-omniand-upload-name", 512),
                        decodedHeader(headers, "x-omniand-upload-type", 256),
                        requiredHeader(headers, "x-omniand-upload-size").toLongOrNull()
                            ?: throw MediaUploadStore.Invalid("invalid-upload"),
                        requiredHeader(headers, "x-omniand-upload-sha256"),
                    )
            }
        }
        val mediaUpload =
            Regex("^/api/media/uploads/([^/]+)(?:/(complete|abort))?$").matchEntire(path)
        if (mediaUpload != null && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "media.write"))
                return codedError(403, "missing-capability", "Missing capability: media.write")
            return mediaUpload {
                val store = MediaUploadStore(context)
                val id = mediaUpload.groupValues[1]
                when (mediaUpload.groupValues[2]) {
                    "complete" -> store.complete(app!!.id, id)
                    "abort" -> store.abort(app!!.id, id)
                    else -> {
                        val bytes =
                            if (body.isNotEmpty()) body
                            else
                                Base64.getDecoder()
                                    .decode(requiredHeader(headers, "x-omniand-upload-chunk"))
                        store.append(
                            app!!.id,
                            id,
                            requiredHeader(headers, "x-omniand-upload-offset").toLongOrNull()
                                ?: throw MediaUploadStore.Invalid("invalid-upload-chunk"),
                            bytes,
                        )
                    }
                }
            }
        }
        val mediaResource = Regex("^/api/media/([^/]+)(?:/(thumbnail|content))?$").matchEntire(path)
        if (mediaResource != null && method == "GET") {
            val id = URLDecoder.decode(mediaResource.groupValues[1], "UTF-8")
            return when (mediaResource.groupValues[2]) {
                "thumbnail" -> mediaThumbnail(context, app, id)
                "content" -> mediaContent(context, app, id, headers["range"])
                else -> mediaRead(context, app) { it.item(id) }
            }
        }
        if (path == "/api/contacts/setup" && method == "GET") {
            if (!hasContactsCapability(context, app))
                return codedError(403, "missing-capability", "Missing Contacts capability")
            return json(200, ContactsSetupManager.state(context))
        }
        if (path == "/api/contacts/setup/request" && method == "POST") {
            if (!hasContactsCapability(context, app))
                return codedError(403, "missing-capability", "Missing Contacts capability")
            if (!isLocalWebView)
                return codedError(
                    403,
                    "phone-local-required",
                    "Contacts setup can only be opened on the phone",
                )
            ContactsSetupManager.request(context, app?.permissions.orEmpty())
            return json(200, JSONObject().put("opened", true))
        }
        if (path == "/api/contacts/events" && method == "GET") {
            if (!PermissionManager.hasCapability(context, app?.id, "contacts.read"))
                return codedError(403, "missing-capability", "Missing capability: contacts.read")
            return Response.stream(
                "200 OK",
                "text/event-stream; charset=utf-8",
                { ContactsEventBroadcaster.subscribe(isLocalWebView) },
                mapOf("Cache-Control" to "no-cache, no-transform", "X-Accel-Buffering" to "no"),
            )
        }
        if (path == "/api/contacts" && method == "GET")
            return contactsRead(context, app) {
                it.list(
                    query["q"],
                    query["offset"]?.toIntOrNull() ?: 0,
                    query["limit"]?.toIntOrNull() ?: 50,
                )
            }
        if (path == "/api/contacts/accounts" && method == "GET")
            return contactsRead(context, app) { it.accounts() }
        if (path == "/api/contacts/matches" && method == "POST")
            return contactsRead(context, app) {
                it.match(
                    JSONArray(
                        decodedHeader(headers, "x-omniand-contacts-numbers", MAX_CONTACT_JSON)
                    )
                )
            }
        if (path == "/api/contacts" && method == "POST")
            return contactsWrite(context, app) {
                it.create(
                    JSONObject(decodedHeader(headers, "x-omniand-contacts-data", MAX_CONTACT_JSON))
                )
            }
        val contactPhoto = Regex("^/api/contacts/([^/]+)/photo$").matchEntire(path)
        if (contactPhoto != null && method == "GET")
            return contactsPhoto(
                context,
                app,
                URLDecoder.decode(contactPhoto.groupValues[1], "UTF-8"),
            )
        if (contactPhoto != null && method in setOf("PUT", "DELETE"))
            return contactsWrite(context, app) {
                val bytes =
                    if (method == "DELETE") null
                    else
                        Base64.getDecoder()
                            .decode(
                                decodedHeader(headers, "x-omniand-contacts-photo", MAX_PHOTO_HEADER)
                            )
                if (bytes != null && bytes.size > MAX_CONTACT_PHOTO)
                    throw ContactsService.InvalidInput()
                it.setPhoto(
                    URLDecoder.decode(contactPhoto.groupValues[1], "UTF-8"),
                    decodedHeader(headers, "x-omniand-contacts-source", 256),
                    requireIfMatch(headers),
                    bytes,
                )
            }
        val contactPath = Regex("^/api/contacts/([^/]+)$").matchEntire(path)
        if (contactPath != null && method == "GET")
            return contactsRead(context, app) {
                it.detail(URLDecoder.decode(contactPath.groupValues[1], "UTF-8"))
            }
        if (contactPath != null && method == "PUT")
            return contactsWrite(context, app) {
                it.update(
                    URLDecoder.decode(contactPath.groupValues[1], "UTF-8"),
                    decodedHeader(headers, "x-omniand-contacts-source", 256),
                    requireIfMatch(headers),
                    JSONObject(decodedHeader(headers, "x-omniand-contacts-data", MAX_CONTACT_JSON)),
                )
            }
        if (contactPath != null && method == "DELETE")
            return contactsWrite(context, app) {
                it.delete(
                    URLDecoder.decode(contactPath.groupValues[1], "UTF-8"),
                    requireIfMatch(headers),
                )
            }
        if (path == "/api/sms/setup" && method == "GET") {
            if (
                !PermissionManager.hasCapability(context, app?.id, "sms.read") &&
                    !PermissionManager.hasCapability(context, app?.id, "sms.send") &&
                    !PermissionManager.hasCapability(context, app?.id, "sms.modify")
            )
                return codedError(403, "missing-capability", "Missing SMS capability")
            return json(200, SmsSetupManager.state(context))
        }
        if (path == "/api/sms/setup/request" && method == "POST") {
            if (
                !PermissionManager.hasCapability(context, app?.id, "sms.read") &&
                    !PermissionManager.hasCapability(context, app?.id, "sms.send") &&
                    !PermissionManager.hasCapability(context, app?.id, "sms.modify")
            )
                return codedError(403, "missing-capability", "Missing SMS capability")
            if (!isLocalWebView)
                return codedError(
                    403,
                    "phone-local-required",
                    "SMS setup can only be opened on the phone",
                )
            SmsSetupManager.request(context, app?.permissions.orEmpty())
            return json(200, JSONObject().put("opened", true))
        }
        if (path == "/api/sms" && method == "GET") return sms(context, app)
        if (path == "/api/sms/events" && method == "GET") {
            if (!PermissionManager.hasCapability(context, app?.id, "sms.read"))
                return error(403, "Missing capability: sms.read")
            return Response.stream(
                "200 OK",
                "text/event-stream; charset=utf-8",
                { SmsEventBroadcaster.subscribe(closeAfterEvent = isLocalWebView) },
                mapOf("Cache-Control" to "no-cache, no-transform", "X-Accel-Buffering" to "no"),
            )
        }
        if (path == "/api/sms/threads" && method == "GET")
            return sms(context, app) { it.threads(query["offset"], query["limit"]) }
        if (path == "/api/sms/uploads" && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "sms.send"))
                return codedError(403, "missing-capability", "Missing capability: sms.send")
            return uploadOperation {
                MmsUploadStore(context)
                    .create(
                        app!!.id,
                        decodedHeader(headers, "x-omniand-upload-name", 512),
                        decodedHeader(headers, "x-omniand-upload-type", 256),
                        requiredUploadHeader(headers, "x-omniand-upload-size").toLongOrNull()
                            ?: throw MmsUploadStore.Invalid("invalid-upload"),
                        requiredUploadHeader(headers, "x-omniand-upload-sha256"),
                    )
            }
        }
        val upload = Regex("^/api/sms/uploads/([^/]+)(?:/(complete|abort))?$").matchEntire(path)
        if (upload != null && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "sms.send"))
                return codedError(403, "missing-capability", "Missing capability: sms.send")
            return uploadOperation {
                val store = MmsUploadStore(context)
                val id = upload.groupValues[1]
                when (upload.groupValues[2]) {
                    "complete" -> store.complete(app!!.id, id)
                    "abort" -> store.abort(app!!.id, id)
                    else ->
                        store.append(
                            app!!.id,
                            id,
                            requiredUploadHeader(headers, "x-omniand-upload-index").toIntOrNull()
                                ?: throw MmsUploadStore.Invalid("invalid-upload-chunk"),
                            Base64.getDecoder()
                                .decode(requiredUploadHeader(headers, "x-omniand-upload-chunk")),
                        )
                }
            }
        }
        if (path == "/api/sms/messages" && method == "POST") {
            val subject =
                headers["x-omniand-mms-subject"]?.let {
                    URLDecoder.decode(it, "UTF-8")
                }
            val uploads =
                headers["x-omniand-mms-uploads"]?.let {
                    URLDecoder.decode(it, "UTF-8")
                }
            return smsMutation(context, app, "sms.send") {
                it.send(
                    requiredHeader(headers, "x-omniand-sms-address"),
                    requiredHeader(headers, "x-omniand-sms-body"),
                    subject,
                    uploads?.split(',')?.filter { id -> id.isNotBlank() }.orEmpty(),
                    app!!.id,
                )
            }
        }
        val threadMessages = Regex("^/api/sms/threads/([^/]+)/messages$").matchEntire(path)
        if (threadMessages != null && method == "GET") {
            return sms(context, app) {
                it.messages(threadMessages.groupValues[1], query["offset"], query["limit"])
            }
        }
        val thread = Regex("^/api/sms/threads/([^/]+)$").matchEntire(path)
        if (thread != null && method == "DELETE") {
            return smsMutation(context, app, "sms.modify") {
                it.deleteThread(thread.groupValues[1]).also {
                    SmsNotifications.cancelThread(context, thread.groupValues[1])
                }
            }
        }
        val threadRead = Regex("^/api/sms/threads/([^/]+)/read$").matchEntire(path)
        if (threadRead != null && method == "POST") {
            return smsMutation(context, app, "sms.modify") {
                it.setThreadRead(
                        threadRead.groupValues[1],
                        requiredHeader(headers, "x-omniand-sms-read"),
                    )
                    .also {
                        if (requiredHeader(headers, "x-omniand-sms-read") == "true")
                            SmsNotifications.cancelThread(context, threadRead.groupValues[1])
                        SmsReadEventPublisher.publishThread(threadRead.groupValues[1])
                    }
            }
        }
        val messageRead = Regex("^/api/sms/messages/([^/]+)/read$").matchEntire(path)
        if (messageRead != null && method == "POST") {
            return smsMutation(context, app, "sms.modify") {
                it.setRead(
                        URLDecoder.decode(messageRead.groupValues[1], "UTF-8"),
                        requiredHeader(headers, "x-omniand-sms-read"),
                    )
                    .also { result ->
                        if (result.optBoolean("read"))
                            SmsNotifications.cancelThread(context, result.getString("threadId"))
                        SmsReadEventPublisher.publishMessage(
                            URLDecoder.decode(messageRead.groupValues[1], "UTF-8")
                        )
                    }
            }
        }
        val messagePart = Regex("^/api/sms/messages/([^/]+)/parts/([^/]+)$").matchEntire(path)
        if (messagePart != null && method == "GET") {
            return smsPart(
                context,
                app,
                URLDecoder.decode(messagePart.groupValues[1], "UTF-8"),
                messagePart.groupValues[2],
                headers["range"],
            )
        }
        val messageDownload = Regex("^/api/sms/messages/([^/]+)/download$").matchEntire(path)
        if (messageDownload != null && method == "POST") {
            return smsMutation(context, app, "sms.read") {
                it.retryDownload(URLDecoder.decode(messageDownload.groupValues[1], "UTF-8"))
            }
        }
        val singleMessage = Regex("^/api/sms/messages/([^/]+)$").matchEntire(path)
        if (singleMessage != null && method == "GET") {
            return sms(context, app) {
                it.message(URLDecoder.decode(singleMessage.groupValues[1], "UTF-8"))
            }
        }
        if (singleMessage != null && method == "DELETE") {
            return smsMutation(context, app, "sms.modify") {
                it.deleteMessage(URLDecoder.decode(singleMessage.groupValues[1], "UTF-8")).also {
                    result ->
                    SmsNotifications.cancelThread(context, result.getString("threadId"))
                }
            }
        }
        if (path == "/api/store/config" && method == "GET" && app?.id == "store") {
            return json(
                200,
                JSONObject()
                    .put("storeUrl", BuildConfig.STORE_URL)
                    .put(
                        "installedApps",
                        JSONArray(
                            WebAppRegistry.apps(context)
                                .filter { it.packageName != null }
                                .map { it.id }
                        ),
                    ),
            )
        }
        val installPrefix = "/api/apps/install/"
        if (path.startsWith(installPrefix) && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "apps.install"))
                return error(403, "Missing capability: apps.install")
            if (!isLocalWebView)
                return codedError(
                    403,
                    "phone-local-required",
                    "Applications can only be installed from the phone",
                )
            return try {
                WebAppInstaller.prepare(
                        context,
                        URLDecoder.decode(path.removePrefix(installPrefix), "UTF-8"),
                    )
                    .use { validated -> json(202, WrapperInstaller.install(context, validated)) }
            } catch (error: Exception) {
                Log.w(TAG, "Web application installation rejected", error)
                error(400, error.message ?: "Unable to install application")
            }
        }
        val operationPrefix = "/api/apps/operations/"
        if (path.startsWith(operationPrefix) && method == "GET") {
            val operationId = URLDecoder.decode(path.removePrefix(operationPrefix), "UTF-8")
            val operation =
                dev.omniand.launcher.wrappers.InstallOperations.get(context, operationId)
                    ?: return error(404, "Installation operation not found")
            if (
                operation.optString("kind") == "uninstall" &&
                    operation.optString("status") == "pending-user-action"
            ) {
                val id = operation.getString("id")
                if (
                    runCatching {
                            context.packageManager.getPackageInfo(
                                WrapperInstaller.packageName(id),
                                0,
                            )
                        }
                        .isFailure
                ) {
                    WebAppRegistry.invalidate()
                    dev.omniand.launcher.wrappers.InstallOperations.update(
                        context,
                        operationId,
                        "installed",
                    )
                    operation.put("status", "installed")
                }
            }
            return json(200, operation)
        }
        val uninstallPrefix = "/api/apps/uninstall/"
        if (path.startsWith(uninstallPrefix) && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "apps.install"))
                return error(403, "Missing capability: apps.install")
            val id = URLDecoder.decode(path.removePrefix(uninstallPrefix), "UTF-8")
            return removeWebApp(context, id, isLocalWebView)
        }
        if (WebAppRegistry.isPlatformHost(context, host)) {
            if (path == "/api/apps/web" && method == "GET") {
                val apps =
                    JSONArray().apply {
                        WebAppRegistry.apps(context).forEach { item ->
                            put(
                                JSONObject()
                                    .put("id", item.id)
                                    .put("name", item.name)
                                    .put("version", item.version)
                                    .put("updatable", item.packageName != null)
                                    .put(
                                        "origin",
                                        if (!isLocalWebView) {
                                            WebAppRegistry.developmentOriginFor(item, host, PORT)
                                        } else {
                                            WebAppRegistry.originFor(item)
                                        },
                                    )
                                    .put(
                                        "icon",
                                        item.iconPath?.let { "/api/apps/web/${item.id}/icon" }
                                            ?: JSONObject.NULL,
                                    )
                                    .put("permissions", JSONArray(item.permissions.toList()))
                            )
                        }
                    }
                return json(200, apps)
            }
            val integrationPrefix = "/api/apps/web/"
            if (
                path.startsWith(integrationPrefix) &&
                    path.endsWith("/update") &&
                    method in setOf("GET", "POST")
            ) {
                if (!isLocalWebView)
                    return codedError(
                        403,
                        "phone-local-required",
                        "Web applications can only be updated from the phone",
                    )
                val appId =
                    URLDecoder.decode(
                        path.removePrefix(integrationPrefix).removeSuffix("/update"),
                        "UTF-8",
                    )
                val installedApp =
                    WebAppRegistry.apps(context).firstOrNull {
                        it.id == appId && it.packageName != null
                    } ?: return error(404, "Updatable Web application not found")
                return try {
                    val update = StoreCatalog.check(installedApp)
                    if (method == "GET") {
                        json(
                            200,
                            JSONObject()
                                .put("currentVersion", update.currentVersion)
                                .put("available", update.available)
                                .put("availableVersion", update.availableVersion ?: JSONObject.NULL)
                                .put(
                                    "addedCapabilities",
                                    JSONArray(update.addedCapabilities.sorted()),
                                ),
                        )
                    } else {
                        val expectedVersion =
                            headers["x-omniand-update-version"]
                                ?: return codedError(
                                    400,
                                    "expected-version-required",
                                    "X-OmniAnd-Update-Version is required",
                                )
                        if (
                            !update.available ||
                                update.availableVersion != expectedVersion ||
                                update.catalogApp == null
                        ) {
                            return codedError(
                                409,
                                "stale-update",
                                "The selected update is no longer available",
                            )
                        }
                        val selected = update.catalogApp
                        WebAppInstaller.prepare(
                                context,
                                selected.packageUrl,
                                WebAppInstaller.Expected(
                                    selected.id,
                                    selected.version,
                                    selected.permissions,
                                ),
                            )
                            .use { validated ->
                                json(202, WrapperInstaller.install(context, validated))
                            }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Web application update failed", error)
                    codedError(
                        400,
                        "update-failed",
                        error.message ?: "Unable to update application",
                    )
                }
            }
            if (path.startsWith(integrationPrefix) && path.endsWith("/icon") && method == "GET") {
                val appId =
                    URLDecoder.decode(
                        path.removePrefix(integrationPrefix).removeSuffix("/icon"),
                        "UTF-8",
                    )
                val icon =
                    WebAppRegistry.apps(context)
                        .firstOrNull { it.id == appId }
                        ?.let { readAppIcon(context, it) }
                return if (icon != null) Response("200 OK", "image/png", icon)
                else error(404, "Application icon not found")
            }
            if (
                path.startsWith(integrationPrefix) &&
                    path.endsWith("/uninstall") &&
                    method == "POST"
            ) {
                if (!isLocalWebView)
                    return error(403, "Web applications can only be removed here from the phone")
                val appId =
                    URLDecoder.decode(
                        path.removePrefix(integrationPrefix).removeSuffix("/uninstall"),
                        "UTF-8",
                    )
                return removeWebApp(context, appId, isLocalWebView = true)
            }
        }

        if (app?.assetRoot != null)
            return staticAsset(
                context,
                app.assetRoot,
                path,
                app,
                isLocalWebView,
                headers["host"] ?: host,
            )
        if (app?.packageName != null)
            return staticPackageAsset(
                context,
                app,
                path,
                isLocalWebView,
                headers["host"] ?: host,
            )
        if (WebAppRegistry.isPlatformHost(context, host))
            return staticAsset(context, "web/shell", path, null, isLocalWebView, host)
        return error(404, "Unknown application origin")
    }

    private fun sms(context: Context, app: WebApp?): Response {
        return sms(context, app) { it.recent() }
    }

    private fun smsPart(
        context: Context,
        app: WebApp?,
        messageId: String,
        partId: String,
        range: String?,
    ): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "sms.read"))
            return codedError(403, "missing-capability", "Missing capability: sms.read")
        return try {
            val part = SmsService(context).part(messageId, partId)
            val disposition = if (part.inline) "inline" else "attachment"
            val headers =
                mutableMapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Disposition" to "$disposition; filename=\"${part.name}\"",
                )
            val match = range?.let { Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(it) }
            if (range != null && match == null) return error(416, "Invalid byte range")
            if (match == null)
                Response.bytes(
                    "200 OK",
                    if (part.inline) part.mime else "application/octet-stream",
                    part.bytes,
                    headers,
                )
            else {
                val rawStart = match.groupValues[1]
                val rawEnd = match.groupValues[2]
                if (rawStart.isBlank() && rawEnd.isBlank()) return error(416, "Invalid byte range")
                val suffix = if (rawStart.isBlank()) rawEnd.toIntOrNull() else null
                if (suffix != null && suffix <= 0) return error(416, "Invalid byte range")
                val start =
                    if (suffix != null) (part.bytes.size - suffix).coerceAtLeast(0)
                    else rawStart.toIntOrNull() ?: return error(416, "Invalid byte range")
                val end =
                    if (suffix != null) part.bytes.size - 1
                    else rawEnd.toIntOrNull() ?: (part.bytes.size - 1)
                if (start !in part.bytes.indices || end < start || end >= part.bytes.size)
                    return error(416, "Invalid byte range")
                headers["Content-Range"] = "bytes $start-$end/${part.bytes.size}"
                Response.bytes(
                    "206 Partial Content",
                    if (part.inline) part.mime else "application/octet-stream",
                    part.bytes.copyOfRange(start, end + 1),
                    headers,
                )
            }
        } catch (_: SmsService.PermissionMissing) {
            codedError(
                403,
                "android-permission-required",
                "Android SMS permission has not been granted",
            )
        } catch (_: SmsService.InvalidId) {
            error(400, "Invalid MMS part identifier")
        } catch (_: SmsService.NotFound) {
            error(404, "MMS part not found")
        }
    }

    private fun contactsRead(
        context: Context,
        app: WebApp?,
        operation: (ContactsService) -> Any,
    ): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "contacts.read"))
            return codedError(403, "missing-capability", "Missing capability: contacts.read")
        return contactsOperation(context, operation)
    }

    private fun contactsWrite(
        context: Context,
        app: WebApp?,
        operation: (ContactsService) -> Any,
    ): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "contacts.write"))
            return codedError(403, "missing-capability", "Missing capability: contacts.write")
        return contactsOperation(context, operation)
    }

    private fun contactsOperation(context: Context, operation: (ContactsService) -> Any): Response =
        try {
            ContactsEventBroadcaster.start(context)
            json(200, operation(ContactsService(context)))
        } catch (_: ContactsService.PermissionMissing) {
            codedError(
                403,
                "android-permission-required",
                "Android Contacts permission has not been granted",
            )
        } catch (_: ContactsService.InvalidInput) {
            codedError(400, "invalid-contact-request", "Invalid Contacts request")
        } catch (_: ContactsService.NotFound) {
            codedError(404, "contact-not-found", "Contact not found")
        } catch (_: ContactsService.Conflict) {
            codedError(409, "stale-contact", "The contact changed; reload before saving")
        } catch (_: ContactsService.ReadOnly) {
            codedError(409, "read-only-source", "No selected writable contact source exists")
        } catch (error: Exception) {
            Log.e(TAG, "Contacts operation failed", error)
            codedError(500, "contacts-failed", "Unable to access contacts")
        }

    private fun contactsPhoto(context: Context, app: WebApp?, key: String): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "contacts.read"))
            return codedError(403, "missing-capability", "Missing capability: contacts.read")
        return try {
            Response("200 OK", "image/jpeg", ContactsService(context).photo(key))
        } catch (_: ContactsService.PermissionMissing) {
            codedError(
                403,
                "android-permission-required",
                "Android Contacts permission has not been granted",
            )
        } catch (_: ContactsService.NotFound) {
            codedError(404, "contact-not-found", "Contact photo not found")
        }
    }

    private fun hasContactsCapability(context: Context, app: WebApp?) =
        PermissionManager.hasCapability(context, app?.id, "contacts.read") ||
            PermissionManager.hasCapability(context, app?.id, "contacts.write")

    private fun hasMediaCapability(context: Context, app: WebApp?) =
        PermissionManager.hasCapability(context, app?.id, "media.read") ||
            PermissionManager.hasCapability(context, app?.id, "media.write")

    private fun mediaRead(
        context: Context,
        app: WebApp?,
        operation: (MediaService) -> Any,
    ): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "media.read"))
            return codedError(403, "missing-capability", "Missing capability: media.read")
        MediaEventBroadcaster.start(context)
        return mediaOperation { operation(MediaService(context)) }
    }

    private fun mediaOperation(operation: () -> Any): Response =
        try {
            json(200, operation())
        } catch (error: MediaService.Invalid) {
            val status =
                when (error.code) {
                    "not-found" -> 404
                    "android-permission-required",
                    "media-management-required",
                    "media-management-unavailable" -> 403
                    else -> 400
                }
            codedError(status, error.code, mediaMessage(error.code))
        } catch (error: Exception) {
            Log.e(TAG, "Media operation failed", error)
            codedError(500, "media-failed", "Unable to access Android media")
        }

    private fun mediaUpload(operation: () -> Any): Response =
        try {
            json(200, operation())
        } catch (error: MediaUploadStore.Invalid) {
            codedError(
                if (error.code == "staging-limit") 413 else 400,
                error.code,
                mediaMessage(error.code),
            )
        } catch (_: ContactsService.InvalidInput) {
            codedError(400, "invalid-upload", "Invalid media upload")
        } catch (error: MediaService.Invalid) {
            codedError(400, error.code, mediaMessage(error.code))
        } catch (error: Exception) {
            Log.e(TAG, "Media upload failed", error)
            codedError(500, "storage-unavailable", "Media could not be published")
        }

    private fun mediaThumbnail(context: Context, app: WebApp?, id: String): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "media.read"))
            return codedError(403, "missing-capability", "Missing capability: media.read")
        return try {
            Response.bytes(
                "200 OK",
                "image/jpeg",
                MediaService(context).thumbnail(id),
                mapOf("Cache-Control" to "private, max-age=300"),
            )
        } catch (error: MediaService.Invalid) {
            codedError(
                if (error.code == "not-found") 404 else 400,
                error.code,
                mediaMessage(error.code),
            )
        }
    }

    /** Streams originals with one validated byte range and hardened download metadata. */
    private fun mediaContent(context: Context, app: WebApp?, id: String, range: String?): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "media.read"))
            return codedError(403, "missing-capability", "Missing capability: media.read")
        return try {
            val resource = MediaService(context).content(id)
            val parsed = parseRange(range, resource.length)
            if (range != null && parsed == null) {
                resource.stream.close()
                return codedError(416, "invalid-range", "Invalid byte range")
            }
            val start = parsed?.first ?: 0L
            val end = parsed?.last ?: (resource.length - 1)
            var skipped = 0L
            while (skipped < start) {
                val count = resource.stream.skip(start - skipped)
                if (count <= 0) break
                skipped += count
            }
            val length = end - start + 1
            val headers =
                mutableMapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Length" to length.toString(),
                    "Content-Disposition" to
                        "inline; filename*=UTF-8''${java.net.URLEncoder.encode(resource.name, "UTF-8").replace("+", "%20")}",
                    "ETag" to "W/\"${resource.modified}-${resource.length}\"",
                    "Cache-Control" to "private, max-age=60",
                )
            if (parsed != null) headers["Content-Range"] = "bytes $start-$end/${resource.length}"
            Response.stream(
                if (parsed == null) "200 OK" else "206 Partial Content",
                resource.mime,
                { LimitedInputStream(resource.stream, length) },
                headers,
            )
        } catch (error: MediaService.Invalid) {
            val status =
                when (error.code) {
                    "not-found" -> 404
                    "android-permission-required" -> 403
                    else -> 400
                }
            codedError(status, error.code, mediaMessage(error.code))
        }
    }

    private fun parseRange(value: String?, size: Long): LongRange? {
        if (value == null) return null
        val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(value) ?: return null
        if (size <= 0) return null
        val left = match.groupValues[1]
        val right = match.groupValues[2]
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (size - suffix).coerceAtLeast(0)..(size - 1)
        }
        val start = left.toLongOrNull() ?: return null
        val end = right.toLongOrNull() ?: (size - 1)
        return if (start < size && end in start until size) start..end else null
    }

    private fun mediaMessage(code: String) =
        when (code) {
            "android-permission-required" -> "Android photo and video access has not been granted"
            "media-management-required" -> "Android media management access is required"
            "media-management-unavailable" ->
                "This Android version only permits deletion of OmniAnd-owned media"
            "not-found" -> "Media item not found"
            "invalid-media" -> "The uploaded file is not a supported decoded image or video"
            "hash-mismatch" -> "The uploaded file failed SHA-256 verification"
            "staging-limit" -> "The active upload staging limit was reached"
            "file-count-limit" -> "At most 20 active uploads are allowed per application"
            else -> "Invalid media request"
        }

    private fun decodedHeader(headers: Map<String, String>, name: String, max: Int): String {
        val raw = headers[name] ?: throw ContactsService.InvalidInput()
        if (raw.length > max) throw ContactsService.InvalidInput()
        return runCatching { URLDecoder.decode(raw, "UTF-8") }
            .getOrElse { throw ContactsService.InvalidInput() }
    }

    private fun requireIfMatch(headers: Map<String, String>) =
        headers["if-match"]?.trim()?.trim('"')?.takeIf { it.length <= 64 }
            ?: throw ContactsService.InvalidInput()

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&')
            .filter { it.isNotBlank() }
            .associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= MAX_HEADER_LINE) {
            val value = input.read()
            if (value < 0)
                return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (value == '\n'.code)
                return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            bytes.write(value)
        }
        throw IllegalArgumentException("HTTP header line is too long")
    }

    private fun sms(context: Context, app: WebApp?, operation: (SmsService) -> Any): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "sms.read"))
            return error(403, "Missing capability: sms.read")
        return try {
            json(200, operation(SmsService(context)))
        } catch (_: SmsService.PermissionMissing) {
            codedError(
                403,
                "android-permission-required",
                "Android SMS permission has not been granted",
            )
        } catch (_: SmsService.InvalidId) {
            error(400, "Invalid SMS identifier")
        } catch (_: SmsService.InvalidInput) {
            error(400, "Invalid SMS pagination")
        } catch (_: SmsService.NotFound) {
            error(404, "SMS resource not found")
        } catch (_: Exception) {
            error(500, "Unable to read SMS messages")
        }
    }

    private fun smsMutation(
        context: Context,
        app: WebApp?,
        capability: String,
        operation: (SmsService) -> Any,
    ): Response {
        if (!PermissionManager.hasCapability(context, app?.id, capability))
            return error(403, "Missing capability: $capability")
        return try {
            json(200, operation(SmsService(context)))
        } catch (_: SmsService.PermissionMissing) {
            codedError(
                403,
                "android-permission-required",
                "Required Android SMS permission is missing",
            )
        } catch (_: SmsService.RoleRequired) {
            codedError(
                403,
                "sms-role-required",
                "OmniAnd must be the default SMS application for this action",
            )
        } catch (_: SmsService.InvalidInput) {
            error(400, "Invalid SMS request")
        } catch (_: SmsService.InvalidId) {
            error(400, "Invalid SMS identifier")
        } catch (_: SmsService.NotFound) {
            error(404, "SMS resource not found")
        } catch (error: SmsService.MmsUnavailable) {
            codedError(400, error.code, "Carrier MMS requirements are not met")
        } catch (error: SecurityException) {
            Log.w(TAG, "SMS mutation denied by Android", error)
            codedError(
                403,
                "sms-role-required",
                "Android requires OmniAnd to be the default SMS application for this action",
            )
        } catch (error: Exception) {
            Log.e(TAG, "SMS mutation failed", error)
            error(500, "Unable to change SMS messages")
        }
    }

    private fun requiredHeader(headers: Map<String, String>, name: String): String =
        headers[name]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: throw SmsService.InvalidInput()

    private fun requiredUploadHeader(headers: Map<String, String>, name: String): String =
        headers[name]?.takeIf { it.length <= MAX_UPLOAD_HEADER }
            ?: throw MmsUploadStore.Invalid("invalid-upload")

    private fun uploadOperation(operation: () -> JSONObject): Response =
        try {
            json(200, operation())
        } catch (error: MmsUploadStore.Invalid) {
            codedError(400, error.code, "Invalid MMS attachment upload")
        } catch (_: IllegalArgumentException) {
            codedError(400, "invalid-upload-chunk", "Invalid MMS attachment upload")
        } catch (error: Exception) {
            Log.e(TAG, "MMS upload failed", error)
            codedError(500, "upload-failed", "Unable to store MMS attachment")
        }

    private fun removeWebApp(context: Context, id: String, isLocalWebView: Boolean): Response {
        return try {
            if (!isLocalWebView)
                return codedError(
                    403,
                    "phone-local-required",
                    "Applications can only be uninstalled from the phone",
                )
            val appToRemove =
                WebAppRegistry.apps(context).firstOrNull { it.id == id }
                    ?: return error(404, "Web application not found")
            check(appToRemove.id != "store") { "The system Store cannot be removed" }
            val operation =
                dev.omniand.launcher.wrappers.InstallOperations.create(context, id, "uninstall")
            WrapperInstaller.requestUninstall(context, appToRemove)
            json(202, operation)
        } catch (error: Exception) {
            Log.w(TAG, "Web application removal rejected", error)
            error(400, error.message ?: "Unable to remove application")
        }
    }

    private fun readAppIcon(context: Context, app: WebApp): ByteArray? =
        runCatching {
                val iconPath = app.iconPath ?: return null
                WebAppRegistry.openAsset(context, app, iconPath)
            }
            .getOrNull()

    private fun staticAsset(
        context: Context,
        root: String,
        rawPath: String,
        app: WebApp?,
        isLocalWebView: Boolean,
        requestAuthority: String,
    ): Response {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            val bytes = context.assets.open("$root/$relative").use { it.readBytes() }
            val body = desktopDocument(bytes, relative, app, isLocalWebView, requestAuthority)
            val csp = app?.let(CspBuilder::build) ?: CspBuilder.buildPlatform()
            Response("200 OK", mime(relative), body, csp)
        } catch (_: Exception) {
            error(404, "Not found")
        }
    }

    private fun staticPackageAsset(
        context: Context,
        app: WebApp,
        rawPath: String,
        isLocalWebView: Boolean,
        requestAuthority: String,
    ): Response {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            Response(
                "200 OK",
                mime(relative),
                desktopDocument(
                    WebAppRegistry.openAsset(context, app, relative),
                    relative,
                    app,
                    isLocalWebView,
                    requestAuthority,
                ),
                CspBuilder.build(app),
            )
        } catch (_: Exception) {
            error(404, "Not found")
        }
    }

    private fun desktopDocument(
        bytes: ByteArray,
        relative: String,
        app: WebApp?,
        isLocalWebView: Boolean,
        requestAuthority: String,
    ): ByteArray =
        if (!isLocalWebView && app != null && relative.endsWith(".html")) {
            DesktopNavigationBar.inject(
                bytes,
                app.name,
                DesktopNavigationBar.platformHref(app.id, requestAuthority),
            )
        } else {
            bytes
        }

    private fun json(code: Int, value: Any) =
        Response(status(code), "application/json; charset=utf-8", value.toString().toByteArray())

    private fun error(code: Int, message: String) = json(code, JSONObject().put("error", message))

    private fun codedError(code: Int, stableCode: String, message: String) =
        json(code, JSONObject().put("error", message).put("code", stableCode))

    private fun status(code: Int) =
        when (code) {
            200 -> "200 OK"
            202 -> "202 Accepted"
            206 -> "206 Partial Content"
            400 -> "400 Bad Request"
            403 -> "403 Forbidden"
            404 -> "404 Not Found"
            409 -> "409 Conflict"
            501 -> "501 Not Implemented"
            413 -> "413 Payload Too Large"
            416 -> "416 Range Not Satisfiable"
            else -> "500 Internal Server Error"
        }

    private fun mime(path: String) =
        when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") -> "text/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".json") -> "application/json; charset=utf-8"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }

    class Response
    private constructor(
        val status: String,
        val contentType: String,
        private val fixedBody: ByteArray?,
        val csp: String? = null,
        private val streamBody: (() -> java.io.InputStream)? = null,
        private val extraHeaders: Map<String, String> = emptyMap(),
    ) {
        constructor(
            status: String,
            contentType: String,
            body: ByteArray,
            csp: String? = null,
        ) : this(status, contentType, body, csp, null)

        val statusCode: Int
            get() = status.substringBefore(' ').toInt()

        val reason: String
            get() = status.substringAfter(' ')

        val contentLength: Int?
            get() = fixedBody?.size

        fun openBody(): java.io.InputStream = fixedBody?.inputStream() ?: streamBody!!.invoke()

        val headers: Map<String, String>
            get() = buildMap {
                put("Cache-Control", "no-store")
                put("X-Content-Type-Options", "nosniff")
                put("Referrer-Policy", "no-referrer")
                csp?.let { put("Content-Security-Policy", it) }
                putAll(extraHeaders)
            }

        companion object {
            fun bytes(
                status: String,
                contentType: String,
                body: ByteArray,
                headers: Map<String, String>,
            ) = Response(status, contentType, body, null, null, headers)

            fun stream(
                status: String,
                contentType: String,
                body: () -> java.io.InputStream,
                headers: Map<String, String>,
            ) = Response(status, contentType, null, null, body, headers)
        }
    }

    private class LimitedInputStream(
        private val source: java.io.InputStream,
        private var remaining: Long,
    ) : java.io.InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }

        override fun close() = source.close()
    }

    private const val TAG = "OmniAndHttp"
    private const val MAX_REQUEST_BODY = 256 * 1024
    private const val MAX_HEADER_LINE = 512 * 1024
    private const val MAX_CONTACT_JSON = 96 * 1024
    private const val MAX_CONTACT_PHOTO = 256 * 1024
    private const val MAX_PHOTO_HEADER = 400 * 1024
    private const val MAX_UPLOAD_HEADER = 33 * 1024
}
