package com.milkcocoa.info.sapphire.agent

/** Console representation selected by a command before the logger is installed. */
enum class OutputMode {
    DEFAULT,
    JSON,
    TEXT,
    CBOR,
}

/** Describes whether collection addresses one device or discovers all devices. */
sealed interface TargetDevice {
    /** Requests a collector scan of every device visible to the host. */
    data object Scan : TargetDevice

    /** Requests collection from the normalized path supplied by the operator. */
    data class Explicit(val device: String) : TargetDevice
}
