package com.milkcocoa.info.saphire.agent.smartctl.snapshot.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smartctl")
data class Smartctl(
    @SerialName("version")
    val version: List<Int>,
    @SerialName("pre_release")
    val preRelease: Boolean,
    @SerialName("svn_revision")
    val svnRevision: String,
    @SerialName("platform_info")
    val platformInfo: String,
    @SerialName("build_info")
    val buildInfo: String,
    @SerialName("argv")
    val argv: List<String>,
    @SerialName("drive_database_version")
    val driveDatabaseVersion: DriveDatabaseVersion? = null,
    @SerialName("exit_status")
    val exitStatus: Int
)

@Serializable
@SerialName("drive_database_version")
data class DriveDatabaseVersion(
    @SerialName("string")
    val string: String
)