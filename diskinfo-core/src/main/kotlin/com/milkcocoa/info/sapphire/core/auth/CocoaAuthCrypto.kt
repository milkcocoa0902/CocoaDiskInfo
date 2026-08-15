package com.milkcocoa.info.sapphire.core.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val AuthJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}

class AuthProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class VerifiedSignedRequest(
    val header: JwsProtectedHeader,
    val payload: SignedRequestPayload,
)

object Base64Url {
    private val canonicalPattern = Regex("[A-Za-z0-9_-]+")

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String, fieldName: String = "base64url value"): ByteArray {
        if (!canonicalPattern.matches(value)) {
            throw AuthProtocolException("$fieldName must be canonical base64url without padding.")
        }
        return try {
            Base64.getUrlDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw AuthProtocolException("$fieldName must be valid base64url.", error)
        }.also { decoded ->
            if (encode(decoded) != value) {
                throw AuthProtocolException("$fieldName must be canonical base64url without padding.")
            }
        }
    }

    fun decodeExact(value: String, expectedSize: Int, fieldName: String): ByteArray =
        decode(value, fieldName).also { decoded ->
            if (decoded.size != expectedSize) {
                throw AuthProtocolException("$fieldName must decode to $expectedSize bytes.")
            }
        }
}

object Ed25519Keys {
    private const val RAW_PUBLIC_KEY_SIZE = 32

    fun generate(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun publicJwk(publicKey: PublicKey): Ed25519PublicJwk {
        val key = publicKey as? EdECPublicKey
            ?: throw AuthProtocolException("Public key must be Ed25519.")
        require(key.params.name == NamedParameterSpec.ED25519.name) { "Public key must use Ed25519." }

        val raw = ByteArray(RAW_PUBLIC_KEY_SIZE)
        val y = key.point.y
        require(y.signum() >= 0 && y.bitLength() <= 255) { "Invalid Ed25519 public point." }
        raw.indices.forEach { index ->
            raw[index] = y.shiftRight(index * Byte.SIZE_BITS).and(BigInteger.valueOf(0xff)).toByte()
        }
        if (key.point.isXOdd) {
            raw[raw.lastIndex] = (raw.last().toInt() or 0x80).toByte()
        }
        return Ed25519PublicJwk(x = Base64Url.encode(raw))
    }

    fun publicKey(jwk: Ed25519PublicJwk): PublicKey {
        if (jwk.kty != "OKP" || jwk.crv != "Ed25519") {
            throw AuthProtocolException("JWK must be an OKP Ed25519 public key.")
        }
        val encodedPoint = Base64Url.decodeExact(jwk.x, RAW_PUBLIC_KEY_SIZE, "JWK x")
        val xOdd = encodedPoint.last().toInt() and 0x80 != 0
        encodedPoint[encodedPoint.lastIndex] = (encodedPoint.last().toInt() and 0x7f).toByte()
        val y = BigInteger(1, encodedPoint.reversedArray())
        val spec = EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y))
        return try {
            KeyFactory.getInstance("Ed25519").generatePublic(spec)
        } catch (error: Exception) {
            throw AuthProtocolException("JWK contains an invalid Ed25519 public point.", error)
        }
    }

    fun privateKeyFromPkcs8(encoded: ByteArray): PrivateKey = try {
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded))
    } catch (error: Exception) {
        throw AuthProtocolException("Credential contains an invalid Ed25519 private key.", error)
    }

    fun kid(jwk: Ed25519PublicJwk): String {
        if (jwk.kty != "OKP" || jwk.crv != "Ed25519") {
            throw AuthProtocolException("JWK must be an OKP Ed25519 public key.")
        }
        Base64Url.decodeExact(jwk.x, RAW_PUBLIC_KEY_SIZE, "JWK x")

        // RFC 7638 requires this lexicographic member order and excludes all
        // optional JWK metadata from the thumbprint input.
        val thumbprintInput = "{\"crv\":\"Ed25519\",\"kty\":\"OKP\",\"x\":\"${jwk.x}\"}"
        return Base64Url.encode(sha256(thumbprintInput.toByteArray(StandardCharsets.UTF_8)))
    }

    fun kid(publicKey: PublicKey): String = kid(publicJwk(publicKey))
}

