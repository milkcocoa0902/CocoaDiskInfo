package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.colotok.core.provider.builtin.console.ConsoleProvider
import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommand
import com.milkcocoa.info.sapphire.agent.smartctl.model.translate
import kotlinx.coroutines.runBlocking

class SapphireAgent: CliktCommand(){
    enum class OutputMode {
        DEFAULT,
        JSON,
        TEXT,
        CBOR
    }

    val scan by option().flag(default = false).help("Scan all devices")
    val device by option().required().help("Device path to query")
    val agent by option().flag(default = false).help("Enable agent mode")
    val output by option().enum<OutputMode>(ignoreCase = true, key = {it.name}).default(OutputMode.DEFAULT).help("Output format")

    override fun run() {
        runBlocking {
            val deviceInfo = SmartCtlCommand.DeviceInfo(
                device = device,
            ).execute()
            println(deviceInfo.translate())
        }
    }
}

fun main(args: Array<String>) = SapphireAgent().main(args)