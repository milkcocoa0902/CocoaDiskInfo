package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.agent.identity.DeviceIdentityDefaults

data class AgentConfig(
    val smartctl: SmartctlConfig = SmartctlConfig(),
    val deviceIdentity: DeviceIdentityConfig = DeviceIdentityConfig(),
    val output: OutputConfig = OutputConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    val storage: StorageConfig = StorageConfig(),
    val http: HttpConfig = HttpConfig(),
    val retention: RetentionConfig = RetentionConfig(),
    val maintenance: MaintenanceConfig = MaintenanceConfig(),
) {
    data class SmartctlConfig(
        val scan: Boolean? = null,
        val device: String? = null,
    )

    data class DeviceIdentityConfig(
        val namespaceSalt: String? = null,
    )

    data class OutputConfig(
        val mode: String? = null,
    )

    data class RuntimeConfig(
        val persist: Boolean? = null,
        val intervalSeconds: Long? = null,
    )

    data class StorageConfig(
        val type: String? = null,
        val jdbcUrl: String? = null,
        val username: String? = null,
        val password: String? = null,
    )

    data class HttpConfig(
        val host: String? = null,
        val port: Int? = null,
    )

    data class RetentionConfig(
        val rawSnapshotDays: Int? = null,
    )

    data class MaintenanceConfig(
        val cleanupOnStartup: Boolean? = null,
        val cleanupIntervalHours: Long? = null,
        val vacuumAfterCleanup: Boolean? = null,
    )
}

object AgentConfigDefaults {
    const val DEFAULT_CONFIG_PATH = "/etc/cocoadiskinfo/agent.toml"
    const val JDBC_URL = "jdbc:sqlite:./sapphire.db"
    const val HTTP_HOST = "127.0.0.1"
    const val HTTP_PORT = 14631
    const val COLLECTION_INTERVAL_SECONDS = 60L
    const val DEVICE_IDENTITY_NAMESPACE_SALT = DeviceIdentityDefaults.NAMESPACE_SALT

    const val DEFAULT_RAW_SNAPSHOT_DAYS = 30
    const val DEFAULT_CLEANUP_ON_STARTUP = true
    const val DEFAULT_CLEANUP_INTERVAL_HOURS = 24L
    const val DEFAULT_VACUUM_AFTER_CLEANUP = false
}
