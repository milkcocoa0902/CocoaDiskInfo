package com.milkcocoa.info.sapphire.agent.collector

import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommand
import com.milkcocoa.info.sapphire.agent.identity.DeviceKeyDeriver
import com.milkcocoa.info.sapphire.agent.identity.UuidV5DeviceKeyDeriver
import com.milkcocoa.info.sapphire.agent.smartctl.converter.toDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Collects ATA and NVMe snapshots by invoking `smartctl` with JSON output enabled. */
class SmartctlCollector(
    private val deviceKeyDeriver: DeviceKeyDeriver = UuidV5DeviceKeyDeriver(),
) : DiskSnapshotCollector {
    /**
     * Runs `smartctl -a` for [device] and converts the protocol-specific JSON response.
     * Command exit status is retained by the command layer but is not used here; parse or
     * process failures are propagated to the caller.
     */
    override suspend fun collectDevice(device: String): DiskSnapshot {
        val deviceInfo = SmartCtlCommand.DeviceInfo(
            device = device,
        ).execute()

        return deviceInfo.output.toDiskSnapshot(deviceKeyDeriver)
    }

    /**
     * Runs `smartctl --scan --json`, collects each reported device concurrently, and drops
     * devices whose individual command or conversion fails. The returned order follows the
     * scan result order for successful entries.
     */
    override suspend fun scanDevices(): List<DiskSnapshot> = coroutineScope {
        val scanResult = SmartCtlCommand.DescribeDevices.execute()

        scanResult.output.devices
            .map { device ->
                async(Dispatchers.Default) {
                    runCatching {
                        collectDevice(device.name)
                    }.getOrNull()
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}
