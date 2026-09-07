package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.formatter.builtin.structure.DetailStructureFormatter
import com.milkcocoa.info.colotok.core.level.LogLevel
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.colotok.core.provider.builtin.console.ConsoleProvider
import com.milkcocoa.info.sapphire.agent.logging.SapphireConsoleTextFormatter

/** Builds the process-wide console logger context for a command's output mode. */
object ColotokProviderFactory {
    /**
     * Creates a context containing the single console provider used by the agent.
     * Structured/default JSON output keeps the detail formatter; TEXT and CBOR use the
     * Sapphire formatter so snapshots remain readable.
     *
     * @param outputMode controls formatter selection; it does not change the log level.
     * @return an uninstalled logger context ready for `ColotokLoggerContext.setDefault`.
     */
    fun create(
        outputMode: OutputMode,
    ): ColotokLoggerContext {
        return ColotokLoggerContext()
            .addProvider(createConsoleProvider(outputMode))
    }

    /** Maps the public output mode to the formatter understood by the console provider. */
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
