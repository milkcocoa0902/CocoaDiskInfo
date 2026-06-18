package com.milkcocoa.info.sapphire.agent.sink

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

interface SnapshotSink {
    suspend fun write(snapshot: DiskSnapshot)
}

class ColotokSnapshotSink : SnapshotSink {
    override suspend fun write(snapshot: DiskSnapshot) {
        Colotok.info(snapshot)
    }
}

class RepositorySnapshotSink(
    private val repository: DiskSnapshotRepository,
) : SnapshotSink {
    override suspend fun write(snapshot: DiskSnapshot) {
        repository.insert(snapshot)
    }
}

class CompositeSnapshotSink(
    private val sinks: List<SnapshotSink>,
) : SnapshotSink {
    constructor(vararg sinks: SnapshotSink) : this(sinks.toList())

    override suspend fun write(snapshot: DiskSnapshot) {
        sinks.forEach { it.write(snapshot) }
    }
}
