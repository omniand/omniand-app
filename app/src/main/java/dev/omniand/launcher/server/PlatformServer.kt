package dev.omniand.launcher.server

import android.content.Context
import dev.omniand.launcher.permissions.PermissionManager
import dev.omniand.launcher.services.AndroidAppsService
import dev.omniand.launcher.services.SmsService
import dev.omniand.launcher.webapps.WebApp
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
    const val LAUNCHER_PORT = 8080
    private val started = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val ready = CountDownLatch(3)
        listOf(LAUNCHER_PORT, 8081, 8082).forEach { port ->
            Thread({ serve(appContext, port, ready) }, "platform-http-$port").apply {
                isDaemon = true
                start()
            }
        }
        ready.await(2, TimeUnit.SECONDS)
    }

    private fun serve(context: Context, port: Int, ready: CountDownLatch) {
        try {
            ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).use { server ->
                ready.countDown()
                while (!server.isClosed) {
                    val socket = server.accept()
                    workers.execute {
                        socket.use { client ->
                            runCatching { handle(context, port, client.getInputStream().bufferedReader(), client.getOutputStream()) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            ready.countDown()
            started.set(false)
        }
    }

    private fun handle(context: Context, port: Int, reader: BufferedReader, output: java.io.OutputStream) {
        val request = reader.readLine() ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        var host = "127.0.0.1"
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Host:", true)) host = line.substringAfter(':').trim().substringBefore(':')
        }

        val response = route(context, port, method, path, host)
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

    private fun route(context: Context, port: Int, method: String, path: String, host: String): Response {
        val app = WebAppRegistry.byPort(port)
        if (path == "/api/sms" && method == "GET") return sms(context, app)
        if (port == LAUNCHER_PORT) {
            if (path == "/api/apps/android" && method == "GET") {
                return json(200, AndroidAppsService(context).list())
            }
            if (path == "/api/apps/web" && method == "GET") {
                val apps = JSONArray().apply {
                    WebAppRegistry.apps.forEach { item ->
                        put(JSONObject()
                            .put("id", item.id).put("name", item.name)
                            .put("origin", "http://$host:${item.port}")
                            .put("icon", JSONObject.NULL)
                            .put("permissions", JSONArray(item.permissions.toList())))
                    }
                }
                return json(200, apps)
            }
            val prefix = "/api/apps/android/"
            if (path.startsWith(prefix) && path.endsWith("/launch") && method == "POST") {
                val encoded = path.removePrefix(prefix).removeSuffix("/launch")
                val packageName = URLDecoder.decode(encoded, "UTF-8")
                return if (AndroidAppsService(context).launch(packageName)) json(200, JSONObject().put("launched", true))
                else error(404, "Application is not launchable")
            }
        }

        val root = app?.assetRoot ?: "web/shell"
        return static(context, root, path, app, host)
    }

    private fun sms(context: Context, app: WebApp?): Response {
        if (!PermissionManager.hasCapability(app?.id, "sms.read")) return error(403, "Missing capability: sms.read")
        return try {
            json(200, SmsService(context).recent())
        } catch (_: SmsService.PermissionMissing) {
            error(403, "Android SMS permission has not been granted")
        } catch (_: Exception) {
            error(500, "Unable to read SMS messages")
        }
    }

    private fun static(context: Context, root: String, rawPath: String, app: WebApp?, host: String): Response {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            val bytes = context.assets.open("$root/$relative").use { it.readBytes() }
            val csp = app?.let(CspBuilder::build) ?: CspBuilder.buildShell(host)
            Response("200 OK", mime(relative), bytes, csp)
        } catch (_: Exception) {
            error(404, "Not found")
        }
    }

    private fun json(code: Int, value: Any) = Response(status(code), "application/json; charset=utf-8", value.toString().toByteArray())
    private fun error(code: Int, message: String) = json(code, JSONObject().put("error", message))
    private fun status(code: Int) = when (code) { 200 -> "200 OK"; 400 -> "400 Bad Request"; 403 -> "403 Forbidden"; 404 -> "404 Not Found"; else -> "500 Internal Server Error" }
    private fun mime(path: String) = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "text/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }

    private data class Response(val status: String, val contentType: String, val body: ByteArray, val csp: String? = null)
}
