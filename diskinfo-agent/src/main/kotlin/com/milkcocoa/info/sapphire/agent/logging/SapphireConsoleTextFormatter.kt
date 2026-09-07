package com.milkcocoa.info.sapphire.agent.logging

import com.milkcocoa.info.colotok.core.formatter.details.Formatter
import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.colotok.core.logger.LogRecord
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

/**
 * Human-oriented formatter for agent console output.
 *
 * Disk snapshots are emitted as their own text representation so they can be copied
 * or parsed without a log-level prefix; ordinary records retain the level marker.
 */
object SapphireConsoleTextFormatter : Formatter {
    /** Prefixes plain log messages with their severity. */
    override fun format(record: LogRecord.PlainText): String {
        return "[${record.level.name}] ${record.msg}"
    }

    /** Formats snapshots specially while preserving severity for other structures. */
    override fun <T : LogStructure> format(record: LogRecord.StructuredText<T>): String {
        val text = record.msg.stringify()
        return if (record.msg is DiskSnapshot) {
            text
        } else {
            "[${record.level.name}] $text"
        }
    }

    /** Prefixes metric records with severity using the same convention as plain text. */
    override fun format(record: LogRecord.Metrics): String {
        return "[${record.level.name}] ${record.msg}"
    }
}
