package com.milkcocoa.info.sapphire.agent.auth

import com.milkcocoa.info.sapphire.agent.datastore.BootstrapToken
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenBindResult
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenRepository
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenType
import com.milkcocoa.info.sapphire.agent.datastore.HubIdentityRepository
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentRegistration
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentRegistryRepository
import com.milkcocoa.info.sapphire.agent.datastore.PrincipalStatus
import com.milkcocoa.info.sapphire.agent.datastore.PrincipalType
import com.milkcocoa.info.sapphire.agent.datastore.SecurityPrincipal
import com.milkcocoa.info.sapphire.agent.datastore.SecurityPrincipalRepository
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.core.auth.AuthProtocolException
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthorization
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.Ed25519PublicJwk
import com.milkcocoa.info.sapphire.core.auth.JoinProof
import com.milkcocoa.info.sapphire.core.auth.NonceBinding
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import com.milkcocoa.info.sapphire.core.auth.SignedRequestJws
import com.milkcocoa.info.sapphire.core.auth.SnapshotBodyDigest
import java.security.SecureRandom
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Authentication result that is safe to use only after the caller consumes [nonce].
 *
 * The nonce is bound to the principal and [purpose]; consuming it is the replay
 * prevention step and is intentionally separate from cryptographic verification.
 */
data class VerifiedPrincipalRequest(
    /** The active principal whose public key verified the request. */
    val principal: SecurityPrincipal,
    /** The single-use nonce carried by the verified proof. */
    val nonce: String,
    /** The route operation for which the proof was verified. */
    val purpose: AuthPurpose,
)

/** Verifies signed requests while leaving the explicit single-use nonce transition to the caller. */
class SignedRequestVerifier(
    private val principalRepository: SecurityPrincipalRepository,
    private val transactionRunner: TransactionRunner,
    private val nonceStore: NonceStore,
) {
    /**
     * Verifies the Authorization JWS, principal type, canonical request, and body digest.
     *
     * This method does not consume the nonce. The route must call [consumeNonce] only
     * after request validation succeeds, so malformed requests do not burn a valid nonce.
     * For body-bearing requests, [bodyBytes] must be the exact bytes received and exactly
     * one matching Content-Digest value must be supplied.
     *
     * @param authorizationHeaders all Authorization header values from the request.
     * @param expectedRequest canonical method, path, query, and purpose expected by the route.
     * @param allowedPrincipalType principal role permitted to invoke the route.
     * @param bodyBytes raw request body, or null for a bodyless request.
     * @param contentDigestHeaders Content-Digest header values associated with [bodyBytes].
     * @return verified principal, nonce, and purpose for the request.
     * @throws AuthProtocolException when the proof, principal, role, canonical request, or
     * body binding is invalid.
     */
    suspend fun verify(
        authorizationHeaders: List<String>,
        expectedRequest: CanonicalRequest,
        allowedPrincipalType: PrincipalType,
        bodyBytes: ByteArray? = null,
        contentDigestHeaders: List<String> = emptyList(),
    ): VerifiedPrincipalRequest {
        val compactJws = CocoaAuthorization.parse(authorizationHeaders)
        val protectedHeader = SignedRequestJws.readProtectedHeader(compactJws)
        val principal = transactionRunner.readOnly {
            principalRepository.findActiveByKid(protectedHeader.kid)
        } ?: throw AuthProtocolException("Signing principal is unknown or disabled.")
        if (principal.principalType != allowedPrincipalType) {
            throw AuthProtocolException("Signing principal is not allowed for this route.")
        }

        val boundRequest = expectedRequest.copy(
            bodySha256 = bodyBytes?.let(SnapshotBodyDigest::bodySha256),
        )
        val verified = SignedRequestJws.verify(
            compactJws = compactJws,
            publicKey = Ed25519Keys.publicKey(principal.publicKeyJwk),
            expectedRequest = boundRequest,
        )
        verifyBodyBinding(verified.payload.bodySha256, bodyBytes, contentDigestHeaders)
        return VerifiedPrincipalRequest(
            principal = principal,
            nonce = verified.payload.nonce,
            purpose = expectedRequest.purpose,
        )
    }

    /**
     * Atomically consumes the nonce represented by [verified]. A nonce cannot be reused,
     * and a nonce issued for another principal or purpose is unavailable.
     *
     * @throws NonceUnavailableException when the nonce is expired, already consumed, or
     * bound to a different principal or purpose.
     */
    fun consumeNonce(verified: VerifiedPrincipalRequest) {
        val result = nonceStore.consume(
            nonce = verified.nonce,
            expectedBinding = NonceBinding(
                subjectType = NonceSubjectType.PRINCIPAL,
                subjectId = verified.principal.kid,
                purpose = verified.purpose,
            ),
        )
        if (result != NonceConsumeResult.CONSUMED) {
            throw NonceUnavailableException()
        }
    }

    /** Issues the next nonce bound to the same principal and purpose as [verified]. */
    fun issueNextNonce(verified: VerifiedPrincipalRequest, ttl: Duration): IssuedNonce =
        nonceStore.issue(
            binding = NonceBinding(
                subjectType = NonceSubjectType.PRINCIPAL,
                subjectId = verified.principal.kid,
                purpose = verified.purpose,
            ),
            ttl = ttl,
        )

    private fun verifyBodyBinding(
        signedBodySha256: String?,
        bodyBytes: ByteArray?,
        contentDigestHeaders: List<String>,
    ) {
        if (bodyBytes == null) {
            if (signedBodySha256 != null || contentDigestHeaders.isNotEmpty()) {
                throw AuthProtocolException("Body digest is not allowed for a bodyless request.")
            }
            return
        }
        if (signedBodySha256 == null || contentDigestHeaders.size != 1) {
            throw AuthProtocolException("A signed body requires exactly one Content-Digest header.")
        }
        if (!SnapshotBodyDigest.verify(bodyBytes, contentDigestHeaders.single(), signedBodySha256)) {
            throw AuthProtocolException("Request body digest does not match the signed proof.")
        }
    }
}

