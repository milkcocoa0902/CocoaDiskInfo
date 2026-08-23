package com.milkcocoa.info.sapphire.core.ata

/** The interpretation contract used to evaluate one observed ATA SMART attribute. */
sealed interface AtaAttributeEvaluationMode {
    data object NormalizedThreshold : AtaAttributeEvaluationMode

    data class RawMaximum(
        val maximum: Long,
    ) : AtaAttributeEvaluationMode

    data class RemainingPercentage(
        val badAtOrBelow: Long,
        val cautionAtOrBelow: Long,
    ) : AtaAttributeEvaluationMode {
        init {
            require(badAtOrBelow <= cautionAtOrBelow) {
                "The BAD remaining percentage threshold must not exceed the CAUTION threshold."
            }
        }
    }
}
