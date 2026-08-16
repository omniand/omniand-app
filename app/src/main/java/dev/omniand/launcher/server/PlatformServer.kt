package dev.omniand.launcher.server

import android.content.Context
import android.util.Log
import dev.omniand.launcher.BuildConfig
import dev.omniand.launcher.permissions.PermissionManager
import dev.omniand.launcher.services.SmsService
import dev.omniand.launcher.webapps.WebApp
import dev.omniand.launcher.webapps.WebAppInstaller
import dev.omniand.launcher.webapps.WebAppRegistry
import dev.omniand.launcher.wrappers.WrapperInstaller
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
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
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

        val response = route(context, method, path, host.lowercase(), isLocalWebView = false)
        write(output, response)
    }

    private fun write(output: java.io.OutputStream, response: Response) {
        val bytes = response.body
        val headers = buildString {
            append("HTTP/1.1 ${response.status}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            response.csp?.let { append("Content-Security-Policy: $it\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    fun localResponse(context: Context, method: String, path: String, host: String): Response =
        route(context.applicationContext, method, path.substringBefore('?'), host.lowercase(), isLocalWebView = true)

    private fun route(context: Context, method: String, path: String, host: String, isLocalWebView: Boolean): Response {
        val app = WebAppRegistry.byHost(context, host)
        if (path == "/api/sms" && method == "GET") return sms(context, app)
        if (path == "/api/sms/threads" && method == "GET") return sms(context, app) { it.threads() }
        val threadMessages = Regex("^/api/sms/threads/([^/]+)/messages$").matchEntire(path)
        if (threadMessages != null && method == "GET") {
            return sms(context, app) { it.messages(threadMessages.groupValues[1]) }
        }
        val singleMessage = Regex("^/api/sms/messages/([^/]+)$").matchEntire(path)
        if (singleMessage != null && method == "GET") {
            return sms(context, app) { it.message(singleMessage.groupValues[1]) }
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
            error(403, "Android SMS permission has not been granted")
        } catch (_: SmsService.InvalidId) {
            error(400, "Invalid SMS identifier")
        } catch (_: SmsService.NotFound) {
            error(404, "SMS resource not found")
        } catch (_: Exception) {
            error(500, "Unable to read SMS messages")
        }
    }

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

    data class Response(val status: String, val contentType: String, val body: ByteArray, val csp: String? = null) {
        val statusCode: Int get() = status.substringBefore(' ').toInt()
        val reason: String get() = status.substringAfter(' ')
        val headers: Map<String, String> get() = buildMap {
            put("Cache-Control", "no-store")
            put("X-Content-Type-Options", "nosniff")
            put("Referrer-Policy", "no-referrer")
            csp?.let { put("Content-Security-Policy", it) }
        }
    }

    private const val TAG = "OmniAndHttp"
    private const val MAX_REQUEST_BODY = 16 * 1024
}
