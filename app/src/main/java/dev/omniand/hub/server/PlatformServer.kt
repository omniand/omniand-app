package dev.omniand.hub.server

import android.content.Context
import android.os.Build
import android.util.Log
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.DesktopPairingNotifications
import dev.omniand.hub.contacts.ContactsEventBroadcaster
import dev.omniand.hub.contacts.ContactsSetupManager
import dev.omniand.hub.media.MediaDeleteActivity
import dev.omniand.hub.media.MediaEventBroadcaster
import dev.omniand.hub.media.MediaSetupManager
import dev.omniand.hub.permissions.PermissionManager
import dev.omniand.hub.services.ContactsService
import dev.omniand.hub.services.MediaService
import dev.omniand.hub.services.SmsService
import dev.omniand.hub.sms.SmsEventBroadcaster
import dev.omniand.hub.sms.SmsNotifications
import dev.omniand.hub.sms.SmsReadEventPublisher
import dev.omniand.hub.sms.SmsSetupManager
import dev.omniand.hub.webapps.SemanticVersion
import dev.omniand.hub.webapps.StoreCatalog
import dev.omniand.hub.webapps.WebApp
import dev.omniand.hub.webapps.WebAppInstaller
import dev.omniand.hub.webapps.WebAppRegistry
import dev.omniand.hub.wrappers.WrapperInstaller
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.net.URLDecoder
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

    @Synchronized
    fun start(context: Context): Boolean {
        if (started.get()) return true
        val appContext = context.applicationContext
        return runCatching {
                KtorServer.start(appContext)
                ContactsEventBroadcaster.start(appContext)
                MediaEventBroadcaster.start(appContext)
                started.set(true)
                true
            }
            .getOrElse {
                started.set(false)
                Log.e(TAG, "Ktor server failed to start", it)
                false
            }
    }

    internal fun networkResponse(
        context: Context,
        method: String,
        path: String,
        authority: String,
        headers: Map<String, String>,
        body: ByteArray,
        peerAddress: String,
    ): PlatformContent {
        val normalizedHeaders = headers.mapKeys { it.key.lowercase() }
        val requestPath = path.substringBefore('?')
        val requestContext =
            authenticateRequest(
                context,
                authority,
                peerAddress,
                method,
                requestPath,
                normalizedHeaders,
            )
                ?: return desktopPairingResponse(
                    context,
                    method,
                    requestPath,
                    authority,
                    peerAddress,
                    normalizedHeaders,
                ) ?: codedError(401, "authentication-required", "Unauthorized")
        return try {
            route(
                context,
                method,
                requestPath,
                parseQuery(path.substringAfter('?', "")),
                requestContext,
                normalizedHeaders,
                body,
            )
        } catch (error: InvalidRequest) {
            codedError(error.status, error.code, error.message ?: "Invalid request")
        }
    }

    /** Authenticates localhost before deriving the app identity used by capability checks. */
    internal fun authenticateRequest(
        context: Context,
        authority: String,
        peerAddress: String,
        method: String,
        path: String,
        headers: Map<String, String>,
    ): PlatformRequestContext? {
        val parsed = parseAuthority(authority) ?: return null
        val hostname = parsed.first
        val port = parsed.second
        val localhost = hostname == "localhost" || hostname.endsWith(".localhost")
        if (!localhost) {
            val app = WebAppRegistry.byCanonicalHost(context, hostname)
            if (
                (hostname != BuildConfig.PLATFORM_HOST && app == null) ||
                    port !in setOf(null, 443) ||
                    !DesktopPairing.verify(headers["cookie"])
            )
                return null
            if (
                path.startsWith("/api/") &&
                    method.uppercase() in setOf("POST", "PUT", "PATCH", "DELETE") &&
                    headers["origin"] != "https://$hostname"
            )
                return null
            return PlatformRequestContext(
                authority.lowercase(),
                hostname,
                PlatformRequestContext.Transport.DESKTOP_HTTP,
                false,
                app,
            )
        }
        if (port != PORT || !LocalSessionAuthenticator.isLoopback(peerAddress)) return null
        val app =
            if (hostname == "localhost") null
            else
                WebAppRegistry.apps(context).firstOrNull { "${it.id}.localhost" == hostname }
                    ?: return null
        if (!LocalSessionAuthenticator.verify(hostname, headers["cookie"])) return null
        if (
            path.startsWith("/api/") &&
                method.uppercase() in setOf("POST", "PUT", "PATCH", "DELETE") &&
                headers["origin"] != "http://${authority.lowercase()}"
        )
            return null
        return PlatformRequestContext(
            authority.lowercase(),
            hostname,
            PlatformRequestContext.Transport.LOOPBACK_HTTP,
            true,
            app,
        )
    }

    internal fun hasCapability(
        context: Context,
        request: PlatformRequestContext,
        capability: String,
    ) = PermissionManager.hasCapability(context, request.app?.id, capability)

    internal fun canManageCatalog(request: PlatformRequestContext): Boolean =
        request.phoneClient && request.app == null && request.hostname == "localhost"

    /** Serves only the bounded unauthenticated handshake on the configured canonical Home host. */
    private fun desktopPairingResponse(
        context: Context,
        method: String,
        path: String,
        authority: String,
        peerAddress: String,
        headers: Map<String, String>,
    ): PlatformContent? {
        val parsed = parseAuthority(authority) ?: return null
        val hostname = parsed.first
        if (hostname == "localhost" || hostname.endsWith(".localhost")) return null
        val canonicalApp = WebAppRegistry.byCanonicalHost(context, hostname)
        if (
            (hostname != BuildConfig.PLATFORM_HOST && canonicalApp == null) ||
                parsed.second !in setOf(null, 443)
        )
            return null
        val pairingAsset = if (method == "GET") pairingAssetPath(path) else null
        if (pairingAsset != null) {
            val bytes = context.assets.open("web/pairing/$pairingAsset").use { it.readBytes() }
            return PlatformContent(
                "200 OK",
                mime(pairingAsset),
                bytes,
                CspBuilder.buildPairing(),
            )
        }
        if (path == "/api/pairing/request" && method == "POST") {
            if (headers["origin"] != "https://$hostname") return null
            val request =
                DesktopPairing.requestId(headers["cookie"])?.let(DesktopPairing::pending)
                    ?: try {
                        DesktopPairing.create(peerAddress, headers["user-agent"].orEmpty())
                    } catch (_: DesktopPairing.Throttled) {
                        return codedError(429, "pairing-rate-limited", "Try again later")
                    }
            DesktopPairingNotifications.publish(context, request)
            return PlatformContent.bytes(
                "202 Accepted",
                "application/json; charset=utf-8",
                JSONObject().put("pending", true).toString().toByteArray(),
                mapOf(
                    "Set-Cookie" to
                        "${DesktopPairing.REQUEST_COOKIE}=${request.id}; Path=/api/pairing; " +
                            "HttpOnly; Secure; SameSite=Strict"
                ),
            )
        }
        if (path == "/api/pairing/status" && method == "GET") {
            val id =
                DesktopPairing.requestId(headers["cookie"])
                    ?: return codedError(400, "pairing-request-missing", "Pairing request missing")
            val claim = DesktopPairing.claim(id)
            val body = JSONObject().put("status", claim.decision.name.lowercase())
            val responseHeaders =
                claim.token?.let {
                    mapOf(
                        "Set-Cookie" to
                            "${DesktopPairing.SESSION_COOKIE}=$it; Domain=${BuildConfig.PLATFORM_HOST}; " +
                                "Path=/; HttpOnly; Secure; SameSite=Strict"
                    )
                } ?: emptyMap()
            return PlatformContent.bytes(
                "200 OK",
                "application/json; charset=utf-8",
                body.toString().toByteArray(),
                responseHeaders,
            )
        }
        return null
    }

    /** Maps the complete and intentionally small pre-authentication pairing asset surface. */
    internal fun pairingAssetPath(path: String): String? =
        when {
            path == "/" -> "index.html"
            path in setOf("/pairing.js", "/pairing.css", "/i18n.js") -> path.removePrefix("/")
            path.matches(Regex("^/locales/(?:en|fr)\\.json$")) -> path.removePrefix("/")
            path == "/vendor/i18next/i18next.min.js" -> path.removePrefix("/")
            else -> null
        }

    internal fun parseAuthority(authority: String): Pair<String, Int?>? {
        val value = authority.trim().lowercase()
        if (value.isEmpty() || value.contains('/') || value.contains('@')) return null
        if (value.startsWith('[')) {
            val end = value.indexOf(']')
            if (end < 0) return null
            val suffix = value.substring(end + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
            val port = suffix.removePrefix(":").takeIf { it.isNotEmpty() }
            if (port != null && port.toIntOrNull() == null) return null
            return value.substring(1, end) to port?.toInt()
        }
        if (value.count { it == ':' } > 1) return null
        val hostname = value.substringBefore(':')
        if (hostname.isEmpty()) return null
        val rawPort = value.substringAfter(':', "")
        if (rawPort.isNotEmpty() && rawPort.toIntOrNull() == null) return null
        return hostname to rawPort.takeIf { it.isNotEmpty() }?.toInt()
    }

    private fun route(
        context: Context,
        method: String,
        path: String,
        query: Map<String, String>,
        request: PlatformRequestContext,
        headers: Map<String, String>,
        body: ByteArray,
    ): PlatformContent {
        val app = request.app
        val host = request.hostname
        val isLocalWebView = request.phoneClient
        val isLocalPlatformHome = canManageCatalog(request)
        if (
            path == DesktopNavigationBar.SCRIPT_PATH &&
                method == "GET" &&
                app != null &&
                !isLocalWebView
        )
            return PlatformContent(
                "200 OK",
                "text/javascript; charset=utf-8",
                DesktopNavigationBar.script(),
                CspBuilder.build(app),
            )
        if (path == "/api/media/setup" && method == "GET") {
            if (!hasMediaCapability(context, app))
                return codedError(403, "missing-capability", "Missing Media capability")
            return json(200, MediaSetupManager.state(context, localTransport = false))
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
            return PlatformContent.stream(
                "200 OK",
                "text/event-stream; charset=utf-8",
                { MediaEventBroadcaster.subscribe(closeAfterEvent = false) },
                mapOf("Cache-Control" to "no-cache, no-transform", "X-Accel-Buffering" to "no"),
            )
        }
        if (path == "/api/media" && method == "GET")
            return mediaRead(context, app) {
                it.list(
                    query["type"] ?: "all",
                    query["offset"]?.toIntOrNull() ?: 0,
                    query["limit"]?.toIntOrNull() ?: 60,
                    query["folder"],
                )
            }
        if (path == "/api/media/folders" && method == "GET")
            return mediaRead(context, app) {
                it.folders(
                    query["offset"]?.toIntOrNull() ?: 0,
                    query["limit"]?.toIntOrNull() ?: 60,
                )
            }
        if (path == "/api/media/delete" && method == "POST") {
            if (!PermissionManager.hasCapability(context, app?.id, "media.write"))
                return codedError(403, "missing-capability", "Missing capability: media.write")
            return mediaOperation {
                val ids = requireJson(headers, body).requiredStringArray("ids")
                val requested = List(ids.length()) { ids.getString(it) }
                val service = MediaService(context)
                val plan = service.deletePlan(requested)
                if (isLocalWebView && Build.VERSION.SDK_INT >= 30 && plan.needsConfirmation) {
                    MediaDeleteActivity.request(context, plan.uris)
                    JSONObject().put("pending", true)
                } else {
                    service.delete(requested)
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
            return PlatformContent.stream(
                "200 OK",
                "text/event-stream; charset=utf-8",
                { ContactsEventBroadcaster.subscribe(closeAfterEvent = false) },
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
                it.match(requireJson(headers, body).requiredStringArray("numbers"))
            }
        if (path == "/api/contacts" && method == "POST")
            return contactsWrite(context, app) {
                it.create(requireJson(headers, body))
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
                val bytes = if (method == "DELETE") null else requireJpeg(headers, body)
                if (bytes != null && bytes.size > MAX_CONTACT_PHOTO)
                    throw ContactsService.InvalidInput()
                it.setPhoto(
                    URLDecoder.decode(contactPhoto.groupValues[1], "UTF-8"),
                    query["source"]?.takeIf { it.isNotBlank() && it.length <= 256 }
                        ?: throw InvalidRequest(400, "invalid-request", "source is required"),
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
                val document = requireJson(headers, body)
                it.update(
                    URLDecoder.decode(contactPath.groupValues[1], "UTF-8"),
                    document.requiredString("source", 256),
                    requireIfMatch(headers),
                    document.requiredObject("data"),
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
            return PlatformContent.stream(
                "200 OK",
                "text/event-stream; charset=utf-8",
                { SmsEventBroadcaster.subscribe(closeAfterEvent = false) },
                mapOf("Cache-Control" to "no-cache, no-transform", "X-Accel-Buffering" to "no"),
            )
        }
        if (path == "/api/sms/threads" && method == "GET")
            return sms(context, app) { it.threads(query["offset"], query["limit"]) }
        if (path == "/api/sms/messages" && method == "POST") {
            return smsMutation(context, app, "sms.send") {
                val document = requireJson(headers, body)
                val uploads = document.requiredStringArray("uploads")
                it.send(
                    document.requiredString("address"),
                    document.requiredString("body"),
                    document.optionalString("subject"),
                    List(uploads.length()) { uploads.getString(it) },
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
                val read = requireJson(headers, body).requiredBoolean("read")
                it.setThreadRead(
                        threadRead.groupValues[1],
                        read.toString(),
                    )
                    .also {
                        if (read) SmsNotifications.cancelThread(context, threadRead.groupValues[1])
                        SmsReadEventPublisher.publishThread(threadRead.groupValues[1])
                    }
            }
        }
        val messageRead = Regex("^/api/sms/messages/([^/]+)/read$").matchEntire(path)
        if (messageRead != null && method == "POST") {
            return smsMutation(context, app, "sms.modify") {
                val read = requireJson(headers, body).requiredBoolean("read")
                it.setRead(
                        URLDecoder.decode(messageRead.groupValues[1], "UTF-8"),
                        read.toString(),
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
        if (path == "/api/apps/catalog" && method == "GET") {
            if (!isLocalPlatformHome)
                return codedError(403, "phone-local-required", "Catalog access is phone-local")
            return catalogResponse(context)
        }
        val catalogIcon = Regex("^/api/apps/catalog/([^/]+)/icon$").matchEntire(path)
        if (catalogIcon != null && method == "GET") {
            if (!isLocalPlatformHome)
                return codedError(403, "phone-local-required", "Catalog access is phone-local")
            return try {
                val id = URLDecoder.decode(catalogIcon.groupValues[1], "UTF-8")
                val selected =
                    StoreCatalog.fetch().singleOrNull { it.id == id }
                        ?: return error(404, "Catalog application not found")
                PlatformContent("200 OK", "image/png", StoreCatalog.fetchIcon(selected))
            } catch (error: Exception) {
                Log.w(TAG, "Catalog icon rejected", error)
                codedError(502, "catalog-unavailable", error.message ?: "Catalog unavailable")
            }
        }
        val catalogInstall = Regex("^/api/apps/catalog/([^/]+)/install$").matchEntire(path)
        if (catalogInstall != null && method == "POST") {
            if (!isLocalPlatformHome)
                return codedError(403, "phone-local-required", "Installation is phone-local")
            return installCatalogApp(
                context,
                URLDecoder.decode(catalogInstall.groupValues[1], "UTF-8"),
                headers,
                body,
            )
        }
        val operationPrefix = "/api/apps/operations/"
        if (path.startsWith(operationPrefix) && method == "GET") {
            if (!isLocalPlatformHome)
                return codedError(403, "phone-local-required", "Operations are phone-local")
            val operationId = URLDecoder.decode(path.removePrefix(operationPrefix), "UTF-8")
            val operation =
                dev.omniand.hub.wrappers.InstallOperations.get(context, operationId)
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
                    dev.omniand.hub.wrappers.InstallOperations.update(
                        context,
                        operationId,
                        "installed",
                    )
                    operation.put("status", "installed")
                }
            }
            return json(200, operation)
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
                                        when {
                                            isLocalWebView ->
                                                WebAppRegistry.localhostOriginFor(item)
                                            host == BuildConfig.PLATFORM_HOST ->
                                                WebAppRegistry.originFor(item)
                                            else ->
                                                WebAppRegistry.developmentOriginFor(
                                                    item,
                                                    host,
                                                    PORT,
                                                )
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
                return if (icon != null) PlatformContent("200 OK", "image/png", icon)
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
                request.authority,
            )
        if (app?.packageName != null)
            return staticPackageAsset(
                context,
                app,
                path,
                isLocalWebView,
                request.authority,
            )
        if (WebAppRegistry.isPlatformHost(context, host))
            return staticAsset(
                context,
                "web/shell",
                platformShellAssetPath(path),
                null,
                isLocalWebView,
                host,
            )
        return error(404, "Unknown application origin")
    }

    /** Resolves client-owned Shell routes to the embedded SPA entry document. */
    internal fun platformShellAssetPath(path: String): String =
        if (path == "/discover" || path.matches(Regex("^/discover/[^/]+$"))) "/" else path

    private fun sms(context: Context, app: WebApp?): PlatformContent {
        return sms(context, app) { it.recent() }
    }

    private fun smsPart(
        context: Context,
        app: WebApp?,
        messageId: String,
        partId: String,
        range: String?,
    ): PlatformContent {
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
                PlatformContent.bytes(
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
                PlatformContent.bytes(
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
    ): PlatformContent {
        if (!PermissionManager.hasCapability(context, app?.id, "contacts.read"))
            return codedError(403, "missing-capability", "Missing capability: contacts.read")
        return contactsOperation(context, operation)
    }

    private fun contactsWrite(
        context: Context,
        app: WebApp?,
        operation: (ContactsService) -> Any,
    ): PlatformContent {
        if (!PermissionManager.hasCapability(context, app?.id, "contacts.write"))
            return codedError(403, "missing-capability", "Missing capability: contacts.write")
        return contactsOperation(context, operation)
    }

    private fun contactsOperation(
        context: Context,
        operation: (ContactsService) -> Any,
    ): PlatformContent =
        try {
            ContactsEventBroadcaster.start(context)
            json(200, operation(ContactsService(context)))
        } catch (error: InvalidRequest) {
            throw error
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

    private fun contactsPhoto(context: Context, app: WebApp?, key: String): PlatformContent {
        if (!PermissionManager.hasCapability(context, app?.id, "contacts.read"))
            return codedError(403, "missing-capability", "Missing capability: contacts.read")
        return try {
            PlatformContent("200 OK", "image/jpeg", ContactsService(context).photo(key))
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
    ): PlatformContent {
        if (!PermissionManager.hasCapability(context, app?.id, "media.read"))
            return codedError(403, "missing-capability", "Missing capability: media.read")
        MediaEventBroadcaster.start(context)
        return mediaOperation { operation(MediaService(context)) }
    }

    private fun mediaOperation(operation: () -> Any): PlatformContent =
        try {
            json(200, operation())
        } catch (error: InvalidRequest) {
            throw error
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

    private fun mediaThumbnail(context: Context, app: WebApp?, id: String): PlatformContent {
        if (!PermissionManager.hasCapability(context, app?.id, "media.read"))
            return codedError(403, "missing-capability", "Missing capability: media.read")
        return try {
            PlatformContent.bytes(
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
    private fun mediaContent(
        context: Context,
        app: WebApp?,
        id: String,
        range: String?,
    ): PlatformContent {
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
            PlatformContent.stream(
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

    /** Parses a bounded JSON mutation after authorization has selected the route. */
    private fun requireJson(headers: Map<String, String>, body: ByteArray): JSONObject {
        val contentType = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != "application/json")
            throw InvalidRequest(
                415,
                "unsupported-media-type",
                "Content-Type must be application/json",
            )
        if (body.isEmpty()) throw InvalidRequest(400, "invalid-json", "A JSON body is required")
        return runCatching { JSONObject(body.toString(Charsets.UTF_8)) }
            .getOrElse { throw InvalidRequest(400, "invalid-json", "Malformed JSON request") }
    }

    private fun requireJpeg(headers: Map<String, String>, body: ByteArray): ByteArray {
        if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "image/jpeg")
            throw InvalidRequest(415, "unsupported-media-type", "Content-Type must be image/jpeg")
        if (body.isEmpty() || body.size > MAX_CONTACT_PHOTO)
            throw InvalidRequest(400, "invalid-contact-request", "Invalid contact photo")
        return body
    }

    private fun JSONObject.requiredString(name: String, max: Int = MAX_REQUEST_BODY): String =
        opt(name)?.takeIf { it is String }?.let { it as String }?.takeIf { it.length <= max }
            ?: throw InvalidRequest(400, "invalid-json", "$name must be a string")

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return opt(name)?.takeIf { it is String } as? String
            ?: throw InvalidRequest(400, "invalid-json", "$name must be a string")
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean =
        (opt(name) as? Boolean)
            ?: throw InvalidRequest(400, "invalid-json", "$name must be a boolean")

    private fun JSONObject.requiredObject(name: String): JSONObject =
        opt(name) as? JSONObject
            ?: throw InvalidRequest(400, "invalid-json", "$name must be an object")

    private fun JSONObject.requiredStringArray(name: String): JSONArray {
        val values =
            opt(name) as? JSONArray
                ?: throw InvalidRequest(400, "invalid-json", "$name must be an array")
        repeat(values.length()) { index ->
            if (values.opt(index) !is String)
                throw InvalidRequest(400, "invalid-json", "$name must contain only strings")
        }
        return values
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
            "invalid-folder" -> "The media folder identifier is malformed"
            "folder-not-found" -> "The media folder is no longer available"
            "storage-unavailable" -> "The destination folder is not writable"
            "staging-limit" -> "The active upload staging limit was reached"
            "file-count-limit" -> "At most 20 active uploads are allowed per application"
            else -> "Invalid media request"
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

    private fun sms(
        context: Context,
        app: WebApp?,
        operation: (SmsService) -> Any,
    ): PlatformContent {
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
    ): PlatformContent {
        if (!PermissionManager.hasCapability(context, app?.id, capability))
            return error(403, "Missing capability: $capability")
        return try {
            json(200, operation(SmsService(context)))
        } catch (error: InvalidRequest) {
            throw error
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

    /** Returns display-only catalog metadata with installation state computed on every request. */
    private fun catalogResponse(context: Context): PlatformContent =
        try {
            val installed = WebAppRegistry.apps(context).associateBy { it.id }
            val entries =
                JSONArray().apply {
                    StoreCatalog.fetch().forEach { item ->
                        val current = installed[item.id]
                        val state = StoreCatalog.state(item.version, current?.version)
                        put(
                            JSONObject()
                                .put("id", item.id)
                                .put("name", item.name)
                                .put("tagline", item.tagline)
                                .put("version", item.version)
                                .put("category", item.category)
                                .put("permissions", JSONArray(item.permissions.sorted()))
                                .put("icon", "/api/apps/catalog/${item.id}/icon")
                                .put("installedVersion", current?.version ?: JSONObject.NULL)
                                .put("state", state)
                        )
                    }
                }
            json(200, entries)
        } catch (error: Exception) {
            Log.w(TAG, "Catalog unavailable", error)
            codedError(502, "catalog-unavailable", error.message ?: "Catalog unavailable")
        }

    /** Resolves the requested id/version from a fresh catalog before downloading its package. */
    private fun installCatalogApp(
        context: Context,
        id: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): PlatformContent {
        return try {
            val version = requireJson(headers, body).requiredString("version")
            val selected =
                StoreCatalog.fetch().singleOrNull { it.id == id && it.version == version }
                    ?: return codedError(
                        409,
                        "stale-catalog",
                        "The selected version is no longer available",
                    )
            val installed = WebAppRegistry.apps(context).firstOrNull { it.id == id }
            if (
                installed != null &&
                    SemanticVersion.compare(version, installed.version)?.let { it <= 0 } != false
            )
                return codedError(409, "version-not-newer", "The selected version is not newer")
            WebAppInstaller.prepare(
                    context,
                    selected.packageUrl,
                    WebAppInstaller.Expected(selected.id, selected.version, selected.permissions),
                )
                .use { validated -> json(202, WrapperInstaller.install(context, validated)) }
        } catch (error: InvalidRequest) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Catalog installation rejected", error)
            codedError(400, "install-failed", error.message ?: "Unable to install application")
        }
    }

    private fun removeWebApp(
        context: Context,
        id: String,
        isLocalWebView: Boolean,
    ): PlatformContent {
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
            val operation =
                dev.omniand.hub.wrappers.InstallOperations.create(context, id, "uninstall")
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
    ): PlatformContent {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            val bytes = context.assets.open("$root/$relative").use { it.readBytes() }
            val body = desktopDocument(bytes, relative, app, isLocalWebView, requestAuthority)
            val csp = app?.let(CspBuilder::build) ?: CspBuilder.buildPlatform()
            PlatformContent("200 OK", mime(relative), body, csp)
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
    ): PlatformContent {
        val relative = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        if (relative.contains("..")) return error(400, "Invalid path")
        return try {
            PlatformContent(
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
        PlatformContent(
            status(code),
            "application/json; charset=utf-8",
            value.toString().toByteArray(),
        )

    private fun error(code: Int, message: String) = json(code, JSONObject().put("error", message))

    private fun codedError(code: Int, stableCode: String, message: String) =
        json(code, JSONObject().put("error", message).put("code", stableCode))

    private fun status(code: Int) =
        when (code) {
            200 -> "200 OK"
            202 -> "202 Accepted"
            206 -> "206 Partial Content"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            403 -> "403 Forbidden"
            404 -> "404 Not Found"
            409 -> "409 Conflict"
            501 -> "501 Not Implemented"
            413 -> "413 Payload Too Large"
            415 -> "415 Unsupported Media Type"
            416 -> "416 Range Not Satisfiable"
            429 -> "429 Too Many Requests"
            502 -> "502 Bad Gateway"
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

    /** Native Ktor output that preserves fixed and streamed business responses. */
    class PlatformContent
    private constructor(
        private val statusLine: String,
        private val contentTypeValue: String,
        private val fixedBody: ByteArray?,
        val csp: String? = null,
        private val streamBody: (() -> java.io.InputStream)? = null,
        private val extraHeaders: Map<String, String> = emptyMap(),
    ) : OutgoingContent.WriteChannelContent() {
        constructor(
            status: String,
            contentType: String,
            body: ByteArray,
            csp: String? = null,
        ) : this(status, contentType, body, csp, null)

        override val status: HttpStatusCode
            get() = HttpStatusCode.fromValue(statusLine.substringBefore(' ').toInt())

        override val contentType: ContentType
            get() = ContentType.parse(contentTypeValue)

        override val contentLength: Long?
            get() = fixedBody?.size?.toLong()

        override val headers: Headers
            get() = Headers.build {
                append("Cache-Control", "no-store")
                append("X-Content-Type-Options", "nosniff")
                append("Referrer-Policy", "no-referrer")
                csp?.let { append("Content-Security-Policy", it) }
                extraHeaders.forEach { (name, value) -> append(name, value) }
            }

        override suspend fun writeTo(channel: ByteWriteChannel) {
            (fixedBody?.inputStream() ?: streamBody!!.invoke()).use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    channel.writeFully(buffer, 0, count)
                }
            }
        }

        companion object {
            fun bytes(
                status: String,
                contentType: String,
                body: ByteArray,
                headers: Map<String, String>,
            ) = PlatformContent(status, contentType, body, null, null, headers)

            fun stream(
                status: String,
                contentType: String,
                body: () -> java.io.InputStream,
                headers: Map<String, String>,
            ) = PlatformContent(status, contentType, null, null, body, headers)
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
    internal const val MAX_REQUEST_BODY = 256 * 1024
    private const val MAX_CONTACT_PHOTO = 256 * 1024

    private class InvalidRequest(val status: Int, val code: String, message: String) :
        Exception(message)
}
