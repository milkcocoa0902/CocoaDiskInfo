package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.Ed25519PublicJwk
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val SecurityJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

enum class PrincipalType {
    NODE_AGENT,
    CLIENT,
}

enum class PrincipalStatus {
    ACTIVE,
    DISABLED,
}

enum class BootstrapTokenType {
    JOIN_TOKEN,
    PAIRING_TOKEN,
}

@OptIn(ExperimentalUuidApi::class)
data class HubIdentity(
    val hubId: Uuid,
    val createdAt: Instant,
)

@OptIn(ExperimentalUuidApi::class)
data class SecurityPrincipal(
    val principalId: Uuid,
    val principalType: PrincipalType,
    val displayName: String,
    val status: PrincipalStatus,
    val kid: String,
    val publicKeyJwk: Ed25519PublicJwk,
    val keyAlgorithm: String = KEY_ALGORITHM_ED25519,
    val nodeId: Uuid?,
    val createdAt: Instant,
    val lastSeenAt: Instant? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Principal displayName must not be blank." }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Principal displayName must be at most $MAX_DISPLAY_NAME_LENGTH characters."
        }
        require(keyAlgorithm == KEY_ALGORITHM_ED25519) { "Principal key algorithm must be Ed25519." }
        require(Ed25519Keys.kid(publicKeyJwk) == kid) { "Principal kid must match its public JWK." }
        require(
            (principalType == PrincipalType.NODE_AGENT && nodeId != null) ||
                (principalType == PrincipalType.CLIENT && nodeId == null),
        ) { "NODE_AGENT principals require nodeId and CLIENT principals must not have nodeId." }
    }

    companion object {
        const val KEY_ALGORITHM_ED25519 = "Ed25519"
        const val MAX_DISPLAY_NAME_LENGTH = 255
    }
}

@OptIn(ExperimentalUuidApi::class)
class BootstrapToken(
    val tokenId: Uuid,
    val tokenType: BootstrapTokenType,
    joinKey: ByteArray,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val boundKid: String? = null,
    val recoveryNodeId: Uuid? = null,
    val expectedDisplayName: String? = null,
) {
    private val joinKeyBytes: ByteArray = joinKey.copyOf()

    init {
        require(joinKeyBytes.size == JOIN_KEY_SIZE) { "Bootstrap token joinKey must be 32 bytes." }
        require(expiresAt > createdAt) { "Bootstrap token expiresAt must be after createdAt." }
        require((usedAt == null) == (boundKid == null)) {
            "Bootstrap token usedAt and boundKid must be set together."
        }
        usedAt?.let {
            require(it >= createdAt && it < expiresAt) {
                "Bootstrap token usedAt must be within the token lifetime."
            }
        }
        boundKid?.let { Base64Url.decodeExact(it, 32, "boundKid") }
        require(tokenType == BootstrapTokenType.JOIN_TOKEN || recoveryNodeId == null) {
            "Only JOIN_TOKEN may be scoped to a recovery node."
        }
        expectedDisplayName?.let {
            require(it.isNotBlank()) { "Bootstrap token expectedDisplayName must not be blank." }
            require(it.length <= SecurityPrincipal.MAX_DISPLAY_NAME_LENGTH) {
                "Bootstrap token expectedDisplayName must be at most 255 characters."
            }
        }
    }

    fun joinKeyCopy(): ByteArray = joinKeyBytes.copyOf()

    companion object {
        const val JOIN_KEY_SIZE = 32
    }
}

sealed interface BootstrapTokenBindResult {
    data class Bound(val token: BootstrapToken) : BootstrapTokenBindResult
    data class AlreadyBound(val token: BootstrapToken) : BootstrapTokenBindResult
    data object Unavailable : BootstrapTokenBindResult
    data object DifferentKey : BootstrapTokenBindResult
    data object DisplayNameMismatch : BootstrapTokenBindResult
}

