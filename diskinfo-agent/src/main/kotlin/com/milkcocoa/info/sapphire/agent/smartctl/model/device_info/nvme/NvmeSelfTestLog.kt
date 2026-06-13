package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

@Serializable
data class NvmeSelfTestLog(
    @kotlinx.serialization.SerialName("current_self_test_operation")
    val currentSelfTestOperation: CurrentSelfTestOperation
) {
    @Serializable
    data class CurrentSelfTestOperation(
        val value: Int,
        val string: String
    )
}
