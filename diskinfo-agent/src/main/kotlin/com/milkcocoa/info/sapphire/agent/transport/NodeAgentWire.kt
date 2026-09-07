package com.milkcocoa.info.sapphire.agent.transport

import com.milkcocoa.info.sapphire.core.api.ApiError
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal val NodeAgentJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}

@Serializable
private data class FailureResponse(
    val error: ApiError,
)

/** Converts a non-success response into a structured transport failure with API error details. */
internal suspend fun HttpResponse.toFailureException(
    operation: String,
    retryable: Boolean = status.value >= 500,
): NodeAgentTransportException {
    val body = bodyAsText()
    val apiError = runCatching { NodeAgentJson.decodeFromString<FailureResponse>(body).error }.getOrNull()
    return NodeAgentTransportException(
        message = "$operation failed: ${status.value} ${apiError?.message ?: body.ifBlank { status.description }}",
        statusCode = status.value,
        errorCode = apiError?.code,
        retryable = retryable,
    )
}

/** Decodes strict wire JSON and translates malformed payloads into transport failures. */
internal inline fun <reified T> decodeRawResponse(
    body: String,
    operation: String,
): T = try {
    NodeAgentJson.decodeFromString(body)
} catch (error: SerializationException) {
    throw NodeAgentTransportException("$operation returned invalid JSON.", cause = error)
} catch (error: IllegalArgumentException) {
    throw NodeAgentTransportException("$operation returned invalid UTF-8 JSON.", cause = error)
}
