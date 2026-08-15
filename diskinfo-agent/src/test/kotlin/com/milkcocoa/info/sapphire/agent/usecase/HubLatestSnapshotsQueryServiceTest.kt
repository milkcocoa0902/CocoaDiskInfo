package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.ExposedDiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedNodeAgentRegistryRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedTransactionRunner
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotCursor
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentFailure
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentRegistration
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.datastore.createStorageMigratorFactory
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.agent.testSnapshotRecord
import com.milkcocoa.info.sapphire.core.api.NodeStatus
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class HubLatestSnapshotsQueryServiceTest {
    @Test
    fun `freshness uses received time with an exact fresh boundary and typed pagination`() =
        withHubStore { snapshots, registry, runner ->
            val now = Instant.parse("2026-08-15T12:00:00Z")
            val boundaryNode = testNodeId(101)
            val futureNode = testNodeId(102)

            runner.readWrite {
                registry.activate(NodeAgentRegistration(boundaryNode, "current-a", 60, now.minusSeconds(300)))
                registry.activate(NodeAgentRegistration(futureNode, "current-b", 60, now.minusSeconds(300)))
                snapshots.insert(
                    testSnapshotRecord(
                        snapshot = testDiskSnapshot("device-a", timestampMillis = 1_000),
                        nodeId = boundaryNode,
                        nodeName = "historical-a",
                        receivedAt = now.minusSeconds(120),
                    ),
                )
                snapshots.insert(
                    testSnapshotRecord(
                        snapshot = testDiskSnapshot("device-b", timestampMillis = 2_000),
                        nodeId = futureNode,
                        nodeName = "historical-b",
                        receivedAt = now.plusSeconds(10),
                    ),
                )
                registry.recordStoredSnapshot(boundaryNode, now.minusSeconds(120))
                registry.recordHeartbeat(boundaryNode, 60, now)
                registry.recordStoredSnapshot(futureNode, now.plusSeconds(10))
            }

            val service = service(snapshots, registry, runner, now)
            val firstPage = service.findLatestPage(LatestSnapshotPageRequest(limit = 1))
            val secondPage = service.findLatestPage(
                LatestSnapshotPageRequest(cursor = firstPage.nextCursor, limit = 1),
            )

            assertEquals(LatestSnapshotCursor(boundaryNode, "device-a"), firstPage.nextCursor)
            assertTrue(firstPage.payload.pagination?.hasMore == true)
            assertNull(firstPage.payload.pagination?.nextCursor)
            assertEquals("current-a", firstPage.payload.nodes.single().nodeName)
            assertEquals(now, firstPage.payload.nodes.single().lastSeenAt?.toJavaInstant())
            assertEquals(120_000, firstPage.payload.nodes.single().deviceStates.single().ageMs)
            assertFalse(firstPage.payload.nodes.single().deviceStates.single().stale)
            assertFalse(firstPage.payload.partial)

            assertNull(secondPage.nextCursor)
            assertEquals(0, secondPage.payload.nodes.single().deviceStates.single().ageMs)
            assertFalse(secondPage.payload.nodes.single().deviceStates.single().stale)

            val justBeyondBoundary = service(snapshots, registry, runner, now.plusMillis(1))
                .findLatestPage(LatestSnapshotPageRequest(limit = 1))
            assertTrue(justBeyondBoundary.payload.nodes.single().deviceStates.single().stale)
            assertTrue(justBeyondBoundary.payload.partial)
            assertEquals(
                HubLatestSnapshotsQueryService.ERROR_SNAPSHOT_STALE,
                justBeyondBoundary.payload.errors.single().code,
            )
        }

    @Test
    fun `active missing and current error nodes make aggregate partial while disabled is ignored`() =
        withHubStore { snapshots, registry, runner ->
            val now = Instant.parse("2026-08-15T12:00:00Z")
            val currentErrorWithSnapshot = testNodeId(201)
            val currentErrorWithoutSnapshot = testNodeId(202)
            val missingNode = testNodeId(203)
            val disabledNode = testNodeId(204)

            runner.readWrite {
                listOf(
                    currentErrorWithSnapshot,
                    currentErrorWithoutSnapshot,
                    missingNode,
                    disabledNode,
                ).forEachIndexed { index, nodeId ->
                    registry.activate(
                        NodeAgentRegistration(nodeId, "node-$index", 60, now.minusSeconds(60)),
                    )
                }
                snapshots.insert(
                    testSnapshotRecord(
                        snapshot = testDiskSnapshot("device-a", timestampMillis = 1_000),
                        nodeId = currentErrorWithSnapshot,
                        receivedAt = now,
                    ),
                )
                registry.recordFailure(
                    currentErrorWithSnapshot,
                    NodeAgentFailure("secret_backend_code", "sensitive backend detail", now),
                )
                registry.recordFailure(
                    currentErrorWithoutSnapshot,
                    NodeAgentFailure("another_secret", "another sensitive detail", now),
                )
                registry.disable(disabledNode)
            }

            val result = service(snapshots, registry, runner, now)
                .findLatestPage(LatestSnapshotPageRequest(limit = 10))
            val limitedResult = HubLatestSnapshotsQueryService(
                snapshotRepository = snapshots,
                registryRepository = registry,
                transactionRunner = runner,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                errorLimit = 2,
            ).findLatestPage(LatestSnapshotPageRequest(limit = 10))

            assertEquals(listOf(currentErrorWithSnapshot.toString()), result.payload.nodes.map { it.nodeId })
            assertTrue(result.payload.partial)
            assertTrue(result.payload.errors.any {
                it.code == HubLatestSnapshotsQueryService.ERROR_SNAPSHOT_MISSING &&
                    it.nodeId == missingNode.toString()
            })
            assertTrue(result.payload.errors.all { "sensitive" !in it.message })
            assertTrue(result.payload.errors.all {
                it.nodeId != disabledNode.toString()
            })
            assertEquals(2, limitedResult.payload.errors.size)
            assertTrue(limitedResult.payload.errorsTruncated)
        }

    @Test
    fun `disabled node direct latest and history keep registry authority and latest freshness`() =
        withHubStore { snapshots, registry, runner ->
            val now = Instant.parse("2026-08-15T12:00:00Z")
            val nodeId = testNodeId(301)
            val unknownNodeId = testNodeId(302)
            val olderCollectTime = now.minusSeconds(600)
            val latestCollectTime = now.minusSeconds(60)

            runner.readWrite {
                registry.activate(NodeAgentRegistration(nodeId, "current-name", 60, now.minusSeconds(1_000)))
                snapshots.insert(
                    testSnapshotRecord(
                        snapshot = testDiskSnapshot(
                            "device-a",
                            timestampMillis = olderCollectTime.toEpochMilli(),
                            temperatureCelsius = 30,
                        ),
                        nodeId = nodeId,
                        nodeName = "historical-name",
                        receivedAt = now.minusSeconds(500),
                    ),
                )
                snapshots.insert(
                    testSnapshotRecord(
                        snapshot = testDiskSnapshot(
                            "device-a",
                            timestampMillis = latestCollectTime.toEpochMilli(),
                            temperatureCelsius = 31,
                        ),
                        nodeId = nodeId,
                        nodeName = "historical-name",
                        receivedAt = now.minusSeconds(30),
                    ),
                )
                registry.disable(nodeId)
            }
            val service = service(snapshots, registry, runner, now)

            val latest = assertNotNull(service.findNodeLatest(nodeId, "device-a"))
            val history = assertNotNull(
                service.findNodeHistory(
                    nodeId,
                    "device-a",
                    HistoryQuery(
                        limit = 10,
                        to = OffsetDateTime.ofInstant(olderCollectTime, ZoneOffset.UTC),
                    ),
                ),
            )

            assertEquals(31, latest.snapshot?.temperatureCelsius)
            assertEquals(NodeStatus.DISABLED, latest.freshness.status)
            assertEquals("current-name", latest.freshness.nodeName)
            assertEquals(30_000, latest.freshness.deviceState.ageMs)
            assertEquals("current-name", history.payload.nodeName)
            assertEquals(listOf(30), history.payload.snapshots.map { it.temperatureCelsius })
            assertEquals(30_000, history.freshness.deviceState.ageMs)
            assertNull(service.findNodeLatest(unknownNodeId, "device-a"))
        }

    private fun service(
        snapshots: ExposedDiskSnapshotRepository,
        registry: ExposedNodeAgentRegistryRepository,
        runner: ExposedTransactionRunner,
        now: Instant,
    ): HubLatestSnapshotsQueryService = HubLatestSnapshotsQueryService(
        snapshotRepository = snapshots,
        registryRepository = registry,
        transactionRunner = runner,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun withHubStore(
        block: suspend (
            ExposedDiskSnapshotRepository,
            ExposedNodeAgentRegistryRepository,
            ExposedTransactionRunner,
        ) -> Unit,
    ) = runBlocking {
        val databaseFile = createTempFile()
        val storage = StorageSettings.fromJdbcUrl("jdbc:sqlite:${databaseFile.absolutePathString()}")
        createStorageMigratorFactory().create(storage).migrate(storage)

        StorageConnectionFactory.connect(storage).use { connection ->
            block(
                ExposedDiskSnapshotRepository(),
                ExposedNodeAgentRegistryRepository(),
                ExposedTransactionRunner(connection.database),
            )
        }
    }

    private fun kotlin.time.Instant.toJavaInstant(): Instant =
        Instant.ofEpochMilli(toEpochMilliseconds())
}
