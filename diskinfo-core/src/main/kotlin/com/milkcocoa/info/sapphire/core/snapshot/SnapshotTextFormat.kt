package com.milkcocoa.info.sapphire.core.snapshot

import java.util.Locale

internal fun String?.orDash(): String = this ?: "-"

internal fun Int?.formatCelsiusOrDash(): String = this?.let { "$it C" } ?: "-"

internal fun Int?.formatPercentOrDash(): String = this?.let { "$it%" } ?: "-"

internal fun Long?.formatNumberOrDash(): String = this?.toString() ?: "-"

internal fun Long?.formatBytesOrDash(): String = this?.formatBytes() ?: "-"

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