@OptIn(ExperimentalUuidApi::class)
interface HubIdentityRepository {
    fun getOrCreate(candidate: HubIdentity): HubIdentity
    fun find(): HubIdentity?
}

@OptIn(ExperimentalUuidApi::class)
interface SecurityPrincipalRepository {
    fun insert(principal: SecurityPrincipal)
    fun findById(principalId: Uuid): SecurityPrincipal?
    fun findByKid(kid: String): SecurityPrincipal?
    fun findActiveByKid(kid: String): SecurityPrincipal?
    fun disable(principalId: Uuid): Boolean
    fun recordLastSeen(principalId: Uuid, seenAt: Instant): Boolean
}

@OptIn(ExperimentalUuidApi::class)
interface BootstrapTokenRepository {
    fun insert(token: BootstrapToken)
    fun findById(tokenId: Uuid): BootstrapToken?

    fun bind(
        tokenId: Uuid,
        kid: String,
        displayName: String,
        usedAt: Instant,
    ): BootstrapTokenBindResult
}

@OptIn(ExperimentalUuidApi::class)
object HubIdentityTable : Table("hub_identity") {
    val singletonKey = integer("singleton_key")
    val hubId = uuid("hub_id")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(singletonKey)
}

@OptIn(ExperimentalUuidApi::class)
object SecurityPrincipalTable : Table("security_principal") {
    val principalId = uuid("principal_id")
    val principalType = varchar("principal_type", 32)
    val displayName = varchar("display_name", SecurityPrincipal.MAX_DISPLAY_NAME_LENGTH)
    val status = varchar("status", 32)
    val kid = varchar("kid", 43)
    val publicKeyJwk = jsonb<Ed25519PublicJwk>(
        "public_key_jwk",
        serialize = SecurityJson::encodeToString,
        deserialize = SecurityJson::decodeFromString,
    )
    val keyAlgorithm = varchar("key_algorithm", 32)
    val nodeId = uuid("node_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()

    override val primaryKey = PrimaryKey(principalId)

    init {
        uniqueIndex("uq_security_principal_kid", kid)
        index(false, status, principalType)
    }
}

@OptIn(ExperimentalUuidApi::class)
object BootstrapTokenTable : Table("bootstrap_token") {
    val tokenId = uuid("token_id")
    val tokenType = varchar("token_type", 32)
    val joinKey = binary("join_key", BootstrapToken.JOIN_KEY_SIZE)
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val boundKid = varchar("bound_kid", 43).nullable()
    val recoveryNodeId = uuid("recovery_node_id").nullable()
    val expectedDisplayName = varchar("expected_display_name", SecurityPrincipal.MAX_DISPLAY_NAME_LENGTH).nullable()

    override val primaryKey = PrimaryKey(tokenId)

    init {
        index(false, expiresAt)
    }
}

@OptIn(ExperimentalUuidApi::class)
class ExposedHubIdentityRepository : HubIdentityRepository {
    override fun getOrCreate(candidate: HubIdentity): HubIdentity {
        // The fixed key turns concurrent first starts into insert-or-read. The
        // caller's later public URL never participates in Hub identity.
        HubIdentityTable.insertIgnore {
            it[singletonKey] = SINGLETON_KEY
            it[hubId] = candidate.hubId
            it[createdAt] = candidate.createdAt.asOffsetDateTime()
        }
        return checkNotNull(find()) { "Failed to resolve persistent Hub identity." }
    }

    override fun find(): HubIdentity? = HubIdentityTable
        .selectAll()
        .where { HubIdentityTable.singletonKey eq SINGLETON_KEY }
        .limit(1)
        .singleOrNull()
        ?.let { row ->
            HubIdentity(
                hubId = row[HubIdentityTable.hubId],
                createdAt = row[HubIdentityTable.createdAt].toInstant(),
            )
        }

