package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.formatter.builtin.structure.DetailStructureFormatter
import com.milkcocoa.info.colotok.core.formatter.builtin.text.DetailTextFormatter
import com.milkcocoa.info.colotok.core.level.LogLevel
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.colotok.core.provider.builtin.console.ConsoleProvider
import com.milkcocoa.info.sapphire.agent.datastore.DataStoreColotokProvider

object ColotokProviderFactory {
    fun create(
        executionMode: SapphireAgent.ExecutionMode,
        outputMode: SapphireAgent.OutputMode,
        persist: Boolean,
    ): ColotokLoggerContext? {
        return when (executionMode) {
            is SapphireAgent.ExecutionMode.Agent -> {
                ColotokLoggerContext()
                    .addProvider(createConsoleProvider(outputMode))
                    .addProvider(DataStoreColotokProvider())
            }

            is SapphireAgent.ExecutionMode.Oneshot -> {
                val context = ColotokLoggerContext()
                    .addProvider(createConsoleProvider(outputMode))
                if (persist) context.addProvider(DataStoreColotokProvider()) else context
            }

            is SapphireAgent.ExecutionMode.Migration -> null
        }
    }

    private fun createConsoleProvider(outputMode: SapphireAgent.OutputMode) = ConsoleProvider {
        level = LogLevel.INFO
        formatter = when (outputMode) {
            SapphireAgent.OutputMode.JSON -> DetailStructureFormatter
            SapphireAgent.OutputMode.TEXT,
            SapphireAgent.OutputMode.DEFAULT,
            SapphireAgent.OutputMode.CBOR -> DetailTextFormatter
        }
    }
}