object SignedRequestJws {
    fun sign(
        payload: SignedRequestPayload,
        privateKey: PrivateKey,
        kid: String,
    ): String {
        validatePayload(payload)
        Base64Url.decodeExact(kid, expectedSize = 32, fieldName = "kid")
        val header = JwsProtectedHeader(
            typ = CocoaAuthProtocol.JWS_TYPE,
            alg = CocoaAuthProtocol.JWS_ALGORITHM,
            kid = kid,
        )
        val encodedHeader = Base64Url.encode(AuthJson.encodeToString(header).toByteArray(StandardCharsets.UTF_8))
        val encodedPayload = Base64Url.encode(AuthJson.encodeToString(payload).toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload".toByteArray(StandardCharsets.US_ASCII)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(signingInput)
            sign()
        }
        return "$encodedHeader.$encodedPayload.${Base64Url.encode(signature)}"
    }

    fun readProtectedHeader(compactJws: String): JwsProtectedHeader {
        val parts = compactParts(compactJws)
        return decodeJson<JwsProtectedHeader>(
            Base64Url.decode(parts[0], "JWS protected header"),
            "JWS protected header",
        )
            .also(::validateHeader)
    }

    fun verify(compactJws: String, publicKey: PublicKey): VerifiedSignedRequest {
        val parts = compactParts(compactJws)
        val header = decodeJson<JwsProtectedHeader>(
            Base64Url.decode(parts[0], "JWS protected header"),
            "JWS protected header",
        ).also(::validateHeader)
        val expectedKid = Ed25519Keys.kid(publicKey)
        if (header.kid != expectedKid) {
            throw AuthProtocolException("JWS kid does not match the verification key.")
        }

        val signatureBytes = Base64Url.decodeExact(parts[2], expectedSize = 64, fieldName = "JWS signature")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII)
        val valid = try {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(signingInput)
                verify(signatureBytes)
            }
        } catch (error: Exception) {
            throw AuthProtocolException("JWS signature could not be verified.", error)
        }
        if (!valid) throw AuthProtocolException("JWS signature is invalid.")

        // The payload is parsed only after signature verification, so callers never
        // accidentally treat attacker-controlled request bindings as authenticated.
        val payload = decodeJson<SignedRequestPayload>(
            Base64Url.decode(parts[1], "JWS payload"),
            "JWS payload",
        ).also(::validatePayload)
        return VerifiedSignedRequest(header, payload)
    }

    fun verify(
        compactJws: String,
        publicKey: PublicKey,
        expectedPayload: SignedRequestPayload,
    ): VerifiedSignedRequest = verify(compactJws, publicKey).also { verified ->
        validatePayload(expectedPayload)
        if (verified.payload != expectedPayload) {
            throw AuthProtocolException("JWS request binding does not match the request.")
        }
    }

    fun verify(
        compactJws: String,
        publicKey: PublicKey,
        expectedRequest: CanonicalRequest,
    ): VerifiedSignedRequest {
        val verified = verify(compactJws, publicKey)
        // The nonce is proof data issued by the server, while every other field is
        // reconstructed from the effective HTTP request. Reusing only the verified
        // nonce prevents route adapters from trusting signed-but-wrong path/query data.
        val expectedPayload = expectedRequest.signedPayload(verified.payload.nonce)
        if (verified.payload != expectedPayload) {
            throw AuthProtocolException("JWS request binding does not match the request.")
        }
        return verified
    }

    private fun compactParts(compactJws: String): List<String> {
        val parts = compactJws.split('.', limit = 4)
        if (parts.size != 3 || parts.any(String::isBlank)) {
            throw AuthProtocolException("JWS must use the three-part compact serialization.")
        }
        return parts
    }

    private fun validateHeader(header: JwsProtectedHeader) {
        if (header.typ != CocoaAuthProtocol.JWS_TYPE || header.alg != CocoaAuthProtocol.JWS_ALGORITHM) {
            throw AuthProtocolException("JWS protected header is not supported.")
        }
        Base64Url.decodeExact(header.kid, expectedSize = 32, fieldName = "kid")
    }

    private fun validatePayload(payload: SignedRequestPayload) {
        if (payload.version != CocoaAuthProtocol.VERSION) {
            throw AuthProtocolException("Signed request version is not supported.")
        }
        Base64Url.decodeExact(payload.nonce, expectedSize = 32, fieldName = "nonce")
        CanonicalRequest(
            method = payload.method,
            path = payload.path,
            query = payload.query,
            purpose = payload.purpose,
            bodySha256 = payload.bodySha256,
        )
    }

    private inline fun <reified T> decodeJson(bytes: ByteArray, fieldName: String): T {
        return try {
            AuthJson.decodeFromString(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (error: SerializationException) {
            throw AuthProtocolException("$fieldName must match the required JSON shape.", error)
        } catch (error: IllegalArgumentException) {
            throw AuthProtocolException("$fieldName must be valid UTF-8 JSON.", error)
        }
    }
}

object SnapshotBodyDigest {
    private val contentDigestPattern = Regex("sha-256=:([A-Za-z0-9+/]+={0,2}):")

    fun sha256(bytes: ByteArray): ByteArray = com.milkcocoa.info.sapphire.core.auth.sha256(bytes)

    fun bodySha256(bytes: ByteArray): String = Base64Url.encode(sha256(bytes))

    fun contentDigest(bytes: ByteArray): String =
        "sha-256=:${Base64.getEncoder().encodeToString(sha256(bytes))}:"

    fun parseContentDigest(value: String): ByteArray {
        val encoded = contentDigestPattern.matchEntire(value)?.groupValues?.get(1)
            ?: throw AuthProtocolException("Content-Digest must contain exactly one sha-256 value.")
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw AuthProtocolException("Content-Digest contains invalid base64.", error)
        }
        if (decoded.size != 32 || Base64.getEncoder().encodeToString(decoded) != encoded) {
            throw AuthProtocolException("Content-Digest sha-256 value must be canonical 32-byte base64.")
        }
        return decoded
    }

    fun verify(
        bodyBytes: ByteArray,
        contentDigest: String,
        signedBodySha256: String,
    ): Boolean {
        val actual = sha256(bodyBytes)
        val fromHeader = parseContentDigest(contentDigest)
        val fromJws = Base64Url.decodeExact(signedBodySha256, 32, "bodySha256")
        return MessageDigest.isEqual(actual, fromHeader) && MessageDigest.isEqual(actual, fromJws)
    }
}

