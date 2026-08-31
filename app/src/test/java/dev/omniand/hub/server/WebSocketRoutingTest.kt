package dev.omniand.hub.server

import android.content.Context
import dev.omniand.hub.server.KtorServer.WebSocketAccess
import dev.omniand.hub.webapps.WebApp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** Covers the diagnostic socket's origin gate and observable bidirectional session behavior. */
class WebSocketRoutingTest {
    private val testApp = WebApp("test", "OmniAnd Test", "0.2.0", emptySet())
    private val otherApp = WebApp("messages", "Messages", "1.0.0", setOf("sms.read"))

    @Test
    fun authorizesOnlyTheExactTestOrigin() {
        val local =
            PlatformRequestContext(
                "test.localhost:8080",
                "test.localhost",
                PlatformRequestContext.Transport.LOOPBACK_HTTP,
                true,
                testApp,
            )
        val desktop =
            PlatformRequestContext(
                "test.example.test",
                "test.example.test",
                PlatformRequestContext.Transport.DESKTOP_HTTP,
                false,
                testApp,
            )
        val platform = desktop.copy(app = null, hostname = "example.test")

        assertEquals(
            WebSocketAccess.UNAUTHORIZED,
            KtorServer.authorizeTestWebSocket(null, LOCAL_ORIGIN),
        )
        assertEquals(
            WebSocketAccess.ALLOWED,
            KtorServer.authorizeTestWebSocket(local, "http://test.localhost:8080"),
        )
        assertEquals(
            WebSocketAccess.ALLOWED,
            KtorServer.authorizeTestWebSocket(desktop, "https://test.example.test"),
        )
        assertEquals(
            WebSocketAccess.FORBIDDEN,
            KtorServer.authorizeTestWebSocket(local, "http://messages.localhost:8080"),
        )
        assertEquals(
            WebSocketAccess.FORBIDDEN,
            KtorServer.authorizeTestWebSocket(local.copy(app = otherApp), LOCAL_ORIGIN),
        )
        assertEquals(
            WebSocketAccess.FORBIDDEN,
            KtorServer.authorizeTestWebSocket(platform, "https://example.test"),
        )
    }

    @Test
    fun `camera signaling is desktop only exact origin and capability gated`() {
        val camera = WebApp("camera", "Camera", "0.2.0", setOf("camera.stream"))
        val desktop =
            PlatformRequestContext(
                "camera-link.example.test",
                "camera-link.example.test",
                PlatformRequestContext.Transport.DESKTOP_HTTP,
                false,
                camera,
            )
        assertEquals(
            WebSocketAccess.ALLOWED,
            KtorServer.authorizeCameraWebSocket(
                desktop,
                "https://camera-link.example.test",
                true,
            ),
        )
        assertEquals(
            WebSocketAccess.FORBIDDEN,
            KtorServer.authorizeCameraWebSocket(desktop, "https://wrong.example.test", true),
        )
        assertEquals(
            WebSocketAccess.FORBIDDEN,
            KtorServer.authorizeCameraWebSocket(desktop, "https://camera-link.example.test", false),
        )
        assertEquals(
            WebSocketAccess.FORBIDDEN,
            KtorServer.authorizeCameraWebSocket(
                desktop.copy(transport = PlatformRequestContext.Transport.LOOPBACK_HTTP),
                "https://camera-link.example.test",
                true,
            ),
        )
        assertEquals(
            WebSocketAccess.UNAUTHORIZED,
            KtorServer.authorizeCameraWebSocket(null, null, false),
        )
    }

    @Test
    fun rejectsTheUpgradeBeforeOpeningAWebSocket() = testApplication {
        application {
            KtorServer.run {
                platformModule(
                    mock(Context::class.java),
                    websocketAccess = { WebSocketAccess.UNAUTHORIZED },
                )
            }
        }

        val websocketClient = createClient { install(WebSockets) }
        val failure =
            runCatching { websocketClient.webSocket("/api/test/websocket") {} }.exceptionOrNull()
        assertTrue(failure.toString(), failure != null)
    }

    @Test
    fun upgradesSendsReadyAndTicksAndEchoesTheExactProbe() = testApplication {
        application {
            KtorServer.run {
                platformModule(
                    mock(Context::class.java),
                    websocketAccess = { WebSocketAccess.ALLOWED },
                    websocketTickPeriod = 20.milliseconds,
                )
            }
        }
        val websocketClient = createClient { install(WebSockets) }

        websocketClient.webSocket("/api/test/websocket") {
            val ready =
                JSONObject((withTimeout(1_000) { incoming.receive() } as Frame.Text).readText())
            assertEquals("ready", ready.getString("type"))

            val probe = "{\"type\":\"probe\",\"id\":\"probe-1\"}"
            send(Frame.Text(probe))
            var sawTick = false
            var echo: String? = null
            withTimeout(1_000) {
                while (!sawTick || echo == null) {
                    val text = (incoming.receive() as Frame.Text).readText()
                    val event = JSONObject(text)
                    if (event.optString("type") == "tick") sawTick = true
                    if (text == probe) echo = text
                }
            }
            assertEquals(probe, echo)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun closesMalformedAndUnsupportedMessagesCleanly() = testApplication {
        application {
            KtorServer.run {
                platformModule(
                    mock(Context::class.java),
                    websocketAccess = { WebSocketAccess.ALLOWED },
                )
            }
        }
        val websocketClient = createClient { install(WebSockets) }

        websocketClient.webSocket("/api/test/websocket") {
            incoming.receive()
            send(Frame.Text("not-json"))
            assertEquals(
                CloseReason.Codes.NOT_CONSISTENT,
                withTimeout(1_000) { closeReason.await() }?.knownReason,
            )
        }
        websocketClient.webSocket("/api/test/websocket") {
            incoming.receive()
            send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
            assertEquals(
                CloseReason.Codes.CANNOT_ACCEPT,
                withTimeout(1_000) { closeReason.await() }?.knownReason,
            )
        }
    }

    private companion object {
        const val LOCAL_ORIGIN = "http://test.localhost:8080"
    }
}
