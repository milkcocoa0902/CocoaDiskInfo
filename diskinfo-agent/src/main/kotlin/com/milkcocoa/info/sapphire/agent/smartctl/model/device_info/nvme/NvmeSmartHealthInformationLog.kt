package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * NVMe SMART/health log. Data-unit counters use 512,000 bytes per unit; percentage, time,
 * temperature, and warning fields retain the units defined by the NVMe health-log schema.
 */
@Serializable
data class NvmeSmartHealthInformationLog(
    @SerialName("critical_warning")
    /** Bit mask of controller critical warnings, not a count of warnings. */
    val criticalWarning: Int,
    @SerialName("temperature")
    /** Composite temperature rendered by smartctl in degrees Celsius. */
    val temperature: Int,
    @SerialName("available_spare")
    /** Remaining spare capacity as a percentage. */
    val availableSpare: Int,
    @SerialName("available_spare_threshold")
    /** Spare-capacity percentage at which the controller raises a warning. */
    val availableSpareThreshold: Int,
    @SerialName("percentage_used")
    /** Estimated percentage of the device lifetime consumed. */
    val percentageUsed: Int,
    @SerialName("data_units_read")
    /** Host data read in units of 512,000 bytes. */
    val dataUnitsRead: Long,
    @SerialName("data_units_written")
    /** Host data written in units of 512,000 bytes. */
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
