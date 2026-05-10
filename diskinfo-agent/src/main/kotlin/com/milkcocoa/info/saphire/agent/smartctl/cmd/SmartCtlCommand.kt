package com.milkcocoa.info.saphire.agent.smartctl.cmd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext


data class SmartCtlCommandResult(
    val output: String,
    val exitCode: Int
)

sealed interface SmartCtlCommand {
    val cmd: String get() = "smartctl"
    val cmdArgs: List<String>

    val coroutineContext: CoroutineContext

    suspend fun execute(): SmartCtlCommandResult = withContext(coroutineContext) {
        val process = ProcessBuilder(cmd, *cmdArgs.toTypedArray())
            .redirectErrorStream(true)
            .start()

        return@withContext SmartCtlCommandResult(
            output = process.inputStream.bufferedReader().use { it.readText() },
            exitCode = process.waitFor()
        )
    }


    data object DescribeDevices: SmartCtlCommand {
        override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()
        override val cmdArgs: List<String> = listOf("-a", "--json")
    }

    data class DeviceInfo(val device: String): SmartCtlCommand {
        override val coroutineContext: CoroutineContext = Dispatchers.IO + Job()
        override val cmdArgs: List<String> = listOf("-a", device, "--json")
    }
}