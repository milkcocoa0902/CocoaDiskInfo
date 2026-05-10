package com.milkcocoa.info.sapphire.agent.smartctl.cmd

import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommand.DescribeDevices.cmdArgs
import com.milkcocoa.info.sapphire.agent.smartctl.model.SmartctlSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.scan.SmartctlScanResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

private val json = Json { ignoreUnknownKeys = true }
interface SmartCtlCommandResponse

data class SmartCtlCommandResult<R: SmartCtlCommandResponse>(
    val output: R,
    val exitCode: Int
)


sealed interface SmartCtlCommand<R: SmartCtlCommandResponse> {
    val cmd: String get() = "smartctl"
    val cmdArgs: List<String>

    val coroutineContext: CoroutineContext

    fun parseOutput(output: String): R

    suspend fun execute(): SmartCtlCommandResult<R> = withContext(coroutineContext) {
        val process = ProcessBuilder(cmd, *cmdArgs.toTypedArray())
            .redirectErrorStream(true)
            .start()

        return@withContext SmartCtlCommandResult(
            output = parseOutput(process.inputStream.bufferedReader().use { it.readText() }),
            exitCode = process.waitFor()
        )
    }


    data object DescribeDevices: SmartCtlCommand<SmartctlScanResponse> {
        override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()
        override val cmdArgs: List<String> = listOf("--scan", "--json")

        override fun parseOutput(output: String): SmartctlScanResponse {
            return json.decodeFromString(output)
        }
    }

    data class DeviceInfo(val device: String): SmartCtlCommand<SmartctlSnapshot> {
        override val coroutineContext: CoroutineContext = Dispatchers.IO + Job()
        override val cmdArgs: List<String> = listOf("-a", device, "--json")

        override fun parseOutput(output: String): SmartctlSnapshot {
            return json.decodeFromString(output)
        }
    }
}