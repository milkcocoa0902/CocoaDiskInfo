package com.milkcocoa.info.sapphire.core.snapshot

/** Storage protocol that produced a metrics snapshot. */
enum class Protocol {
    /** ATA/SATA SMART attributes. */
    ATA,
    /** NVMe health log metrics. */
    NVME
}
