package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.formatter.builtin.structure.DetailStructureFormatter
import com.milkcocoa.info.colotok.core.level.LogLevel
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.colotok.core.provider.builtin.console.ConsoleProvider
import com.milkcocoa.info.sapphire.agent.logging.SapphireConsoleTextFormatter

object ColotokProviderFactory {
    fun create(
        outputMode: OutputMode,
    ): ColotokLoggerContext {
        return ColotokLoggerContext()
            .addProvider(createConsoleProvider(outputMode))
    }

    private fun createConsoleProvider(outputMode: OutputMode) = ConsoleProvider {
        level = LogLevel.INFO
        formatter = when (outputMode) {
            OutputMode.DEFAULT,
            OutputMode.JSON -> DetailStructureFormatter
            OutputMode.TEXT,
            OutputMode.CBOR -> SapphireConsoleTextFormatter
        }
    }
}
