package dev.omniand.hub.server

import android.content.Context
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

/** Verifies explicit route-family registration without consuming unauthorized request bodies. */
class KtorRoutingTest {
    @Test
    fun registeredFamiliesAuthenticateAndUnknownApiDoesNotReachAssets() = testApplication {
        application { KtorServer.run { platformModule(mock(Context::class.java)) } }

        listOf(
                "/api/media/setup",
                "/api/contacts/accounts",
                "/api/sms/threads",
                "/api/contacts/a%2Fb",
                "/api/media/a%2Fb/content",
                "/api/sms/messages/a%2Fb",
                "/api/apps/web",
                "/api/apps/catalog",
                "/api/apps/catalog/messages/icon",
            )
            .forEach { path ->
                val response = client.get(path) { header(HttpHeaders.Host, "localhost:8080") }
                assertEquals(path, HttpStatusCode.Unauthorized, response.status)
            }

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/unknown") { header(HttpHeaders.Host, "localhost:8080") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/store/config") { header(HttpHeaders.Host, "localhost:8080") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client
                .post("/api/apps/install/https%3A%2F%2Fevil.example%2Fapp.zip") {
                    header(HttpHeaders.Host, "localhost:8080")
                }
                .status,
        )
    }

    @Test
    fun uploadAuthorizationPrecedesMultipartParsingAndLegacyChildrenAreRemoved() = testApplication {
        application { KtorServer.run { platformModule(mock(Context::class.java)) } }

        val unauthorized =
            client.post("/api/media/uploads") {
                header(HttpHeaders.Host, "localhost:8080")
                header(HttpHeaders.ContentType, "not/a-multipart")
                setBody("malformed")
            }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client
                .post("/api/media/uploads/legacy/complete") {
                    header(HttpHeaders.Host, "localhost:8080")
                }
                .status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client
                .post("/api/sms/uploads/legacy/abort") {
                    header(HttpHeaders.Host, "localhost:8080")
                }
                .status,
        )
    }
}
