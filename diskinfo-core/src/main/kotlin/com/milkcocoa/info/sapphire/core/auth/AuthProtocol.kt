package com.milkcocoa.info.sapphire.core.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant

object CocoaAuthProtocol {
    const val VERSION = 1
    const val AUTHORIZATION_HEADER = "Authorization"
    const val AUTHORIZATION_SCHEME = "CocoaDiskInfo-JWS"
    const val NEXT_NONCE_HEADER = "CocoaDiskInfo-Next-Nonce"
    const val CONTENT_DIGEST_HEADER = "Content-Digest"
    const val JWS_TYPE = "cocoadiskinfo-proof+jws"
    const val JWS_ALGORITHM = "EdDSA"
    const val NONCE_UNAVAILABLE_ERROR_CODE = "nonce_unavailable"

    const val NONCE_PATH = "/api/v1/auth/nonces"
    const val NODE_JOIN_PATH = "/api/v1/node-agents/join"
    const val CLIENT_PAIR_PATH = "/api/v1/clients/pair"
    const val SNAPSHOT_INGEST_PATH = "/api/v1/snapshots"
    const val HEARTBEAT_PATH = "/api/v1/node-agents/heartbeat"
}

object CocoaAuthErrorCodes {
    const val NONCE_UNAVAILABLE = "nonce_unavailable"
}

object CocoaAuthorization {
    fun format(compactJws: String): String {
        require(compactJws.isNotBlank() && compactJws.none(Char::isWhitespace)) {
            "Compact JWS must not be blank or contain whitespace."
        }
        return "${CocoaAuthProtocol.AUTHORIZATION_SCHEME} $compactJws"
    }

    fun parse(headerValues: List<String>): String {
        if (headerValues.size != 1) {
            throw AuthProtocolException("Exactly one Authorization header is required.")
        }
        val value = headerValues.single()
        val separator = value.indexOf(' ')
        if (separator <= 0 || value.indexOf(' ', separator + 1) >= 0) {
            throw AuthProtocolException("Authorization header must contain one auth scheme and credential.")
        }
        val scheme = value.substring(0, separator)
        val compactJws = value.substring(separator + 1)
        if (!scheme.equals(CocoaAuthProtocol.AUTHORIZATION_SCHEME, ignoreCase = true) ||
            compactJws.isBlank() || compactJws.any(Char::isWhitespace)
        ) {
            throw AuthProtocolException("Authorization header does not use the required signed-request scheme.")
        }
        return compactJws
    }
}

@Serializable
enum class AuthPurpose {
    JOIN,
    CLIENT_PAIR,
    SNAPSHOT_INGEST,
    HEARTBEAT,
    CLIENT_READ,
}

@Serializable
enum class NonceSubjectType {
    JOIN_TOKEN,
    PAIRING_TOKEN,
    PRINCIPAL,
}

@Serializable
data class NonceIssueRequest(
    val subjectType: NonceSubjectType,
    val subjectId: String,
    val purpose: AuthPurpose,
)

@Serializable
data class NonceIssueResponse(
    val nonce: String,
    val expiresAt: Instant,
)

@Serializable
data class Ed25519PublicJwk(
    val kty: String = "OKP",
    val crv: String = "Ed25519",
    val x: String,
)

@Serializable
data class JwsProtectedHeader(
    val typ: String,
    val alg: String,
    val kid: String,
)

@Serializable
data class SignedRequestPayload(
    val version: Int = CocoaAuthProtocol.VERSION,
    val nonce: String,
    val method: String,
    val path: String,
    val query: String,
    val purpose: AuthPurpose,
    val bodySha256: String? = null,
)

data class NonceBinding(
    val subjectType: NonceSubjectType,
    val subjectId: String,
    val purpose: AuthPurpose,
) {
    init {
        require(subjectId.isNotBlank()) { "Nonce subject id must not be blank." }
    }
}
