package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NvmeSmartHealthInformationLog(
    @SerialName("critical_warning")
    val criticalWarning: Int,
    @SerialName("temperature")
    val temperature: Int,
    @SerialName("available_spare")
    val availableSpare: Int,
    @SerialName("available_spare_threshold")
    val availableSpareThreshold: Int,
    @SerialName("percentage_used")
    val percentageUsed: Int,
    @SerialName("data_units_read")
    val dataUnitsRead: Long,
    @SerialName("data_units_written")
    val dataUnitsWritten: Long,
    @SerialName("host_reads")
    val hostReads: Long,
    @SerialName("host_writes")
    val hostWrites: Long,
    @SerialName("controller_busy_time")
    val controllerBusyTime: Long,
    @SerialName("power_cycles")
    val powerCycles: Int,
    @SerialName("power_on_hours")
    val powerOnHours: Int,
    @SerialName("unsafe_shutdowns")
    val unsafeShutdowns: Int,
    @SerialName("media_errors")
    val mediaErrors: Int,
    @SerialName("num_err_log_entries")
    val numErrLogEntries: Int,
    @SerialName("warning_temp_time")
    val warningTempTime: Int,
    @SerialName("critical_comp_time")
    val criticalCompTime: Int
)
