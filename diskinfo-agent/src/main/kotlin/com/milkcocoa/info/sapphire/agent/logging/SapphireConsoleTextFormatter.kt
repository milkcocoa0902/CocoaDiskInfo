package com.milkcocoa.info.sapphire.agent.logging

import com.milkcocoa.info.colotok.core.formatter.details.Formatter
import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.colotok.core.logger.LogRecord
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

object SapphireConsoleTextFormatter : Formatter {
    override fun format(record: LogRecord.PlainText): String {
        return "[${record.level.name}] ${record.msg}"
    }

    override fun <T : LogStructure> format(record: LogRecord.StructuredText<T>): String {
        val text = record.msg.stringify()
        return if (record.msg is DiskSnapshot) {
            text
        } else {
            "[${record.level.name}] $text"
        }
    }

    override fun format(record: LogRecord.Metrics): String {
        return "[${record.level.name}] ${record.msg}"
    }
}