    private companion object {
        const val SINGLETON_KEY = 1
    }
}

@OptIn(ExperimentalUuidApi::class)
class ExposedSecurityPrincipalRepository : SecurityPrincipalRepository {
    override fun insert(principal: SecurityPrincipal) {
        SecurityPrincipalTable.insert {
            it[principalId] = principal.principalId
            it[principalType] = principal.principalType.name
            it[displayName] = principal.displayName
            it[status] = principal.status.name
            it[kid] = principal.kid
            it[publicKeyJwk] = principal.publicKeyJwk
            it[keyAlgorithm] = principal.keyAlgorithm
            it[nodeId] = principal.nodeId
            it[createdAt] = principal.createdAt.asOffsetDateTime()
            it[lastSeenAt] = principal.lastSeenAt?.asOffsetDateTime()
        }
    }

    override fun findById(principalId: Uuid): SecurityPrincipal? =
        findWhere { SecurityPrincipalTable.principalId eq principalId }

    override fun findByKid(kid: String): SecurityPrincipal? =
        findWhere { SecurityPrincipalTable.kid eq kid }

    override fun findActiveByKid(kid: String): SecurityPrincipal? =
        findWhere {
            (SecurityPrincipalTable.kid eq kid) and
                (SecurityPrincipalTable.status eq PrincipalStatus.ACTIVE.name)
        }

    override fun disable(principalId: Uuid): Boolean = SecurityPrincipalTable.update({
        (SecurityPrincipalTable.principalId eq principalId) and
            (SecurityPrincipalTable.status eq PrincipalStatus.ACTIVE.name)
    }) {
        it[status] = PrincipalStatus.DISABLED.name
    } > 0

    override fun recordLastSeen(principalId: Uuid, seenAt: Instant): Boolean {
        val timestamp = seenAt.asOffsetDateTime()
        val updated = SecurityPrincipalTable.update({
            (SecurityPrincipalTable.principalId eq principalId) and
                (SecurityPrincipalTable.status eq PrincipalStatus.ACTIVE.name) and
                (SecurityPrincipalTable.lastSeenAt.isNull() or
                    (SecurityPrincipalTable.lastSeenAt less timestamp))
        }) {
            it[lastSeenAt] = timestamp
        } > 0
        if (updated) return true

        // An older/equal observation is still valid activity. Report whether the
        // principal remains active without moving its persisted clock backwards.
        return SecurityPrincipalTable
            .selectAll()
            .where {
                (SecurityPrincipalTable.principalId eq principalId) and
                    (SecurityPrincipalTable.status eq PrincipalStatus.ACTIVE.name)
            }
            .limit(1)
            .any()
    }

    private fun findWhere(
        condition: () -> org.jetbrains.exposed.v1.core.Op<Boolean>,
    ): SecurityPrincipal? = SecurityPrincipalTable
        .selectAll()
        .where(condition)
        .limit(1)
        .singleOrNull()
        ?.toSecurityPrincipal()
}

@OptIn(ExperimentalUuidApi::class)
class ExposedBootstrapTokenRepository : BootstrapTokenRepository {
    override fun insert(token: BootstrapToken) {
        BootstrapTokenTable.insert {
            it[tokenId] = token.tokenId
            it[tokenType] = token.tokenType.name
            it[joinKey] = token.joinKeyCopy()
            it[createdAt] = token.createdAt.asOffsetDateTime()
            it[expiresAt] = token.expiresAt.asOffsetDateTime()
            it[usedAt] = token.usedAt?.asOffsetDateTime()
            it[boundKid] = token.boundKid
            it[recoveryNodeId] = token.recoveryNodeId
            it[expectedDisplayName] = token.expectedDisplayName
        }
    }

    override fun findById(tokenId: Uuid): BootstrapToken? = BootstrapTokenTable
        .selectAll()
        .where { BootstrapTokenTable.tokenId eq tokenId }
        .limit(1)
        .singleOrNull()
        ?.toBootstrapToken()

