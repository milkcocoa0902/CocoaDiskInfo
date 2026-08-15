package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.connectDiskSnapshotTestDatabase
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class SecurityRepositoriesTest {
    private val baseTime = Instant.parse("2026-08-15T00:00:00Z")

    @Test
    fun `Hub identity is created once and remains independent of later candidates`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedHubIdentityRepository()
        val first = HubIdentity(testNodeId(100), baseTime)
        val laterCandidate = HubIdentity(testNodeId(101), baseTime.plusSeconds(60))

        val resolved = transaction {
            assertEquals(first, repository.getOrCreate(first))
            repository.getOrCreate(laterCandidate)
        }

        assertEquals(first, resolved)
        assertEquals(first, transaction { repository.find() })
    }

    @Test
    fun `principal lookup enforces status and last seen is monotonic`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedSecurityPrincipalRepository()
        val principal = nodePrincipal(seed = 200)

        transaction {
            repository.insert(principal)
            assertEquals(principal, repository.findByKid(principal.kid))
            assertEquals(principal, repository.findActiveByKid(principal.kid))
            assertTrue(repository.recordLastSeen(principal.principalId, baseTime.plusSeconds(60)))
            assertTrue(repository.recordLastSeen(principal.principalId, baseTime.plusSeconds(30)))
        }

        assertEquals(
            baseTime.plusSeconds(60),
            transaction { repository.findById(principal.principalId) }?.lastSeenAt,
        )

        transaction {
            assertTrue(repository.disable(principal.principalId))
            assertNull(repository.findActiveByKid(principal.kid))
            assertFalse(repository.recordLastSeen(principal.principalId, baseTime.plusSeconds(120)))
        }
        assertEquals(
            PrincipalStatus.DISABLED,
            transaction { repository.findByKid(principal.kid) }?.status,
        )
    }

    @Test
    fun `node and client principals preserve nested public JWK and node scope`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedSecurityPrincipalRepository()
        val node = nodePrincipal(seed = 300)
        val client = clientPrincipal(seed = 301)

        transaction {
            repository.insert(node)
            repository.insert(client)
        }

        val storedNode = transaction { repository.findById(node.principalId) }
        val storedClient = transaction { repository.findById(client.principalId) }
        assertEquals(node.publicKeyJwk, storedNode?.publicKeyJwk)
        assertEquals(node.nodeId, storedNode?.nodeId)
        assertEquals(client.publicKeyJwk, storedClient?.publicKeyJwk)
        assertNull(storedClient?.nodeId)
    }

    @Test
    fun `bootstrap token binding is name scoped atomic and idempotent for the same kid`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedBootstrapTokenRepository()
        val token = bootstrapToken(seed = 400, expectedDisplayName = "node-a")
        val kidA = nodePrincipal(seed = 401).kid
        val kidB = nodePrincipal(seed = 402).kid

        val mismatch = transaction {
            repository.insert(token)
            repository.bind(token.tokenId, kidA, "node-b", baseTime.plusSeconds(10))
        }
        assertEquals(BootstrapTokenBindResult.DisplayNameMismatch, mismatch)

        val first = transaction {
            repository.bind(token.tokenId, kidA, "node-a", baseTime.plusSeconds(20))
        }
        val sameKeyRetry = transaction {
            repository.bind(token.tokenId, kidA, "node-a", baseTime.plusSeconds(30))
        }
        val differentKey = transaction {
            repository.bind(token.tokenId, kidB, "node-a", baseTime.plusSeconds(30))
        }

        val bound = assertIs<BootstrapTokenBindResult.Bound>(first).token
        assertEquals(kidA, bound.boundKid)
        assertEquals(baseTime.plusSeconds(20), bound.usedAt)
        assertIs<BootstrapTokenBindResult.AlreadyBound>(sameKeyRetry)
        assertEquals(BootstrapTokenBindResult.DifferentKey, differentKey)
        assertContentEquals(token.joinKeyCopy(), transaction { repository.findById(token.tokenId) }?.joinKeyCopy())
    }

    @Test
    fun `expired bootstrap token cannot be newly bound or retried`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedBootstrapTokenRepository()
        val token = bootstrapToken(seed = 500)
        val kid = nodePrincipal(seed = 501).kid

        transaction { repository.insert(token) }
        val atExpiry = transaction {
            repository.bind(token.tokenId, kid, "any-node", token.expiresAt)
        }

        assertEquals(BootstrapTokenBindResult.Unavailable, atExpiry)
        assertNull(transaction { repository.findById(testNodeId(999)) })
    }

    @Test
    fun `concurrent bootstrap binding converges on one kid`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedBootstrapTokenRepository()
        val token = bootstrapToken(seed = 600)
        val kids = listOf(nodePrincipal(seed = 601).kid, nodePrincipal(seed = 602).kid)
        transaction { repository.insert(token) }

        val start = CountDownLatch(1)
        val done = CountDownLatch(kids.size)
        val results = mutableListOf<BootstrapTokenBindResult>()
        val executor = Executors.newFixedThreadPool(kids.size)
        try {
            kids.forEach { kid ->
                executor.execute {
                    start.await()
                    val result = transaction {
                        repository.bind(token.tokenId, kid, "node-a", baseTime.plusSeconds(10))
                    }
                    synchronized(results) { results += result }
                    done.countDown()
                }
            }
            start.countDown()
            done.await()
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it is BootstrapTokenBindResult.Bound })
        assertEquals(1, results.count { it == BootstrapTokenBindResult.DifferentKey })
        val persistedKid = transaction { repository.findById(token.tokenId) }?.boundKid
        assertTrue(persistedKid in kids)
    }

    private fun nodePrincipal(seed: Long): SecurityPrincipal {
        val keys = Ed25519Keys.generate()
        val jwk = Ed25519Keys.publicJwk(keys.public)
        return SecurityPrincipal(
            principalId = testNodeId(seed),
            principalType = PrincipalType.NODE_AGENT,
            displayName = "node-$seed",
            status = PrincipalStatus.ACTIVE,
            kid = Ed25519Keys.kid(jwk),
            publicKeyJwk = jwk,
            nodeId = testNodeId(seed + 10_000),
            createdAt = baseTime,
        )
    }

    private fun clientPrincipal(seed: Long): SecurityPrincipal {
        val keys = Ed25519Keys.generate()
        val jwk = Ed25519Keys.publicJwk(keys.public)
        return SecurityPrincipal(
            principalId = testNodeId(seed),
            principalType = PrincipalType.CLIENT,
            displayName = "client-$seed",
            status = PrincipalStatus.ACTIVE,
            kid = Ed25519Keys.kid(jwk),
            publicKeyJwk = jwk,
            nodeId = null,
            createdAt = baseTime,
        )
    }

    private fun bootstrapToken(
        seed: Long,
        expectedDisplayName: String? = null,
    ): BootstrapToken = BootstrapToken(
        tokenId = testNodeId(seed),
        tokenType = BootstrapTokenType.JOIN_TOKEN,
        joinKey = ByteArray(32) { it.toByte() },
        createdAt = baseTime,
        expiresAt = baseTime.plusSeconds(60),
        recoveryNodeId = testNodeId(seed + 20_000),
        expectedDisplayName = expectedDisplayName,
    )
}
