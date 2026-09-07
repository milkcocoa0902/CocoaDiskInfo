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

/** Marker for JSON response models returned by a [SmartCtlCommand]. */
interface SmartCtlCommandResponse

/** Result of one smartctl process invocation, including parsed output and the raw exit code. */
data class SmartCtlCommandResult<R: SmartCtlCommandResponse>(
    /** Parsed JSON response. */
    val output: R,
    /** Operating-system process exit code; non-zero is not converted into an exception. */
    val exitCode: Int
)


/**
 * A typed smartctl invocation. Implementations define arguments and JSON decoding while the
 * shared executor captures merged stdout/stderr and waits for process completion.
 *
 * The exit code is returned to the caller rather than interpreted here. JSON decoding failures
 * are propagated, and process startup failures are likewise not converted into a result.
 */
sealed interface SmartCtlCommand<R: SmartCtlCommandResponse> {
    /** Executable name passed to [ProcessBuilder]. */
    val cmd: String get() = "smartctl"
    /** Arguments in process order, excluding [cmd]. */
    val cmdArgs: List<String>

    /** Dispatcher used for blocking process I/O. */
    val coroutineContext: CoroutineContext

    /** Decodes the complete smartctl output into this command's response type. */
    fun parseOutput(output: String): R

    /** Executes smartctl on the command's coroutine context and returns its parsed result. */
    suspend fun execute(): SmartCtlCommandResult<R> = withContext(coroutineContext) {
        val process = ProcessBuilder(cmd, *cmdArgs.toTypedArray())
            .redirectErrorStream(true)
            .start()

        return@withContext SmartCtlCommandResult(
            output = parseOutput(process.inputStream.bufferedReader().use { it.readText() }),
            exitCode = process.waitFor()
        )
    }


    /** `smartctl --scan --json`, returning the devices discoverable on the host. */
    data object DescribeDevices: SmartCtlCommand<SmartctlScanResponse> {
        override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()
        override val cmdArgs: List<String> = listOf("--scan", "--json")

        /** Decodes the scan response while ignoring fields newer than this model. */
        override fun parseOutput(output: String): SmartctlScanResponse {
            return json.decodeFromString(output)
        }
    }

    /**
     * `smartctl -a <device> --json`, returning a protocol-specific device snapshot.
     *
     * @property device device path or smartctl device name passed unchanged to the process
     */
    data class DeviceInfo(val device: String): SmartCtlCommand<SmartctlSnapshot> {
        override val coroutineContext: CoroutineContext = Dispatchers.IO + Job()
        override val cmdArgs: List<String> = listOf("-a", device, "--json")

        /** Selects ATA or NVMe decoding from the response's `device.protocol` field. */
        override fun parseOutput(output: String): SmartctlSnapshot {
            return json.decodeFromString(output)
        }
    }
}
