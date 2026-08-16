package dev.omniand.launcher.server

import android.content.Context
import android.util.Log
import dev.omniand.launcher.BuildConfig
import dev.omniand.launcher.permissions.PermissionManager
import dev.omniand.launcher.services.SmsService
import dev.omniand.launcher.webapps.WebApp
import dev.omniand.launcher.webapps.WebAppInstaller
import dev.omniand.launcher.webapps.WebAppRegistry
import dev.omniand.launcher.webapps.StoreCatalog
import dev.omniand.launcher.wrappers.WrapperInstaller
import dev.omniand.launcher.sms.SmsSetupManager
import dev.omniand.launcher.sms.SmsNotifications
import dev.omniand.launcher.sms.SmsEventBroadcaster
import dev.omniand.launcher.sms.SmsReadEventPublisher
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PlatformServer {
    const val PORT = 8080
    private val started = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
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
                            runCatching { handle(context, client.getInputStream().bufferedReader(), client.getOutputStream()) }
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

    private fun handle(context: Context, reader: BufferedReader, output: java.io.OutputStream) {
        val request = reader.readLine() ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        var host = "127.0.0.1"
        var contentLength = 0
        val requestHeaders = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':', "").trim().lowercase()
            if (name.isNotEmpty()) requestHeaders[name] = line.substringAfter(':').trim()
            if (line.startsWith("Host:", true)) host = line.substringAfter(':').trim().substringBefore(':')
            if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (contentLength > MAX_REQUEST_BODY) {
            write(output, error(413, "Request body is too large"))
            return
        }
        if (contentLength > 0) CharArray(contentLength).also {
            var offset = 0
            while (offset < it.size) {
                val count = reader.read(it, offset, it.size - offset)
                if (count < 0) break
                offset += count
            }
        }

        val response = route(context, method, path, host.lowercase(), isLocalWebView = false, requestHeaders)
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

    fun localResponse(context: Context, method: String, path: String, host: String, headers: Map<String, String> = emptyMap()): Response =
        route(context.applicationContext, method, path.substringBefore('?'), host.lowercase(), isLocalWebView = true,
            headers.mapKeys { it.key.lowercase() })

    private fun route(context: Context, method: String, path: String, host: String, isLocalWebView: Boolean, headers: Map<String, String>): Response {
        val app = WebAppRegistry.byHost(context, host)
        if (path == "/api/sms/setup" && method == "GET") {
            if (!PermissionManager.hasCapability(context, app?.id, "sms.read") &&
                !PermissionManager.hasCapability(context, app?.id, "sms.send") &&
                !PermissionManager.hasCapability(context, app?.id, "sms.modify")) return codedError(403, "missing-capability", "Missing SMS capability")
            return json(200, SmsSetupManager.state(context))
        }
        if (path == "/api/sms/setup/request" && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "sms.read") &&
                !PermissionManager.hasCapability(context, app?.id, "sms.send") &&
                !PermissionManager.hasCapability(context, app?.id, "sms.modify")) return codedError(403, "missing-capability", "Missing SMS capability")
            if (!isLocalWebView) return codedError(403, "phone-local-required", "SMS setup can only be opened on the phone")
            SmsSetupManager.request(context, app?.permissions.orEmpty())
            return json(200, JSONObject().put("opened", true))
        }
        if (path == "/api/sms" && method == "GET") return sms(context, app)
        if (path == "/api/sms/events" && method == "GET") {
            if (!PermissionManager.hasCapability(context, app?.id, "sms.read")) return error(403, "Missing capability: sms.read")
            return Response.stream("200 OK", "text/event-stream; charset=utf-8", { SmsEventBroadcaster.subscribe(closeAfterEvent = isLocalWebView) },
                mapOf("Cache-Control" to "no-cache, no-transform", "X-Accel-Buffering" to "no"))
        }
        if (path == "/api/sms/threads" && method == "GET") return sms(context, app) { it.threads() }
        if (path == "/api/sms/messages" && method == "POST") {
            return smsMutation(context, app, "sms.send") {
                it.send(requiredHeader(headers, "x-omniand-sms-address"), requiredHeader(headers, "x-omniand-sms-body"))
            }
        }
        val threadMessages = Regex("^/api/sms/threads/([^/]+)/messages$").matchEntire(path)
        if (threadMessages != null && method == "GET") {
            return sms(context, app) { it.messages(threadMessages.groupValues[1]) }
        }
        val thread = Regex("^/api/sms/threads/([^/]+)$").matchEntire(path)
        if (thread != null && method == "DELETE") {
            return smsMutation(context, app, "sms.modify") {
                it.deleteThread(thread.groupValues[1]).also { SmsNotifications.cancelThread(context, thread.groupValues[1]) }
            }
        }
        val threadRead = Regex("^/api/sms/threads/([^/]+)/read$").matchEntire(path)
        if (threadRead != null && method == "POST") {
            return smsMutation(context, app, "sms.modify") {
                it.setThreadRead(threadRead.groupValues[1], requiredHeader(headers, "x-omniand-sms-read")).also {
                    if (requiredHeader(headers, "x-omniand-sms-read") == "true") SmsNotifications.cancelThread(context, threadRead.groupValues[1])
                    SmsReadEventPublisher.publishThread(threadRead.groupValues[1])
                }
            }
        }
        val messageRead = Regex("^/api/sms/messages/([^/]+)/read$").matchEntire(path)
        if (messageRead != null && method == "POST") {
            return smsMutation(context, app, "sms.modify") {
                it.setRead(messageRead.groupValues[1], requiredHeader(headers, "x-omniand-sms-read")).also { result ->
                    if (result.optBoolean("read")) SmsNotifications.cancelThread(context, result.getString("threadId"))
                    SmsReadEventPublisher.publishMessage(messageRead.groupValues[1])
                }
            }
        }
        val singleMessage = Regex("^/api/sms/messages/([^/]+)$").matchEntire(path)
        if (singleMessage != null && method == "GET") {
            return sms(context, app) { it.message(singleMessage.groupValues[1]) }
        }
        if (singleMessage != null && method == "DELETE") {
            return smsMutation(context, app, "sms.modify") {
                it.deleteMessage(singleMessage.groupValues[1]).also { result -> SmsNotifications.cancelThread(context, result.getString("threadId")) }
            }
        }
        if (path == "/api/store/config" && method == "GET" && app?.id == "store") {
            return json(200, JSONObject()
                .put("storeUrl", BuildConfig.STORE_URL)
                .put("installedApps", JSONArray(WebAppRegistry.apps(context)
                    .filter { it.fileRoot != null }
                    .map { it.id })))
        }
        val installPrefix = "/api/apps/install/"
        if (path.startsWith(installPrefix) && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "apps.install")) return error(403, "Missing capability: apps.install")
            return try {
                val installed = WebAppInstaller.install(context, URLDecoder.decode(path.removePrefix(installPrefix), "UTF-8"))
                if (installed.permissions.any { it.startsWith("sms.") }) {
                    SmsSetupManager.recordPending(context, installed.permissions)
                    if (isLocalWebView) SmsSetupManager.request(context, installed.permissions)
                }
                json(200, JSONObject().put("installed", true).put("id", installed.id).put("name", installed.name).put("version", installed.version))
            } catch (error: Exception) {
                Log.w(TAG, "Web application installation rejected", error)
                error(400, error.message ?: "Unable to install application")
            }
        }
        val uninstallPrefix = "/api/apps/uninstall/"
        if (path.startsWith(uninstallPrefix) && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "apps.install")) return error(403, "Missing capability: apps.install")
            val id = URLDecoder.decode(path.removePrefix(uninstallPrefix), "UTF-8")
            return removeWebApp(context, id, isLocalWebView)
        }
        if (WebAppRegistry.isPlatformHost(context, host)) {
            if (path == "/api/apps/web" && method == "GET") {
                val apps = JSONArray().apply {
                    WebAppRegistry.apps(context).forEach { item ->
                        val integration = WrapperInstaller.state(context, item)
                        put(JSONObject()
                            .put("id", item.id).put("name", item.name)
                            .put("version", item.version)
                            .put("updatable", item.fileRoot != null)
                            .put("origin", if (!isLocalWebView) {
                                WebAppRegistry.developmentOriginFor(item, host, PORT)
                            } else {
                                WebAppRegistry.originFor(item)
                            })
                            .put("icon", item.iconPath?.let { "/api/apps/web/${item.id}/icon" } ?: JSONObject.NULL)
                            .put("permissions", JSONArray(item.permissions.toList()))
                            .put("androidIntegration", JSONObject()
                                .put("supported", integration.supported)
                                .put("installed", integration.installed)))
                    }
                }
                return json(200, apps)
            }
            val integrationPrefix = "/api/apps/web/"
            if (path.startsWith(integrationPrefix) && path.endsWith("/update") && method in setOf("GET", "POST")) {
                if (!isLocalWebView) return codedError(403, "phone-local-required", "Web applications can only be updated from the phone")
                val appId = URLDecoder.decode(path.removePrefix(integrationPrefix).removeSuffix("/update"), "UTF-8")
                val installedApp = WebAppRegistry.apps(context).firstOrNull { it.id == appId && it.fileRoot != null }
                    ?: return error(404, "Updatable Web application not found")
                return try {
                    val update = StoreCatalog.check(installedApp)
                    if (method == "GET") {
                        json(200, JSONObject()
                            .put("currentVersion", update.currentVersion)
                            .put("available", update.available)
                            .put("availableVersion", update.availableVersion ?: JSONObject.NULL)
                            .put("addedCapabilities", JSONArray(update.addedCapabilities.sorted())))
                    } else {
                        val expectedVersion = headers["x-omniand-update-version"]
                            ?: return codedError(400, "expected-version-required", "X-OmniAnd-Update-Version is required")
                        if (!update.available || update.availableVersion != expectedVersion || update.catalogApp == null) {
                            return codedError(409, "stale-update", "The selected update is no longer available")
                        }
                        val selected = update.catalogApp
                        val result = WebAppInstaller.install(context, selected.packageUrl,
                            WebAppInstaller.Expected(selected.id, selected.version, selected.permissions))
                        val addedSms = update.addedCapabilities.filterTo(mutableSetOf()) { it.startsWith("sms.") }
                        if (addedSms.isNotEmpty()) {
                            SmsSetupManager.recordPending(context, result.permissions)
                            SmsSetupManager.request(context, result.permissions)
                        }
                        json(200, JSONObject().put("updated", true).put("id", result.id)
                            .put("previousVersion", update.currentVersion).put("newVersion", result.version))
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Web application update failed", error)
                    codedError(400, "update-failed", error.message ?: "Unable to update application")
                }
            }
            if (path.startsWith(integrationPrefix) && path.endsWith("/icon") && method == "GET") {
                val appId = URLDecoder.decode(path.removePrefix(integrationPrefix).removeSuffix("/icon"), "UTF-8")
                val icon = WebAppRegistry.apps(context).firstOrNull { it.id == appId }?.let { readAppIcon(context, it) }
                return if (icon != null) Response("200 OK", "image/png", icon) else error(404, "Application icon not found")
            }
            if (path.startsWith(integrationPrefix) && path.endsWith("/integrate") && method == "POST") {
                if (!isLocalWebView) return error(403, "Android integration can only be installed on the phone")
                val appId = URLDecoder.decode(path.removePrefix(integrationPrefix).removeSuffix("/integrate"), "UTF-8")
                if (WebAppRegistry.apps(context).none { it.id == appId }) return error(404, "Web application not found")
                return try {
                    val appToIntegrate = WebAppRegistry.apps(context).first { it.id == appId }
                    json(200, JSONObject().put("status", WrapperInstaller.install(context, appToIntegrate)))
                } catch (error: Exception) {
                    Log.w(TAG, "Android integration installation failed", error)
                    error(400, error.message ?: "Unable to install Android integration")
                }
            }
            if (path.startsWith(integrationPrefix) && path.endsWith("/uninstall") && method == "POST") {
                if (!isLocalWebView) return error(403, "Web applications can only be removed here from the phone")
                val appId = URLDecoder.decode(path.removePrefix(integrationPrefix).removeSuffix("/uninstall"), "UTF-8")
                return removeWebApp(context, appId, isLocalWebView = true)
            }
        }

        if (app?.assetRoot != null) return staticAsset(context, app.assetRoot, path, app)
        if (app?.fileRoot != null) return staticFile(app.fileRoot, path, app)
        if (WebAppRegistry.isPlatformHost(context, host)) return staticAsset(context, "web/shell", path, null)
        return error(404, "Unknown application origin")
    }

    private fun sms(context: Context, app: WebApp?): Response {
        return sms(context, app) { it.recent() }
    }

    private fun sms(context: Context, app: WebApp?, operation: (SmsService) -> Any): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "sms.read")) return error(403, "Missing capability: sms.read")
        return try {
            json(200, operation(SmsService(context)))
        } catch (_: SmsService.PermissionMissing) {
            codedError(403, "android-permission-required", "Android SMS permission has not been granted")
        } catch (_: SmsService.InvalidId) {
            error(400, "Invalid SMS identifier")
        } catch (_: SmsService.NotFound) {
            error(404, "SMS resource not found")
        } catch (_: Exception) {
            error(500, "Unable to read SMS messages")
        }
    }

    private fun smsMutation(context: Context, app: WebApp?, capability: String, operation: (SmsService) -> Any): Response {
        if (!PermissionManager.hasCapability(context, app?.id, capability)) return error(403, "Missing capability: $capability")
        return try {
            json(200, operation(SmsService(context)))
        } catch (_: SmsService.PermissionMissing) {
            codedError(403, "android-permission-required", "Required Android SMS permission is missing")
        } catch (_: SmsService.RoleRequired) {
            codedError(403, "sms-role-required", "OmniAnd must be the default SMS application for this action")
        } catch (_: SmsService.InvalidInput) {
            error(400, "Invalid SMS request")
        } catch (_: SmsService.InvalidId) {
            error(400, "Invalid SMS identifier")
        } catch (_: SmsService.NotFound) {
            error(404, "SMS resource not found")
        } catch (error: SecurityException) {
            Log.w(TAG, "SMS mutation denied by Android", error)
            codedError(403, "sms-role-required", "Android requires OmniAnd to be the default SMS application for this action")
        } catch (error: Exception) {
            Log.e(TAG, "SMS mutation failed", error)
            error(500, "Unable to change SMS messages")
        }
    }

    private fun requiredHeader(headers: Map<String, String>, name: String): String =
        headers[name]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: throw SmsService.InvalidInput()

    private fun removeWebApp(context: Context, id: String, isLocalWebView: Boolean): Response {
        return try {
            val appToRemove = WebAppRegistry.apps(context).firstOrNull { it.id == id }
                ?: return error(404, "Web application not found")
            if (WrapperInstaller.state(context, appToRemove).installed) {
                if (!isLocalWebView) {
                    return json(409, JSONObject()
                        .put("error", "This app has Android integration and must be uninstalled from the phone")
                        .put("code", "android-integration-phone-required")
                        .put("id", id))
                }
                WrapperInstaller.requestUninstall(context, appToRemove)
                return json(409, JSONObject()
                    .put("error", "Uninstall the Android integration first, then retry")
                    .put("code", "android-integration-installed")
                    .put("id", id))
            }
            WebAppInstaller.uninstall(context, id)
            json(200, JSONObject().put("uninstalled", true).put("id", id))
        } catch (error: Exception) {
            Log.w(TAG, "Web application removal rejected", error)
            error(400, error.message ?: "Unable to remove application")
        }
    }

    private fun readAppIcon(context: Context, app: WebApp): ByteArray? = runCatching {
        val iconPath = app.iconPath ?: return null
        if (app.assetRoot != null) context.assets.open("${app.assetRoot}/$iconPath").use { it.readBytes() }
        else File(app.fileRoot ?: return null, iconPath).readBytes()
    }.getOrNull()

    private fun staticAsset(context: Context, root: String, rawPath: String, app: WebApp?): Response {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            val bytes = context.assets.open("$root/$relative").use { it.readBytes() }
            val csp = app?.let(CspBuilder::build) ?: CspBuilder.buildPlatform()
            Response("200 OK", mime(relative), bytes, csp)
        } catch (_: Exception) {
            error(404, "Not found")
        }
    }

    private fun staticFile(root: java.io.File, rawPath: String, app: WebApp): Response {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            val file = java.io.File(root, relative)
            if (!file.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator)) return error(400, "Invalid path")
            Response("200 OK", mime(relative), file.readBytes(), CspBuilder.build(app))
        } catch (_: Exception) {
            error(404, "Not found")
        }
    }

    private fun json(code: Int, value: Any) = Response(status(code), "application/json; charset=utf-8", value.toString().toByteArray())
    private fun error(code: Int, message: String) = json(code, JSONObject().put("error", message))
    private fun codedError(code: Int, stableCode: String, message: String) =
        json(code, JSONObject().put("error", message).put("code", stableCode))
    private fun status(code: Int) = when (code) { 200 -> "200 OK"; 400 -> "400 Bad Request"; 403 -> "403 Forbidden"; 404 -> "404 Not Found"; 409 -> "409 Conflict"; 413 -> "413 Payload Too Large"; else -> "500 Internal Server Error" }
    private fun mime(path: String) = when {
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

    class Response private constructor(val status: String, val contentType: String, private val fixedBody: ByteArray?,
        val csp: String? = null, private val streamBody: (() -> java.io.InputStream)? = null,
        private val extraHeaders: Map<String, String> = emptyMap()) {
        constructor(status: String, contentType: String, body: ByteArray, csp: String? = null) : this(status, contentType, body, csp, null)
        val statusCode: Int get() = status.substringBefore(' ').toInt()
        val reason: String get() = status.substringAfter(' ')
        val contentLength: Int? get() = fixedBody?.size
        fun openBody(): java.io.InputStream = fixedBody?.inputStream() ?: streamBody!!.invoke()
        val headers: Map<String, String> get() = buildMap {
            put("Cache-Control", "no-store")
            put("X-Content-Type-Options", "nosniff")
            put("Referrer-Policy", "no-referrer")
            csp?.let { put("Content-Security-Policy", it) }
            putAll(extraHeaders)
        }
        companion object {
            fun stream(status: String, contentType: String, body: () -> java.io.InputStream, headers: Map<String, String>) =
                Response(status, contentType, null, null, body, headers)
        }
    }

    private const val TAG = "OmniAndHttp"
    private const val MAX_REQUEST_BODY = 16 * 1024
}
