package com.milkcocoa.info.sapphire.agent.collector

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

interface DiskSnapshotCollector {
    suspend fun collectDevice(device: String): DiskSnapshot
    suspend fun scanDevices(): List<DiskSnapshot>
}
