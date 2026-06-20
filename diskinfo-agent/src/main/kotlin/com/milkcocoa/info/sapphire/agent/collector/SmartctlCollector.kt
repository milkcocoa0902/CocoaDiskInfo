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

class SmartctlCollector(
    private val deviceKeyDeriver: DeviceKeyDeriver = UuidV5DeviceKeyDeriver(),
) : DiskSnapshotCollector {
    override suspend fun collectDevice(device: String): DiskSnapshot {
        val deviceInfo = SmartCtlCommand.DeviceInfo(
            device = device,
        ).execute()

        return deviceInfo.output.toDiskSnapshot(deviceKeyDeriver)
    }

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
