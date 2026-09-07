package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.agent.identity.DeviceIdentityDefaults

/** Raw TOML configuration values; nullable fields preserve whether a source supplied a value. */
data class AgentConfig(
    val smartctl: SmartctlConfig = SmartctlConfig(),
    val deviceIdentity: DeviceIdentityConfig = DeviceIdentityConfig(),
    val output: OutputConfig = OutputConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    val storage: StorageConfig = StorageConfig(),
    val http: HttpConfig = HttpConfig(),
    val publicEndpoint: PublicEndpointConfig = PublicEndpointConfig(),
    val hub: HubConfig = HubConfig(),
    val auth: AuthConfig = AuthConfig(),
    val retention: RetentionConfig = RetentionConfig(),
    val maintenance: MaintenanceConfig = MaintenanceConfig(),
    val health: HealthConfig = HealthConfig(),
) {
    /** Device discovery and explicit-device settings consumed by collection commands. */
    data class SmartctlConfig(
        val scan: Boolean? = null,
        val device: String? = null,
    )

    /** Namespace input for stable device-key derivation. */
    data class DeviceIdentityConfig(
        val namespaceSalt: String? = null,
    )

    /** Console output mode name, resolved to the runtime output enum by the resolver. */
    data class OutputConfig(
        val mode: String? = null,
    )

    /** Collection cadence and oneshot persistence preferences. */
    data class RuntimeConfig(
        val persist: Boolean? = null,
        val intervalSeconds: Long? = null,
    )

    /** Storage backend and optional JDBC credentials. */
    data class StorageConfig(
        val type: String? = null,
        val jdbcUrl: String? = null,
        val username: String? = null,
        val password: String? = null,
    )

    /** Listener address for the local HTTP API. */
    data class HttpConfig(
        val host: String? = null,
        val port: Int? = null,
    )

    /** Operator-facing endpoint advertised in bootstrap material. */
    data class PublicEndpointConfig(
        val baseUrl: String? = null,
        val allowInsecureTransport: Boolean? = null,
    )

    /** Hub endpoint, credential, transport, and delivery timing settings. */
    data class HubConfig(
        val endpoint: String? = null,
        val allowInsecureTransport: Boolean? = null,
        val credentialFile: String? = null,
        val pemCaFile: String? = null,
        val heartbeatIntervalSeconds: Long? = null,
        val requestTimeoutSeconds: Long? = null,
        val maxRetries: Int? = null,
    )

    /** Authentication lifetime and request-size limits for served APIs. */
    data class AuthConfig(
        val nonceTtlSeconds: Long? = null,
        val maxRequestBodyBytes: Long? = null,
    )

    /** Raw snapshot retention policy in days. */
    data class RetentionConfig(
        val rawSnapshotDays: Int? = null,
    )

    /** Startup and periodic cleanup behavior for long-running modes. */
    data class MaintenanceConfig(
        val cleanupOnStartup: Boolean? = null,
        val cleanupIntervalHours: Long? = null,
        val vacuumAfterCleanup: Boolean? = null,
    )

    /** Stable name of the health policy selected for evaluation. */
    data class HealthConfig(
        val policy: String? = null,
    )
}

/** Defaults applied only after CLI, environment, and file sources are absent. */
object AgentConfigDefaults {
    const val DEFAULT_CONFIG_PATH = "/etc/cocoadiskinfo/agent.toml"
    const val JDBC_URL = "jdbc:sqlite:./sapphire.db"
    const val HTTP_HOST = "127.0.0.1"
    const val HTTP_PORT = 14631
    const val COLLECTION_INTERVAL_SECONDS = 60L
    const val HEARTBEAT_INTERVAL_SECONDS = 60L
    const val REQUEST_TIMEOUT_SECONDS = 30L
    const val MAX_DELIVERY_RETRIES = 2
    const val NONCE_TTL_SECONDS = 60L
    const val MAX_REQUEST_BODY_BYTES = 2L * 1024L * 1024L
    const val DEVICE_IDENTITY_NAMESPACE_SALT = DeviceIdentityDefaults.NAMESPACE_SALT

    const val DEFAULT_RAW_SNAPSHOT_DAYS = 30
    const val DEFAULT_CLEANUP_ON_STARTUP = true
    const val DEFAULT_CLEANUP_INTERVAL_HOURS = 24L
    const val DEFAULT_VACUUM_AFTER_CLEANUP = false
    const val DEFAULT_HEALTH_POLICY = "default"
}
