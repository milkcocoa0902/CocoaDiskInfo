package com.milkcocoa.info.sapphire.agent.sink

import com.milkcocoa.info.sapphire.agent.server.SnapshotIngestRequest
import com.milkcocoa.info.sapphire.agent.transport.SignedNodeAgentTransport
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.util.UUID

/**
 * Sends raw observations to a remote Hub through signed transport.
 *
 * One ingest id is allocated per [write] and remains fixed for transport retries, so response loss
 * converges on Hub-side idempotency instead of creating another stored snapshot. Transport errors
 * are propagated to the caller; retry/failure classification belongs to the transport/use case.
 */
class RemoteSnapshotSink(
    private val transport: SignedNodeAgentTransport,
    private val nextIngestId: () -> String = { UUID.randomUUID().toString() },
) : SnapshotSink {
    /** Sends [snapshot] with one retry-stable ingest identity. */
    override suspend fun write(snapshot: DiskSnapshot) {
        // The id is allocated outside the transport retry loop so a response loss
        // converges on Hub idempotency instead of creating another stored row.
        val request = SnapshotIngestRequest(
            ingestId = nextIngestId(),
            snapshot = snapshot,
        )
        transport.ingest(request)
    }
}
