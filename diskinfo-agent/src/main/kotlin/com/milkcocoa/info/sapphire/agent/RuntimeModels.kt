package com.milkcocoa.info.sapphire.agent

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
