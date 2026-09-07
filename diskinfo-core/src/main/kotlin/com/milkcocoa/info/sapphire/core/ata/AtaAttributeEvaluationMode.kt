package com.milkcocoa.info.sapphire.core.ata

import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus

/** The interpretation contract used to evaluate one observed ATA SMART attribute. */
sealed interface AtaAttributeEvaluationMode {
    /** Compares normalized value and worst value with the device-reported threshold. */
    data object NormalizedThreshold : AtaAttributeEvaluationMode

    /** Treats [maximum] as an inclusive normal upper bound for the raw counter. */
    data class RawMaximum(
        /** Largest acceptable raw value; values above it are [AttributeStatus.BAD]. */
        val maximum: Long,
    ) : AtaAttributeEvaluationMode

    /** Treats a normalized value as remaining endurance, where lower values are worse. */
    data class RemainingPercentage(
        /** Value at or below which the attribute is BAD, in percentage points. */
        val badAtOrBelow: Long,
        /** Value at or below which the attribute is CAUTION, in percentage points. */
        val cautionAtOrBelow: Long,
    ) : AtaAttributeEvaluationMode {
        init {
            require(badAtOrBelow <= cautionAtOrBelow) {
                "The BAD remaining percentage threshold must not exceed the CAUTION threshold."
            }
        }
    }
}
