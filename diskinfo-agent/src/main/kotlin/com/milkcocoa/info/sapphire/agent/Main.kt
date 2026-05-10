package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.colotok.core.provider.builtin.console.ConsoleProvider
import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommand
import com.milkcocoa.info.sapphire.agent.smartctl.converter.toDiskSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.translate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sun.net.util.IPAddressUtil.scan
import kotlin.io.path.absolutePathString

class SapphireAgent: CliktCommand(){
    enum class OutputMode {
        DEFAULT,
        JSON,
        TEXT,
        CBOR
    }

    sealed interface TargetDevice{
        object Scan: TargetDevice
        data class Explicit(val device: String): TargetDevice
    }

    val device: TargetDevice by mutuallyExclusiveOptions(
        option1 = option("--scan").flag(default = false).help("Scan all devices").convert { TargetDevice.Scan },
        option2 = option("--device").path(
            mustExist = true,
            canBeFile = true,
            canBeDir = false,
            mustBeWritable = false,
            mustBeReadable = true,
            canBeSymlink = true
        ).help("Device path to query").convert { TargetDevice.Explicit(it.absolutePathString()) }
    ).single().required()

    val agent by option().flag(default = false).help("Enable agent mode")
    val output by option().enum<OutputMode>(ignoreCase = true, key = {it.name}).default(OutputMode.DEFAULT).help("Output format")

    override fun run() {
        if(agent) return

        when(device){
            is TargetDevice.Explicit -> {
                runBlocking {
                    val deviceInfo = SmartCtlCommand.DeviceInfo(
                        device = (device as TargetDevice.Explicit).device,
                    ).execute()

                    val snapshot = deviceInfo.translate()
                    val diskSnapshot = snapshot.toDiskSnapshot()

                    when(output) {
                        OutputMode.DEFAULT -> println(diskSnapshot)
                        OutputMode.JSON -> {
                            val json = Json { prettyPrint = true }
                            println(json.encodeToString(diskSnapshot))
                        }
                        OutputMode.TEXT -> println(diskSnapshot.toString())
                        OutputMode.CBOR -> {
                            val cbor = Cbor { }
                            println(cbor.encodeToHexString(diskSnapshot))
                        }
                    }
                }
            }
            is TargetDevice.Scan -> {

            }
        }

    }
}

fun main(args: Array<String>) = SapphireAgent().main(args)