package com.milkcocoa.info.sapphire.core.auth

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CocoaAuthCryptoTest {
    @Test
    fun `RFC 8037 Ed25519 JWK produces the published thumbprint`() {
        val jwk = Ed25519PublicJwk(
            x = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo",
        )

        assertEquals(
            "kPrK_qmxVWaYVA9wwBF6Iuo3vVzz7TxHCTwXBygrS4k",
            Ed25519Keys.kid(jwk),
        )
        assertEquals(jwk, Ed25519Keys.publicJwk(Ed25519Keys.publicKey(jwk)))
    }

    @Test
    fun `Ed25519 JWK round trip preserves key and RFC 7638 kid`() {
        val keyPair = Ed25519Keys.generate()
        val jwk = Ed25519Keys.publicJwk(keyPair.public)
        val restored = Ed25519Keys.publicKey(jwk)
        val expectedThumbprintInput =
            "{\"crv\":\"Ed25519\",\"kty\":\"OKP\",\"x\":\"${jwk.x}\"}"
        val expectedKid = Base64Url.encode(
            MessageDigest.getInstance("SHA-256").digest(expectedThumbprintInput.toByteArray()),
        )

        assertEquals("OKP", jwk.kty)
        assertEquals("Ed25519", jwk.crv)
        assertEquals(expectedKid, Ed25519Keys.kid(jwk))
        assertEquals(expectedKid, Ed25519Keys.kid(restored))

        val payload = requestPayload(body = "round-trip".encodeToByteArray())
        val token = SignedRequestJws.sign(payload, keyPair.private, expectedKid)
        assertEquals(payload, SignedRequestJws.verify(token, restored).payload)
    }

    @Test
    fun `compact JWS rejects substitution algorithm confusion and wrong request binding`() {
        val keyPair = Ed25519Keys.generate()
        val otherKeyPair = Ed25519Keys.generate()
        val kid = Ed25519Keys.kid(keyPair.public)
        val payload = requestPayload(body = "original".encodeToByteArray())
        val token = SignedRequestJws.sign(payload, keyPair.private, kid)

        assertFailsWith<AuthProtocolException> {
            SignedRequestJws.verify(token, otherKeyPair.public)
        }

        val parts = token.split('.')
        val noneHeader = Base64Url.encode(
            """{"typ":"${CocoaAuthProtocol.JWS_TYPE}","alg":"none","kid":"$kid"}""".encodeToByteArray(),
        )
        assertFailsWith<AuthProtocolException> {
            SignedRequestJws.verify("$noneHeader.${parts[1]}.${parts[2]}", keyPair.public)
        }

        assertFailsWith<AuthProtocolException> {
            SignedRequestJws.verify(
                compactJws = token,
                publicKey = keyPair.public,
                expectedPayload = payload.copy(path = CocoaAuthProtocol.HEARTBEAT_PATH),
            )
        }
        assertFailsWith<AuthProtocolException> {
            SignedRequestJws.verify(
                compactJws = token,
                publicKey = keyPair.public,
                expectedPayload = payload.copy(purpose = AuthPurpose.HEARTBEAT),
            )
        }
        assertEquals(
            payload,
            SignedRequestJws.verify(
                compactJws = token,
                publicKey = keyPair.public,
                expectedRequest = CanonicalRequest(
                    method = payload.method,
                    path = payload.path,
                    query = payload.query,
                    purpose = payload.purpose,
                    bodySha256 = payload.bodySha256,
                ),
            ).payload,
        )
    }

    @Test
    fun `private PKCS8 credential restores a signing key`() {
        val keyPair = Ed25519Keys.generate()
        val restoredPrivate = Ed25519Keys.privateKeyFromPkcs8(keyPair.private.encoded)
        val kid = Ed25519Keys.kid(keyPair.public)
        val payload = requestPayload(body = ByteArray(0))

        val token = SignedRequestJws.sign(payload, restoredPrivate, kid)

        assertEquals(payload, SignedRequestJws.verify(token, keyPair.public).payload)
    }

    @Test
    fun `Content-Digest binds the exact body bytes`() {
        val body = "{ \"value\": 1 }".encodeToByteArray()
        val contentDigest = SnapshotBodyDigest.contentDigest(body)
        val bodySha256 = SnapshotBodyDigest.bodySha256(body)

        assertTrue(SnapshotBodyDigest.verify(body, contentDigest, bodySha256))
        assertFalse(
            SnapshotBodyDigest.verify(
                "{\"value\":1}".encodeToByteArray(),
                contentDigest,
                bodySha256,
            ),
        )
        assertFailsWith<AuthProtocolException> {
            SnapshotBodyDigest.parseContentDigest(contentDigest.replace("sha-256=", "SHA-256="))
        }
        assertFailsWith<AuthProtocolException> {
            SnapshotBodyDigest.parseContentDigest("sha-512=:YWJjZA==:")
        }
    }

    @Test
    fun `join proof uses derived key and unambiguous typed field boundaries`() {
        val tokenSecret = ByteArray(32) { it.toByte() }
        val joinKey = JoinProof.deriveJoinKey(tokenSecret)
        val nonce = Base64Url.encode(ByteArray(32) { (it + 1).toByte() })
        val kid = Base64Url.encode(ByteArray(32) { (it + 2).toByte() })
        val input = JoinProof.canonicalInput(
            hubId = "hub-a",
            joinTokenId = "token-a",
            nonce = nonce,
            kid = kid,
            nodeName = "node-a",
        )
        val proof = JoinProof.hmac(joinKey, input)

        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(tokenSecret), joinKey)
        assertTrue(JoinProof.verify(joinKey, input, proof))
        assertFalse(
            JoinProof.verify(
                joinKey,
                JoinProof.canonicalInput("hub-a", "token-a", nonce, kid, "node-b"),
                proof,
            ),
        )

        val firstLength = ByteBuffer.wrap(input, 0, Int.SIZE_BYTES).int
        assertEquals("cocoadiskinfo.join.v1".encodeToByteArray().size, firstLength)
        assertEquals(
            "cocoadiskinfo.join.v1",
            input.copyOfRange(Int.SIZE_BYTES, Int.SIZE_BYTES + firstLength).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `non canonical base64url is rejected`() {
        val bytes = ByteArray(32) { it.toByte() }
        val canonical = Base64Url.encode(bytes)

        assertContentEquals(bytes, Base64Url.decodeExact(canonical, 32, "test"))
        assertFailsWith<AuthProtocolException> { Base64Url.decode("$canonical=", "test") }
        assertFailsWith<AuthProtocolException> { Base64Url.decodeExact(canonical, 31, "test") }
    }

    @Test
    fun `Authorization profile formats one compact proof and rejects ambiguity`() {
        val compactJws = "header.payload.signature"

        assertEquals(
            compactJws,
            CocoaAuthorization.parse(listOf(CocoaAuthorization.format(compactJws))),
        )
        assertEquals(
            compactJws,
            CocoaAuthorization.parse(listOf("cocoadiskinfo-jws $compactJws")),
        )
        assertFailsWith<AuthProtocolException> { CocoaAuthorization.parse(emptyList()) }
        assertFailsWith<AuthProtocolException> {
            CocoaAuthorization.parse(listOf("CocoaDiskInfo-JWS $compactJws", "Basic credential"))
        }
        assertFailsWith<AuthProtocolException> {
            CocoaAuthorization.parse(listOf("CocoaDiskInfo-JWS  $compactJws"))
        }
    }

    private fun requestPayload(body: ByteArray): SignedRequestPayload {
        return CanonicalRequest(
            method = "POST",
            path = CocoaAuthProtocol.SNAPSHOT_INGEST_PATH,
            query = "",
            purpose = AuthPurpose.SNAPSHOT_INGEST,
            bodySha256 = SnapshotBodyDigest.bodySha256(body),
        ).signedPayload(Base64Url.encode(ByteArray(32) { it.toByte() }))
    }
}
