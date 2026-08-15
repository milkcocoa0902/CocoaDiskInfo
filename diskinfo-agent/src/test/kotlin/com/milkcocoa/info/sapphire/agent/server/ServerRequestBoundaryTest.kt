package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.core.api.ApiError
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ServerRequestBoundaryTest {
    @Test
    fun `raw request reader rejects every Content-Encoding`() = testApplication {
        installBoundaryRoutes(maximumBytes = 32)

        val response = client.post("/bounded") {
            header(HttpHeaders.ContentEncoding, "identity")
            setBody("{}")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals("content_encoding_not_supported", response.failureCode())
    }

    @Test
    fun `raw request reader enforces actual bytes without Content-Length`() = testApplication {
        installBoundaryRoutes(maximumBytes = 4)

        val response = client.post("/bounded") {
            setBody(UnknownLengthBody("12345".encodeToByteArray()))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("request_body_too_large", response.failureCode())
    }

    @Test
    fun `unexpected application failure returns a stable non-sensitive 5xx`() = testApplication {
        installBoundaryRoutes(maximumBytes = 32)

        val response = client.get("/failure")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val failure = BoundaryJson.decodeFromString<BoundaryFailureResponse>(response.bodyAsText())
        assertEquals("internal_error", failure.error.code)
        assertEquals("Internal server error.", failure.error.message)
    }

    @Test
    fun `request cancellation is not converted into an internal error response`() = testApplication {
        installBoundaryRoutes(maximumBytes = 32)

        val response = client.get("/cancellation")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertFalse(response.bodyAsText().contains("internal_error"))
    }
}

private fun io.ktor.server.testing.ApplicationTestBuilder.installBoundaryRoutes(maximumBytes: Long) {
    application {
        install(ContentNegotiation) { json(BoundaryJson) }
        routing {
            post("/bounded") {
                call.handleApiErrors {
                    call.respondText(call.receiveBodyBytes(maximumBytes).size.toString())
                }
            }
            get("/failure") {
                call.handleApiErrors {
                    throw IllegalStateException("database-password-must-not-leak")
                }
            }
            get("/cancellation") {
                call.handleApiErrors {
                    throw CancellationException("request stopped")
                }
            }
        }
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.failureCode(): String =
    BoundaryJson.decodeFromString<BoundaryFailureResponse>(bodyAsText()).error.code

private class UnknownLengthBody(
    private val bytes: ByteArray,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.OctetStream

    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(bytes)
    }
}

@Serializable
private data class BoundaryFailureResponse(
    val error: ApiError,
)

private val BoundaryJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
