package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Uses the same explicitly configured, dedicated PostgreSQL database as the
 * Phase 4/5 storage integration suite. All security rows created here are removed.
 */
@OptIn(ExperimentalUuidApi::class)
class PostgreSqlSecurityRepositoryIntegrationTest {
    @Test
    fun `postgresql persists nested JWK and bootstrap join key contracts`() = runBlocking {
        val storage = postgreSqlSettings()
        assumeTrue(
            storage != null,
            "Set COCOADISKINFO_TEST_POSTGRESQL_JDBC_URL to run PostgreSQL security integration.",
        )
        checkNotNull(storage)
        createStorageMigratorFactory().create(storage).migrate(storage)

        StorageConnectionFactory.connect(storage).use { connection ->
            val transactionRunner = ExposedTransactionRunner(connection.database)
            val principalRepository = ExposedSecurityPrincipalRepository()
            val tokenRepository = ExposedBootstrapTokenRepository()
            val keyPair = Ed25519Keys.generate()
            val jwk = Ed25519Keys.publicJwk(keyPair.public)
            val principal = SecurityPrincipal(
                principalId = randomUuid(),
                principalType = PrincipalType.CLIENT,
                displayName = "postgresql-client",
                status = PrincipalStatus.ACTIVE,
                kid = Ed25519Keys.kid(jwk),
                publicKeyJwk = jwk,
                nodeId = null,
                createdAt = Instant.parse("2026-08-15T00:00:00Z"),
            )
            val token = BootstrapToken(
                tokenId = randomUuid(),
                tokenType = BootstrapTokenType.PAIRING_TOKEN,
                joinKey = ByteArray(32) { (it + 1).toByte() },
                createdAt = principal.createdAt,
                expiresAt = principal.createdAt.plusSeconds(60),
                expectedDisplayName = principal.displayName,
            )

            try {
                transactionRunner.readWrite {
                    principalRepository.insert(principal)
                    tokenRepository.insert(token)
                }
                val bindResult = transactionRunner.readWrite {
                    tokenRepository.bind(
                        tokenId = token.tokenId,
                        kid = principal.kid,
                        displayName = principal.displayName,
                        usedAt = principal.createdAt.plusSeconds(10),
                    )
                }
                assertIs<BootstrapTokenBindResult.Bound>(bindResult)

                val storedPrincipal = transactionRunner.readOnly {
                    principalRepository.findByKid(principal.kid)
                }
                val storedToken = transactionRunner.readOnly {
                    tokenRepository.findById(token.tokenId)
                }
                assertEquals(jwk, storedPrincipal?.publicKeyJwk)
                assertContentEquals(token.joinKeyCopy(), storedToken?.joinKeyCopy())
                assertPostgreSqlSecurityColumnTypes(connection)
            } finally {
                connection.useJdbcConnection { jdbc ->
                    jdbc.prepareStatement("DELETE FROM bootstrap_token WHERE token_id = ?::uuid").use { statement ->
                        statement.setString(1, token.tokenId.toString())
                        statement.executeUpdate()
                    }
                    jdbc.prepareStatement("DELETE FROM security_principal WHERE principal_id = ?::uuid").use { statement ->
                        statement.setString(1, principal.principalId.toString())
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun assertPostgreSqlSecurityColumnTypes(connection: StorageConnection) {
        val types = connection.useJdbcConnection { jdbc ->
            jdbc.prepareStatement(
                """
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND ((table_name = 'security_principal' AND column_name = 'public_key_jwk')
                    OR (table_name = 'bootstrap_token' AND column_name = 'join_key')
                    OR (table_name = 'hub_identity' AND column_name = 'hub_id'))
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            put(rows.getString("table_name") to rows.getString("column_name"), rows.getString("data_type"))
                        }
                    }
                }
            }
        }
        assertEquals("jsonb", types["security_principal" to "public_key_jwk"])
        assertEquals("bytea", types["bootstrap_token" to "join_key"])
        assertEquals("uuid", types["hub_identity" to "hub_id"])
    }

    private fun postgreSqlSettings(): StorageSettings? {
        val jdbcUrl = explicitSetting(
            "cocoadiskinfo.test.postgresql.jdbcUrl",
            "COCOADISKINFO_TEST_POSTGRESQL_JDBC_URL",
        ) ?: return null
        return StorageSettings(
            backend = StorageBackend.POSTGRESQL,
            jdbcUrl = jdbcUrl,
            username = explicitSetting(
                "cocoadiskinfo.test.postgresql.username",
                "COCOADISKINFO_TEST_POSTGRESQL_USERNAME",
            ),
            password = explicitSetting(
                "cocoadiskinfo.test.postgresql.password",
                "COCOADISKINFO_TEST_POSTGRESQL_PASSWORD",
            ),
        )
    }

    private fun explicitSetting(systemProperty: String, environmentVariable: String): String? =
        System.getProperty(systemProperty)?.takeIf(String::isNotBlank)
            ?: System.getenv(environmentVariable)?.takeIf(String::isNotBlank)

    private fun randomUuid(): Uuid = UUID.randomUUID().let {
        Uuid.fromLongs(it.mostSignificantBits, it.leastSignificantBits)
    }
}
