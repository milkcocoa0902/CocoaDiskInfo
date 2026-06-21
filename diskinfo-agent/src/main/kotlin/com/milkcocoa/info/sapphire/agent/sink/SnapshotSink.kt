package com.milkcocoa.info.sapphire.agent.sink

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
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
    private val snapshotUseCase: SnapshotUseCase,
) : SnapshotSink {
    override suspend fun write(snapshot: DiskSnapshot) {
        runCatching {
            snapshotUseCase.saveSnapshot(snapshot)
        }.getOrElse { error ->
            Colotok.warn(
                msg = "Failed to persist disk snapshot.",
                attr = mapOf(
                    "device_key" to snapshot.deviceKey,
                    "device_path" to snapshot.path,
                    "error" to (error.message ?: error::class.simpleName.orEmpty()),
                ),
            )
        }
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