object JoinProof {
    private const val DOMAIN = "cocoadiskinfo.join.v1"

    fun deriveJoinKey(tokenSecret: ByteArray): ByteArray {
        require(tokenSecret.isNotEmpty()) { "Join token secret must not be empty." }
        return sha256(tokenSecret)
    }

    fun canonicalInput(
        hubId: String,
        joinTokenId: String,
        nonce: String,
        kid: String,
        nodeName: String,
    ): ByteArray {
        val fields = listOf(
            DOMAIN.toByteArray(StandardCharsets.UTF_8),
            hubId.nonEmptyUtf8("hubId"),
            joinTokenId.nonEmptyUtf8("joinTokenId"),
            Base64Url.decodeExact(nonce, 32, "nonce"),
            Base64Url.decodeExact(kid, 32, "kid"),
            nodeName.nonEmptyUtf8("nodeName"),
        )
        val output = ByteArrayOutputStream()
        fields.forEach { field ->
            // An unsigned 32-bit big-endian length removes every concatenation
            // ambiguity, including boundaries involving multibyte UTF-8 text.
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(field.size).array())
            output.write(field)
        }
        return output.toByteArray()
    }

    fun hmac(joinKey: ByteArray, canonicalInput: ByteArray): ByteArray {
        require(joinKey.size == 32) { "Join key must be a SHA-256 digest." }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(joinKey, "HmacSHA256"))
            doFinal(canonicalInput)
        }
    }

    fun verify(joinKey: ByteArray, canonicalInput: ByteArray, proof: ByteArray): Boolean =
        MessageDigest.isEqual(hmac(joinKey, canonicalInput), proof)

    private fun String.nonEmptyUtf8(fieldName: String): ByteArray {
        require(isNotEmpty()) { "$fieldName must not be empty." }
        return toByteArray(StandardCharsets.UTF_8)
    }
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
