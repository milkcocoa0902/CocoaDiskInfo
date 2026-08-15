package com.milkcocoa.info.sapphire.agent.sink

import com.milkcocoa.info.sapphire.agent.server.SnapshotIngestRequest
import com.milkcocoa.info.sapphire.agent.transport.SignedNodeAgentTransport
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.util.UUID

class RemoteSnapshotSink(
    private val transport: SignedNodeAgentTransport,
    private val nextIngestId: () -> String = { UUID.randomUUID().toString() },
) : SnapshotSink {
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
