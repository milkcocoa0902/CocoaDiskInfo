package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtaSctCapabilities(
    val value: Int,
    @SerialName("error_recovery_control_supported")
    val errorRecoveryControlSupported: Boolean,
    @SerialName("feature_control_supported")
    val featureControlSupported: Boolean,
    @SerialName("data_table_supported")
    val dataTableSupported: Boolean
)