    override fun bind(
        tokenId: Uuid,
        kid: String,
        displayName: String,
        usedAt: Instant,
    ): BootstrapTokenBindResult {
        require(displayName.isNotBlank()) { "Bootstrap displayName must not be blank." }
        Base64Url.decodeExact(kid, 32, "kid")
        val current = findById(tokenId) ?: return BootstrapTokenBindResult.Unavailable
        if (usedAt < current.createdAt || current.expiresAt <= usedAt) {
            return BootstrapTokenBindResult.Unavailable
        }
        if (current.expectedDisplayName != null && current.expectedDisplayName != displayName) {
            return BootstrapTokenBindResult.DisplayNameMismatch
        }
        if (current.boundKid != null) {
            return if (current.boundKid == kid) {
                BootstrapTokenBindResult.AlreadyBound(current)
            } else {
                BootstrapTokenBindResult.DifferentKey
            }
        }

        // Classification is repeated after the conditional update. That second
        // read is what turns a concurrent loser into idempotent-same-key or
        // different-key instead of incorrectly reporting a fresh bind.
        val updated = BootstrapTokenTable.update({
            (BootstrapTokenTable.tokenId eq tokenId) and
                BootstrapTokenTable.boundKid.isNull() and
                (BootstrapTokenTable.expiresAt greater usedAt.asOffsetDateTime())
        }) {
            it[BootstrapTokenTable.boundKid] = kid
            it[BootstrapTokenTable.usedAt] = usedAt.asOffsetDateTime()
        }
        val result = findById(tokenId) ?: return BootstrapTokenBindResult.Unavailable
        return when {
            result.expiresAt <= usedAt -> BootstrapTokenBindResult.Unavailable
            result.expectedDisplayName != null && result.expectedDisplayName != displayName ->
                BootstrapTokenBindResult.DisplayNameMismatch
            result.boundKid == kid && updated > 0 -> BootstrapTokenBindResult.Bound(result)
            result.boundKid == kid -> BootstrapTokenBindResult.AlreadyBound(result)
            else -> BootstrapTokenBindResult.DifferentKey
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun org.jetbrains.exposed.v1.core.ResultRow.toSecurityPrincipal(): SecurityPrincipal =
    SecurityPrincipal(
        principalId = this[SecurityPrincipalTable.principalId],
        principalType = PrincipalType.valueOf(this[SecurityPrincipalTable.principalType]),
        displayName = this[SecurityPrincipalTable.displayName],
        status = PrincipalStatus.valueOf(this[SecurityPrincipalTable.status]),
        kid = this[SecurityPrincipalTable.kid],
        publicKeyJwk = this[SecurityPrincipalTable.publicKeyJwk],
        keyAlgorithm = this[SecurityPrincipalTable.keyAlgorithm],
        nodeId = this[SecurityPrincipalTable.nodeId],
        createdAt = this[SecurityPrincipalTable.createdAt].toInstant(),
        lastSeenAt = this[SecurityPrincipalTable.lastSeenAt]?.toInstant(),
    )

@OptIn(ExperimentalUuidApi::class)
private fun org.jetbrains.exposed.v1.core.ResultRow.toBootstrapToken(): BootstrapToken =
    BootstrapToken(
        tokenId = this[BootstrapTokenTable.tokenId],
        tokenType = BootstrapTokenType.valueOf(this[BootstrapTokenTable.tokenType]),
        joinKey = this[BootstrapTokenTable.joinKey],
        createdAt = this[BootstrapTokenTable.createdAt].toInstant(),
        expiresAt = this[BootstrapTokenTable.expiresAt].toInstant(),
        usedAt = this[BootstrapTokenTable.usedAt]?.toInstant(),
        boundKid = this[BootstrapTokenTable.boundKid],
        recoveryNodeId = this[BootstrapTokenTable.recoveryNodeId],
        expectedDisplayName = this[BootstrapTokenTable.expectedDisplayName],
    )

private fun Instant.asOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
