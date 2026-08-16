package dev.omniand.launcher.server

import android.content.Context
import android.util.Log
import dev.omniand.launcher.BuildConfig
import dev.omniand.launcher.permissions.PermissionManager
import dev.omniand.launcher.services.AndroidAppsService
import dev.omniand.launcher.services.SmsService
import dev.omniand.launcher.webapps.WebApp
import dev.omniand.launcher.webapps.WebAppInstaller
import dev.omniand.launcher.webapps.WebAppRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
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
        val body = if (contentLength > 0) CharArray(contentLength).also {
            var offset = 0
            while (offset < it.size) {
                val count = reader.read(it, offset, it.size - offset)
                if (count < 0) break
                offset += count
            }
        }.concatToString() else ""

        val response = route(context, method, path, host.lowercase(), body)
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

    private fun route(context: Context, method: String, path: String, host: String, body: String): Response {
        val app = WebAppRegistry.byHost(context, host)
        if (path == "/api/sms" && method == "GET") return sms(context, app)
        if (path == "/api/store/config" && method == "GET" && app?.id == "store") {
            return json(200, JSONObject()
                .put("storeUrl", BuildConfig.STORE_URL)
                .put("installedApps", JSONArray(WebAppRegistry.apps(context)
                    .filter { it.fileRoot != null }
                    .map { it.id })))
        }
        if (path == "/api/apps/install" && method == "POST") return install(context, app, body)
        if (path == "/api/apps/uninstall" && method == "POST") return uninstall(context, app, body)
        if (WebAppRegistry.isLauncherHost(context, host)) {
            if (path == "/api/apps/android" && method == "GET") {
                return json(200, AndroidAppsService(context).list())
            }
            if (path == "/api/apps/web" && method == "GET") {
                val apps = JSONArray().apply {
                    WebAppRegistry.apps(context).forEach { item ->
                        put(JSONObject()
                            .put("id", item.id).put("name", item.name)
                            .put("origin", WebAppRegistry.originFor(item, host, PORT))
                            .put("icon", JSONObject.NULL)
                            .put("permissions", JSONArray(item.permissions.toList())))
                    }
                }
                return json(200, apps)
            }
            val prefix = "/api/apps/android/"
            if (path.startsWith(prefix) && path.endsWith("/icon") && method == "GET") {
                val encoded = path.removePrefix(prefix).removeSuffix("/icon")
                val packageName = URLDecoder.decode(encoded, "UTF-8")
                val icon = AndroidAppsService(context).icon(packageName)
                return if (icon != null) Response("200 OK", "image/png", icon)
                else error(404, "Application icon not found")
            }
            if (path.startsWith(prefix) && path.endsWith("/launch") && method == "POST") {
                val encoded = path.removePrefix(prefix).removeSuffix("/launch")
                val packageName = URLDecoder.decode(encoded, "UTF-8")
                return if (AndroidAppsService(context).launch(packageName)) json(200, JSONObject().put("launched", true))
                else error(404, "Application is not launchable")
            }
        }

        if (app != null) return static(context, path, app, host)
        if (WebAppRegistry.isLauncherHost(context, host)) return staticAsset(context, "web/shell", path, null, host)
        return error(404, "Unknown application origin")
    }

    private fun sms(context: Context, app: WebApp?): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "sms.read")) return error(403, "Missing capability: sms.read")
        return try {
            json(200, SmsService(context).recent())
        } catch (_: SmsService.PermissionMissing) {
            error(403, "Android SMS permission has not been granted")
        } catch (_: Exception) {
            error(500, "Unable to read SMS messages")
        }
    }

    private fun install(context: Context, app: WebApp?, body: String): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "apps.install")) {
            return error(403, "Missing capability: apps.install")
        }
        return try {
            val packageUrl = JSONObject(body).getString("packageUrl")
            val installed = WebAppInstaller.install(context, packageUrl)
            json(200, JSONObject()
                .put("installed", true)
                .put("id", installed.id)
                .put("name", installed.name)
                .put("version", installed.version))
        } catch (error: Exception) {
            Log.w(TAG, "Web application installation rejected", error)
            error(400, error.message ?: "Unable to install application")
        }
    }

    private fun uninstall(context: Context, app: WebApp?, body: String): Response {
        if (!PermissionManager.hasCapability(context, app?.id, "apps.install")) {
            return error(403, "Missing capability: apps.install")
        }
        return try {
            val id = JSONObject(body).getString("id")
            WebAppInstaller.uninstall(context, id)
            json(200, JSONObject().put("uninstalled", true).put("id", id))
        } catch (error: Exception) {
            Log.w(TAG, "Web application removal rejected", error)
            error(400, error.message ?: "Unable to remove application")
        }
    }

    private fun static(context: Context, rawPath: String, app: WebApp, host: String): Response {
        if (app.assetRoot != null) return staticAsset(context, app.assetRoot, rawPath, app, host)
        val root = app.fileRoot ?: return error(404, "Not found")
        return staticFile(root, rawPath, app)
    }

    private fun staticAsset(context: Context, root: String, rawPath: String, app: WebApp?, host: String): Response {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            val bytes = context.assets.open("$root/$relative").use { it.readBytes() }
            val origins = WebAppRegistry.apps(context).map { WebAppRegistry.originFor(it, host, PORT) }
            val csp = app?.let(CspBuilder::build) ?: CspBuilder.buildShell(origins)
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
    private fun status(code: Int) = when (code) { 200 -> "200 OK"; 400 -> "400 Bad Request"; 403 -> "403 Forbidden"; 404 -> "404 Not Found"; 413 -> "413 Payload Too Large"; else -> "500 Internal Server Error" }
    private fun mime(path: String) = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "text/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }

    private data class Response(val status: String, val contentType: String, val body: ByteArray, val csp: String? = null)

    private const val TAG = "OmniAndHttp"
    private const val MAX_REQUEST_BODY = 16 * 1024
}
