package com.milkcocoa.info.sapphire.agent.collector

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

/**
 * Collects normalized [DiskSnapshot] values from one device or from all devices visible to the host.
 * Implementations may use a device-specific command line tool and are responsible for translating
 * its native output into the core snapshot model.
 */
interface DiskSnapshotCollector {
    /**
     * Reads one device identified by its operating-system path or command-line name.
     * Implementations normally propagate command and decoding failures to the caller.
     */
    suspend fun collectDevice(device: String): DiskSnapshot

    /**
     * Enumerates visible devices and returns the successfully collected snapshots.
     * A scan implementation may omit devices that cannot be read.
     */
    suspend fun scanDevices(): List<DiskSnapshot>
}
