package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * smartctl build and invocation metadata included in every JSON response. [exitStatus] is the
 * tool's own status bitmask and is distinct from the operating-system process exit code returned
 * by the command execution result. The drive-database version is optional in smartctl output.
 */
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

/** Optional drive-database version used by the smartctl build. */
@Serializable
@SerialName("drive_database_version")
data class DriveDatabaseVersion(
    @SerialName("string")
    val string: String
)
