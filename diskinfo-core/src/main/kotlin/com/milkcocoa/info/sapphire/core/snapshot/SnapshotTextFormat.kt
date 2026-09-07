package com.milkcocoa.info.sapphire.core.snapshot

import java.util.Locale

/** Formats an optional text value for diagnostics without exposing `null`. */
internal fun String?.orDash(): String = this ?: "-"

/** Formats Celsius values with a unit, using `-` when unavailable. */
internal fun Int?.formatCelsiusOrDash(): String = this?.let { "$it C" } ?: "-"

/** Formats percentage values with a percent sign, using `-` when unavailable. */
internal fun Int?.formatPercentOrDash(): String = this?.let { "$it%" } ?: "-"

/** Formats an optional count without adding a unit. */
internal fun Long?.formatNumberOrDash(): String = this?.toString() ?: "-"

/** Formats an optional byte count using IEC units, using `-` when unavailable. */
internal fun Long?.formatBytesOrDash(): String = this?.formatBytes() ?: "-"

/** Formats a byte count with binary (1024-based) IEC units and two decimals above bytes. */
internal fun Long.formatBytes(): String {
    if (this < 1024L) return "$this B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = toDouble()
    var unitIndex = -1

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return "%.2f %s".format(Locale.ROOT, value, units[unitIndex])
}
