package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.default
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.sapphire.agent.exec.SapphireExecutor
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.io.path.absolutePathString

class SapphireAgent : CliktCommand() {
    enum class OutputMode {
        DEFAULT,
        JSON,
        TEXT,
        CBOR,
    }

    sealed interface TargetDevice {
        data object Scan : TargetDevice
        data class Explicit(val device: String) : TargetDevice
    }

    sealed interface ExecutionMode {
        data object Agent : ExecutionMode
        data object Oneshot : ExecutionMode
        data object Migration : ExecutionMode
    }

    val device: TargetDevice? by mutuallyExclusiveOptions(
        option1 = option("--scan").flag(default = false).help("Scan all devices").convert { TargetDevice.Scan },
        option2 = option("--device").path(
            mustExist = true,
            canBeFile = true,
            canBeDir = false,
            mustBeWritable = false,
            mustBeReadable = true,
            canBeSymlink = true,
        ).help("Device path to query").convert { TargetDevice.Explicit(it.absolutePathString()) },
    ).single()

    val executionMode by mutuallyExclusiveOptions(
        option("--agent").flag(default = false).help("Run as agent").convert { ExecutionMode.Agent },
        option("--oneshot").flag(default = false).help("Run as oneshot").convert { ExecutionMode.Oneshot },
        option("--migration").flag(default = false).help("Run as migration").convert { ExecutionMode.Migration },
    ).default(ExecutionMode.Oneshot)

    val output by option("--output").enum<OutputMode>(ignoreCase = true, key = { it.name }).help("Output format")
    val persist by option("--persist").flag(default = false).help("Persist SMART snapshot to SQLite (oneshot only)")

    override fun run() {
        validateArguments()
        if (shouldUseDatabase()) {
            Database.connect("jdbc:sqlite:./sapphire.db", "org.sqlite.JDBC")
        }

        val effectiveOutput = when (executionMode) {
            is ExecutionMode.Agent -> OutputMode.DEFAULT
            is ExecutionMode.Oneshot -> output ?: OutputMode.DEFAULT
            is ExecutionMode.Migration -> OutputMode.DEFAULT
        }

        ColotokProviderFactory
            .create(
                executionMode = executionMode,
                outputMode = effectiveOutput,
                persist = persist,
            )
            ?.also { ColotokLoggerContext.setDefault(it) }

        val executor = when (executionMode) {
            is ExecutionMode.Agent -> SapphireExecutor.Agent(device!!)
            is ExecutionMode.Oneshot -> SapphireExecutor.Oneshot(device!!)
            is ExecutionMode.Migration -> SapphireExecutor.Migrate()
        }

        runBlocking {
            try {
                executor.execute()
            } finally {
                Colotok.forceShutdown()
            }
        }
    }

    private fun shouldUseDatabase(): Boolean {
        return when (executionMode) {
            is ExecutionMode.Agent -> true
            is ExecutionMode.Oneshot -> persist
            is ExecutionMode.Migration -> false
        }
    }

    private fun validateArguments() {
        when (executionMode) {
            is ExecutionMode.Agent -> {
                if (device == null) throw UsageError("Agent mode requires either --scan or --device.")
                if (output != null) throw UsageError("Agent mode does not allow --output.")
                if (persist) throw UsageError("Agent mode does not allow --persist.")
            }

            is ExecutionMode.Oneshot -> {
                if (device == null) throw UsageError("Oneshot mode requires either --scan or --device.")
            }

            is ExecutionMode.Migration -> {
                if (device != null || output != null || persist) {
                    throw UsageError("Migration mode does not allow --scan, --device, --output, or --persist.")
                }
            }
        }
    }
}

fun main(args: Array<String>) = SapphireAgent().main(args)
