package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartData(
    @SerialName("offline_data_collection")
    val offlineDataCollection: OfflineDataCollection,
    @SerialName("self_test")
    val selfTest: SelfTest,
    val capabilities: Capabilities
) {
    @Serializable
    data class OfflineDataCollection(
        val status: Status,
        @SerialName("completion_seconds")
        val completionSeconds: Int
    ) {
        @Serializable
        data class Status(
            val value: Int,
            val string: String
        )
    }

    @Serializable
    data class SelfTest(
        val status: Status,
        @SerialName("polling_minutes")
        val pollingMinutes: PollingMinutes
    ) {
        @Serializable
        data class Status(
            val value: Int,
            val string: String,
            val passed: Boolean? = null
        )

        @Serializable
        data class PollingMinutes(
            val short: Int,
            val extended: Int,
            val conveyance: Int? = null
        )
    }

    @Serializable
    data class Capabilities(
        val values: List<Int>,
        @SerialName("exec_offline_immediate_supported")
        val execOfflineImmediateSupported: Boolean,
        @SerialName("offline_is_aborted_upon_new_cmd")
        val offlineIsAbortedUponNewCmd: Boolean,
        @SerialName("offline_surface_scan_supported")
        val offlineSurfaceScanSupported: Boolean,
        @SerialName("self_tests_supported")
        val selfTestsSupported: Boolean,
        @SerialName("conveyance_self_test_supported")
        val conveyanceSelfTestSupported: Boolean,
        @SerialName("selective_self_test_supported")
        val selectiveSelfTestSupported: Boolean,
        @SerialName("attribute_autosave_enabled")
        val attributeAutosaveEnabled: Boolean,
        @SerialName("error_logging_supported")
        val errorLoggingSupported: Boolean,
        @SerialName("gp_logging_supported")
        val gpLoggingSupported: Boolean
    )
}
