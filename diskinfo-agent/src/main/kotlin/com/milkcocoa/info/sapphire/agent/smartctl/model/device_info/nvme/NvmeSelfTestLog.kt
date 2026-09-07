package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

/** NVMe self-test status returned by the controller. */
@Serializable
data class NvmeSelfTestLog(
    @kotlinx.serialization.SerialName("current_self_test_operation")
    val currentSelfTestOperation: CurrentSelfTestOperation
) {
    /** Current operation and its controller-defined numeric status. */
    @Serializable
    data class CurrentSelfTestOperation(
        val value: Int,
        val string: String
    )
}