/** Indicates that a signed request nonce cannot be consumed exactly once. */
class NonceUnavailableException : IllegalArgumentException(
    "Nonce is expired, already used, or has the wrong binding.",
)

/** Issues nonces only for live bootstrap tokens or active principals allowed for a purpose. */
class AuthorizedNonceIssuer(
    private val tokenRepository: BootstrapTokenRepository,
    private val principalRepository: SecurityPrincipalRepository,
    private val transactionRunner: TransactionRunner,
    private val nonceStore: NonceStore,
    private val now: () -> Instant = Instant::now,
) {
    /**
     * Issues a nonce after checking the subject's repository state and purpose/role pairing.
     *
     * @throws NonceSubjectUnavailableException when the token or principal is missing,
     * expired, disabled, or not authorized for [request].
     * @throws NonceCapacityExceededException when the nonce store cannot accept another entry.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun issue(request: NonceIssueRequest, ttl: Duration): IssuedNonce {
        val binding = NonceBinding(request.subjectType, request.subjectId, request.purpose)
        val allowed = transactionRunner.readOnly {
            when (request.subjectType) {
                NonceSubjectType.JOIN_TOKEN,
                NonceSubjectType.PAIRING_TOKEN,
                -> {
                    val tokenId = runCatching { Uuid.parse(request.subjectId) }.getOrNull()
                        ?: return@readOnly false
                    val token = tokenRepository.findById(tokenId) ?: return@readOnly false
                    token.expiresAt > now() &&
                        token.tokenType.toNonceSubjectType() == request.subjectType &&
                        token.tokenType.toPurpose() == request.purpose
                }
                NonceSubjectType.PRINCIPAL -> {
                    val principal = principalRepository.findActiveByKid(request.subjectId)
                        ?: return@readOnly false
                    when (request.purpose) {
                        AuthPurpose.SNAPSHOT_INGEST,
                        AuthPurpose.HEARTBEAT,
                        -> principal.principalType == PrincipalType.NODE_AGENT
                        AuthPurpose.CLIENT_READ -> principal.principalType == PrincipalType.CLIENT
                        AuthPurpose.JOIN,
                        AuthPurpose.CLIENT_PAIR,
                        -> false
                    }
                }
            }
        }
        if (!allowed) throw NonceSubjectUnavailableException()
        return nonceStore.issue(binding, ttl)
    }
}

/** Indicates that a nonce subject is not eligible for the requested authentication purpose. */
class NonceSubjectUnavailableException : IllegalArgumentException(
    "Nonce subject is unknown, expired, disabled, or not permitted for this purpose.",
)

/** Bootstrap token record plus the one-time secret that must be delivered to the joiner. */
data class IssuedBootstrapToken(
    /** Persisted token metadata; the secret itself is not stored in this object. */
    val token: BootstrapToken,
    /** Base64url-encoded secret used to compute the registration proof. */
    val secret: String,
)

/** Creates expiring join or client-pairing tokens and persists only their derived key. */
@OptIn(ExperimentalUuidApi::class)
class BootstrapTokenService(
    private val repository: BootstrapTokenRepository,
    private val transactionRunner: TransactionRunner,
    private val now: () -> Instant = Instant::now,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    /**
     * Creates and persists a bootstrap token in one write transaction.
     *
     * @param type determines whether the token registers a node or pairs a client.
     * @param ttl positive lifetime of the token.
     * @param expectedDisplayName optional display-name restriction.
     * @param recoveryNodeId existing node identity to reactivate for a join token, if any.
     * @return persisted token metadata and its base64url secret.
     * @throws IllegalArgumentException when [ttl] is not positive.
     */
    suspend fun issue(
        type: BootstrapTokenType,
        ttl: Duration = DEFAULT_TTL,
        expectedDisplayName: String? = null,
        recoveryNodeId: Uuid? = null,
    ): IssuedBootstrapToken {
        require(ttl.isPositive()) { "Bootstrap token TTL must be positive." }
        val secretBytes = ByteArray(SECRET_SIZE_BYTES).also(secureRandom::nextBytes)
        val createdAt = now()
        val token = BootstrapToken(
            tokenId = Uuid.random(),
            tokenType = type,
            joinKey = JoinProof.deriveJoinKey(secretBytes),
            createdAt = createdAt,
            expiresAt = createdAt.plusMillis(ttl.inWholeMilliseconds),
            recoveryNodeId = recoveryNodeId,
            expectedDisplayName = expectedDisplayName,
        )
        transactionRunner.readWrite { repository.insert(token) }
        return IssuedBootstrapToken(token, Base64Url.encode(secretBytes))
    }

    companion object {
        const val SECRET_SIZE_BYTES = 32
        val DEFAULT_TTL = 10.minutes
    }
}

@OptIn(ExperimentalUuidApi::class)
/** Input to a proof-of-possession bootstrap registration request. */
data class BootstrapRegistrationRequest(
    /** UUID of the join or pairing token. */
    val tokenId: Uuid,
    /** Token class expected by the endpoint and proof binding. */
    val expectedTokenType: BootstrapTokenType,
    /** Single-use nonce issued for this token and registration purpose. */
    val nonce: String,
    /** New principal's Ed25519 public key. */
    val publicKeyJwk: Ed25519PublicJwk,
    /** Key identifier derived from [publicKeyJwk]. */
    val kid: String,
    /** Human-readable node/client name subject to token policy. */
    val displayName: String,
    /** Required for node joins; forbidden for client pairing. */
    val expectedCollectionIntervalSeconds: Long? = null,
    /** Base64url HMAC proof over the canonical hub/token/request identity. */
    val proof: String,
)

/** Result of bootstrap registration, including the hub identity and created/reused principal. */
@OptIn(ExperimentalUuidApi::class)
data class BootstrapRegistrationResult(
    /** Stable hub identity used in join proof canonicalization. */
    val hubId: Uuid,
    /** Active principal bound to the presented key. */
    val principal: SecurityPrincipal,
)

/** Validates bootstrap proofs and atomically binds tokens to principals and node records. */
@OptIn(ExperimentalUuidApi::class)
class BootstrapRegistrationService(
    private val hubIdentityRepository: HubIdentityRepository,
    private val tokenRepository: BootstrapTokenRepository,
    private val principalRepository: SecurityPrincipalRepository,
    private val nodeRegistryRepository: NodeAgentRegistryRepository,
    private val transactionRunner: TransactionRunner,
    private val nonceStore: NonceStore,
    private val now: () -> Instant = Instant::now,
) {
    /**
     * Validates and registers [request]. Token type, expiry, name, key identifier, interval,
     * and HMAC are checked before the nonce is consumed. Principal/token/node registry writes
     * then occur in one transaction; retrying an already-bound key returns the existing principal.
     *
     * @throws BootstrapRegistrationException for protocol, token, name, key, or interval errors.
     * @throws NonceUnavailableException when the registration nonce is not available.
     */
    suspend fun register(request: BootstrapRegistrationRequest): BootstrapRegistrationResult {
        validateRequest(request)
        val hubIdentity = transactionRunner.readOnly {
            checkNotNull(hubIdentityRepository.find()) { "Hub identity has not been initialized." }
        }
        val token = transactionRunner.readOnly {
            tokenRepository.findById(request.tokenId)
        } ?: throw BootstrapRegistrationException("bootstrap_token_unavailable", "Bootstrap token is unavailable.")
        if (token.tokenType != request.expectedTokenType || token.expiresAt <= now()) {
            throw BootstrapRegistrationException("bootstrap_token_unavailable", "Bootstrap token is unavailable.")
        }

        val canonicalInput = JoinProof.canonicalInput(
            hubId = hubIdentity.hubId.toString(),
            joinTokenId = request.tokenId.toString(),
            nonce = request.nonce,
            kid = request.kid,
            nodeName = request.displayName,
        )
        val proofBytes = Base64Url.decodeExact(request.proof, 32, "bootstrap proof")
        if (!JoinProof.verify(token.joinKeyCopy(), canonicalInput, proofBytes)) {
            throw BootstrapRegistrationException("bootstrap_proof_invalid", "Bootstrap proof is invalid.")
        }

        // Consume only after every attacker-controlled field and the HMAC have been
        // validated. A malformed registration must not burn a legitimate nonce.
        val nonceResult = nonceStore.consume(
            request.nonce,
            NonceBinding(
                subjectType = request.expectedTokenType.toNonceSubjectType(),
                subjectId = request.tokenId.toString(),
                purpose = request.expectedTokenType.toPurpose(),
            ),
        )
        if (nonceResult != NonceConsumeResult.CONSUMED) throw NonceUnavailableException()

        return transactionRunner.readWrite {
            registerInTransaction(hubIdentity.hubId, token, request)
        }
    }

    private fun registerInTransaction(
        hubId: Uuid,
        token: BootstrapToken,
        request: BootstrapRegistrationRequest,
    ): BootstrapRegistrationResult {
        return when (
            val binding = tokenRepository.bind(
                tokenId = request.tokenId,
                kid = request.kid,
                displayName = request.displayName,
                usedAt = now(),
            )
        ) {
            BootstrapTokenBindResult.Unavailable -> throw BootstrapRegistrationException(
                "bootstrap_token_unavailable",
                "Bootstrap token is unavailable.",
            )
            BootstrapTokenBindResult.DifferentKey -> throw BootstrapRegistrationException(
                "bootstrap_token_reused",
                "Bootstrap token is already bound to another key.",
            )
            BootstrapTokenBindResult.DisplayNameMismatch -> throw BootstrapRegistrationException(
                "bootstrap_name_mismatch",
                "Bootstrap token is restricted to another display name.",
            )
            is BootstrapTokenBindResult.Bound -> createPrincipal(hubId, token, request)
            is BootstrapTokenBindResult.AlreadyBound -> {
                val existing = principalRepository.findByKid(request.kid)
                    ?: throw IllegalStateException("Bootstrap token is bound but its principal is missing.")
                BootstrapRegistrationResult(hubId, existing)
            }
        }
    }

    private fun createPrincipal(
        hubId: Uuid,
        token: BootstrapToken,
        request: BootstrapRegistrationRequest,
    ): BootstrapRegistrationResult {
        val nodeId = if (request.expectedTokenType == BootstrapTokenType.JOIN_TOKEN) {
            token.recoveryNodeId ?: Uuid.random()
        } else {
            null
        }
        val createdAt = now()
        val principal = SecurityPrincipal(
            principalId = Uuid.random(),
            principalType = request.expectedTokenType.toPrincipalType(),
            displayName = request.displayName,
            status = PrincipalStatus.ACTIVE,
            kid = request.kid,
            publicKeyJwk = request.publicKeyJwk,
            nodeId = nodeId,
            createdAt = createdAt,
        )
        principalRepository.insert(principal)
        if (nodeId != null) {
            nodeRegistryRepository.activate(
                NodeAgentRegistration(
                    nodeId = nodeId,
                    nodeName = request.displayName,
                    expectedCollectionIntervalSeconds = checkNotNull(request.expectedCollectionIntervalSeconds),
                    joinedAt = createdAt,
                ),
            )
        }
        return BootstrapRegistrationResult(hubId, principal)
    }

    private fun validateRequest(request: BootstrapRegistrationRequest) {
        if (request.displayName.isBlank() || request.displayName.length > SecurityPrincipal.MAX_DISPLAY_NAME_LENGTH) {
            throw BootstrapRegistrationException("bootstrap_name_invalid", "Display name must contain 1 to 255 characters.")
        }
        if (Ed25519Keys.kid(request.publicKeyJwk) != request.kid) {
            throw BootstrapRegistrationException("bootstrap_kid_invalid", "kid does not match the public key.")
        }
        when (request.expectedTokenType) {
            BootstrapTokenType.JOIN_TOKEN -> if (
                request.expectedCollectionIntervalSeconds == null ||
                request.expectedCollectionIntervalSeconds <= 0
            ) {
                throw BootstrapRegistrationException(
                    "collection_interval_invalid",
                    "Expected collection interval must be greater than zero.",
                )
            }
            BootstrapTokenType.PAIRING_TOKEN -> if (request.expectedCollectionIntervalSeconds != null) {
                throw BootstrapRegistrationException(
                    "collection_interval_unexpected",
                    "Client pairing must not include a collection interval.",
                )
            }
        }
    }
}

/** Typed bootstrap failure whose [code] maps to the HTTP API error contract. */
class BootstrapRegistrationException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

private fun BootstrapTokenType.toNonceSubjectType(): NonceSubjectType = when (this) {
    BootstrapTokenType.JOIN_TOKEN -> NonceSubjectType.JOIN_TOKEN
    BootstrapTokenType.PAIRING_TOKEN -> NonceSubjectType.PAIRING_TOKEN
}

private fun BootstrapTokenType.toPurpose(): AuthPurpose = when (this) {
    BootstrapTokenType.JOIN_TOKEN -> AuthPurpose.JOIN
    BootstrapTokenType.PAIRING_TOKEN -> AuthPurpose.CLIENT_PAIR
}

private fun BootstrapTokenType.toPrincipalType(): PrincipalType = when (this) {
    BootstrapTokenType.JOIN_TOKEN -> PrincipalType.NODE_AGENT
    BootstrapTokenType.PAIRING_TOKEN -> PrincipalType.CLIENT
}
