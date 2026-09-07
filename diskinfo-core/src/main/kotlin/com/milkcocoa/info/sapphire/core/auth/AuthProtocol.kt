package com.milkcocoa.info.sapphire.core.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Wire-level constants shared by signers and verifiers. */
object CocoaAuthProtocol {
    /** Version embedded in every signed request payload. */
    const val VERSION = 1
    /** HTTP header carrying the compact signed request. */
    const val AUTHORIZATION_HEADER = "Authorization"
    /** Case-insensitive authorization scheme accepted by the protocol. */
    const val AUTHORIZATION_SCHEME = "CocoaDiskInfo-JWS"
    /** Response header carrying a nonce for a subsequent proof. */
    const val NEXT_NONCE_HEADER = "CocoaDiskInfo-Next-Nonce"
    /** Header containing the RFC 9530-style body digest binding. */
    const val CONTENT_DIGEST_HEADER = "Content-Digest"
    /** Required protected JWS media type. */
    const val JWS_TYPE = "cocoadiskinfo-proof+jws"
    /** Required signature algorithm. */
    const val JWS_ALGORITHM = "EdDSA"
    /** Stable error code for nonce issuance failures. */
    const val NONCE_UNAVAILABLE_ERROR_CODE = "nonce_unavailable"

    /** Endpoint used to issue a purpose-bound nonce. */
    const val NONCE_PATH = "/api/v1/auth/nonces"
    /** Endpoint used by a node agent to complete joining. */
    const val NODE_JOIN_PATH = "/api/v1/node-agents/join"
    /** Endpoint used by a desktop client to complete pairing. */
    const val CLIENT_PAIR_PATH = "/api/v1/clients/pair"
    /** Endpoint used to ingest a disk snapshot. */
    const val SNAPSHOT_INGEST_PATH = "/api/v1/snapshots"
    /** Endpoint used by a node agent to send a heartbeat. */
    const val HEARTBEAT_PATH = "/api/v1/node-agents/heartbeat"
}

/** Stable machine-readable authentication error codes. */
object CocoaAuthErrorCodes {
    /** Indicates that a request nonce is missing, invalid, expired, or already consumed. */
    const val NONCE_UNAVAILABLE = "nonce_unavailable"
}

/** Formats and strictly parses the protocol's single-value Authorization header. */
object CocoaAuthorization {
    /** Prefixes a non-blank, whitespace-free compact JWS with the protocol authorization scheme. */
    fun format(compactJws: String): String {
        require(compactJws.isNotBlank() && compactJws.none(Char::isWhitespace)) {
            "Compact JWS must not be blank or contain whitespace."
        }
        return "${CocoaAuthProtocol.AUTHORIZATION_SCHEME} $compactJws"
    }

    /**
     * Extracts the compact JWS from exactly one header value.
     *
     * @throws AuthProtocolException when the header count, scheme, separator, or credential
     * violates the protocol grammar.
     */
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
/** Operation for which a nonce and signed request proof are scoped. */
enum class AuthPurpose {
    /** Node-agent join proof scope. */
    JOIN,
    /** Desktop client pairing proof scope. */
    CLIENT_PAIR,
    /** Node-agent snapshot-ingest proof scope. */
    SNAPSHOT_INGEST,
    /** Node-agent heartbeat proof scope. */
    HEARTBEAT,
    /** Authenticated desktop read proof scope. */
    CLIENT_READ,
}

@Serializable
/** Credential class to which an issued nonce is bound. */
enum class NonceSubjectType {
    /** Nonce bound to a one-time node join token. */
    JOIN_TOKEN,
    /** Nonce bound to a one-time client pairing token. */
    PAIRING_TOKEN,
    /** Nonce bound to an already registered principal credential. */
    PRINCIPAL,
}

@Serializable
/** Request to issue a purpose-bound, single-use nonce. */
data class NonceIssueRequest(
    /** Subject credential class. */
    val subjectType: NonceSubjectType,
    /** Stable identifier of the credential or principal. */
    val subjectId: String,
    /** Endpoint operation that may consume the nonce. */
    val purpose: AuthPurpose,
)

@Serializable
/** Server-issued nonce and its absolute expiry time. */
data class NonceIssueResponse(
    /** Canonical unpadded base64url nonce used in the signed payload. */
    val nonce: String,
    /** Instant after which the nonce must no longer be accepted. */
    val expiresAt: Instant,
)

@Serializable
/** JSON Web Key representation of an Ed25519 public key. */
data class Ed25519PublicJwk(
    /** JWK key type; protocol requires `OKP`. */
    val kty: String = "OKP",
    /** Curve name; protocol requires `Ed25519`. */
    val crv: String = "Ed25519",
    /** 32-byte raw public point encoded as unpadded base64url. */
    val x: String,
)

@Serializable
/** Protected JWS header authenticated along with a signed request payload. */
data class JwsProtectedHeader(
    /** JWS media type expected by the protocol. */
    val typ: String,
    /** Signature algorithm expected by the protocol. */
    val alg: String,
    /** RFC 7638 thumbprint identifying the verification key. */
    val kid: String,
)

@Serializable
/** Canonical request bindings carried inside a compact JWS. */
data class SignedRequestPayload(
    /** Protocol revision used to interpret the remaining fields. */
    val version: Int = CocoaAuthProtocol.VERSION,
    /** Server-issued nonce proving freshness and subject binding. */
    val nonce: String,
    /** Uppercase canonical HTTP method. */
    val method: String,
    /** Percent-encoded absolute path without query or fragment. */
    val path: String,
    /** Canonical sorted query string without a leading `?`. */
    val query: String,
    /** Operation scope preventing a proof from crossing endpoint purposes. */
    val purpose: AuthPurpose,
    /** Unpadded base64url SHA-256 digest of the request body, when a body exists. */
    val bodySha256: String? = null,
)

/** Binds a nonce to one credential subject and one operation purpose. */
data class NonceBinding(
    /** Credential class for which the nonce is valid. */
    val subjectType: NonceSubjectType,
    /** Non-empty credential/principal identifier. */
    val subjectId: String,
    /** Operation scope for which the nonce may be consumed. */
    val purpose: AuthPurpose,
) {
    init {
        require(subjectId.isNotBlank()) { "Nonce subject id must not be blank." }
    }
}
