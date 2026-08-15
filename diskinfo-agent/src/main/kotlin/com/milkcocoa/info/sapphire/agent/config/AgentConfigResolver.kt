package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.agent.OutputMode
import com.milkcocoa.info.sapphire.agent.TargetDevice
import com.milkcocoa.info.sapphire.agent.datastore.StorageBackend
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import java.net.URI
import java.nio.file.Paths

class AgentConfigValidationException(message: String) : IllegalArgumentException(message)

data class AgentConfigOverrides(
    val scan: Boolean? = null,
    val device: String? = null,
    val outputMode: OutputMode? = null,
    val persist: Boolean? = null,
    val intervalSeconds: Long? = null,
    val jdbcUrl: String? = null,
    val storageType: String? = null,
    val storageUsername: String? = null,
    val storagePassword: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val rawSnapshotDays: Int? = null,
    val vacuumAfterCleanup: Boolean? = null,
    val publicEndpointBaseUrl: String? = null,
    val publicEndpointAllowInsecureTransport: Boolean? = null,
    val hubEndpoint: String? = null,
    val hubAllowInsecureTransport: Boolean? = null,
    val credentialFile: String? = null,
    val pemCaFile: String? = null,
    val heartbeatIntervalSeconds: Long? = null,
    val requestTimeoutSeconds: Long? = null,
    val maxRetries: Int? = null,
    val nonceTtlSeconds: Long? = null,
    val maxRequestBodyBytes: Long? = null,
)

data class EffectiveOneshotConfig(
    val target: TargetDevice,
    val outputMode: OutputMode,
    val persist: Boolean,
    val storage: StorageSettings,
    val deviceIdentityNamespaceSalt: String,
) {
    val jdbcUrl: String
        get() = storage.jdbcUrl
}

data class EffectiveStandaloneConfig(
    val target: TargetDevice,
    val outputMode: OutputMode,
    val intervalSeconds: Long,
    val storage: StorageSettings,
    val host: String,
    val port: Int,
    val deviceIdentityNamespaceSalt: String,
    val rawSnapshotDays: Int,
    val cleanupOnStartup: Boolean,
    val cleanupIntervalHours: Long,
    val vacuumAfterCleanup: Boolean,
) {
    val jdbcUrl: String
        get() = storage.jdbcUrl
}

data class EffectiveDbMigrateConfig(
    val storage: StorageSettings,
) {
    val jdbcUrl: String
        get() = storage.jdbcUrl
}

data class EffectiveDbCleanupConfig(
    val storage: StorageSettings,
    val rawSnapshotDays: Int,
    val vacuumAfterCleanup: Boolean,
) {
    val jdbcUrl: String
        get() = storage.jdbcUrl
}

data class EffectiveHubConfig(
    val storage: StorageSettings,
    val host: String,
    val port: Int,
    val publicEndpointBaseUrl: String,
    val publicEndpointAllowInsecureTransport: Boolean,
    val nonceTtlSeconds: Long,
    val maxRequestBodyBytes: Long,
    val rawSnapshotDays: Int,
    val cleanupOnStartup: Boolean,
    val cleanupIntervalHours: Long,
    val vacuumAfterCleanup: Boolean,
)

enum class BootstrapTokenHostMode {
    HUB,
    STANDALONE,
}

data class EffectiveBootstrapTokenConfig(
    val storage: StorageSettings,
    val publicEndpointBaseUrl: String,
    val publicEndpointAllowInsecureTransport: Boolean,
)

data class EffectiveNodeAgentConfig(
    val target: TargetDevice,
    val outputMode: OutputMode,
    val intervalSeconds: Long,
    val hubEndpoint: String,
    val hubAllowInsecureTransport: Boolean,
    val credentialFile: String,
    val pemCaFile: String?,
    val heartbeatIntervalSeconds: Long,
    val requestTimeoutSeconds: Long,
    val maxRetries: Int,
    val deviceIdentityNamespaceSalt: String,
)

data class EffectiveNodeAgentJoinConfig(
    val hubEndpoint: String,
    val hubAllowInsecureTransport: Boolean,
    val credentialFile: String,
    val pemCaFile: String?,
    val requestTimeoutSeconds: Long,
)

object AgentConfigResolver {
    fun resolveOneshot(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveOneshotConfig {
        val scan = cli.scan
            ?: environment.boolean("COCOADISKINFO_AGENT_SMARTCTL_SCAN")
            ?: config.smartctl.scan
        val device = firstString(
            "[smartctl].device",
            cli.device,
            environment.string("COCOADISKINFO_AGENT_SMARTCTL_DEVICE"),
            config.smartctl.device,
        )
        val outputMode = resolveOutputMode(
            cli.outputMode?.name
                ?: firstString(
                    "[output].mode",
                    environment.string("COCOADISKINFO_AGENT_OUTPUT_MODE"),
                    config.output.mode,
                ),
        )
        val persist = cli.persist
            ?: environment.boolean("COCOADISKINFO_AGENT_RUNTIME_PERSIST")
            ?: config.runtime.persist
            ?: false
        val storage = resolveStorageSettings(config, environment, cli)
        val deviceIdentityNamespaceSalt = resolveDeviceIdentityNamespaceSalt(config, environment)

        return EffectiveOneshotConfig(
            target = requireTarget(scan == true, device),
            outputMode = outputMode,
            persist = persist,
            storage = storage,
            deviceIdentityNamespaceSalt = deviceIdentityNamespaceSalt,
        )
    }

    fun resolveStandalone(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveStandaloneConfig {
        val scan = cli.scan
            ?: environment.boolean("COCOADISKINFO_AGENT_SMARTCTL_SCAN")
            ?: config.smartctl.scan
        val device = firstString(
            "[smartctl].device",
            cli.device,
            environment.string("COCOADISKINFO_AGENT_SMARTCTL_DEVICE"),
            config.smartctl.device,
        )
        val outputMode = resolveOutputMode(
            cli.outputMode?.name
                ?: firstString(
                    "[output].mode",
                    environment.string("COCOADISKINFO_AGENT_OUTPUT_MODE"),
                    config.output.mode,
                ),
        )
        val intervalSeconds = cli.intervalSeconds
            ?: environment.long("COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS")
            ?: config.runtime.intervalSeconds
            ?: AgentConfigDefaults.COLLECTION_INTERVAL_SECONDS
        val storage = resolveStorageSettings(config, environment, cli)
        val host = firstString(
            "[http].host",
            cli.host,
            environment.string("COCOADISKINFO_AGENT_HTTP_HOST"),
            config.http.host,
        ) ?: AgentConfigDefaults.HTTP_HOST
        val port = cli.port
            ?: environment.int("COCOADISKINFO_AGENT_HTTP_PORT")
            ?: config.http.port
            ?: AgentConfigDefaults.HTTP_PORT
        val deviceIdentityNamespaceSalt = resolveDeviceIdentityNamespaceSalt(config, environment)
        val rawSnapshotDays = environment.int("COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS")
            ?: config.retention.rawSnapshotDays
            ?: AgentConfigDefaults.DEFAULT_RAW_SNAPSHOT_DAYS
        val cleanupOnStartup = environment.boolean("COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_ON_STARTUP")
            ?: config.maintenance.cleanupOnStartup
            ?: AgentConfigDefaults.DEFAULT_CLEANUP_ON_STARTUP
        val cleanupIntervalHours = environment.long("COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_INTERVAL_HOURS")
            ?: config.maintenance.cleanupIntervalHours
            ?: AgentConfigDefaults.DEFAULT_CLEANUP_INTERVAL_HOURS
        val vacuumAfterCleanup = environment.boolean("COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP")
            ?: config.maintenance.vacuumAfterCleanup
            ?: AgentConfigDefaults.DEFAULT_VACUUM_AFTER_CLEANUP

        validateInterval(intervalSeconds)
        validatePort(port)
        validateRawSnapshotDays(rawSnapshotDays)
        validateCleanupInterval(cleanupIntervalHours)

        return EffectiveStandaloneConfig(
            target = requireTarget(scan == true, device),
            outputMode = outputMode,
            intervalSeconds = intervalSeconds,
            storage = storage,
            host = host,
            port = port,
            deviceIdentityNamespaceSalt = deviceIdentityNamespaceSalt,
            rawSnapshotDays = rawSnapshotDays,
            cleanupOnStartup = cleanupOnStartup,
            cleanupIntervalHours = cleanupIntervalHours,
            vacuumAfterCleanup = vacuumAfterCleanup,
        )
    }

    fun resolveDbMigrate(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveDbMigrateConfig {
        return EffectiveDbMigrateConfig(
            storage = resolveStorageSettings(config, environment, cli),
        )
    }

    fun resolveDbCleanup(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveDbCleanupConfig {
        val rawSnapshotDays = listOfNotNull(
            cli.rawSnapshotDays,
            environment.int("COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS"),
            config.retention.rawSnapshotDays,
        ).firstOrNull() ?: AgentConfigDefaults.DEFAULT_RAW_SNAPSHOT_DAYS
        validateRawSnapshotDays(rawSnapshotDays)

        return EffectiveDbCleanupConfig(
            storage = resolveStorageSettings(config, environment, cli),
            rawSnapshotDays = rawSnapshotDays,
            vacuumAfterCleanup = listOfNotNull(
                cli.vacuumAfterCleanup,
                environment.boolean("COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP"),
                config.maintenance.vacuumAfterCleanup,
            ).firstOrNull() ?: AgentConfigDefaults.DEFAULT_VACUUM_AFTER_CLEANUP,
        )
    }

    fun resolveHub(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveHubConfig {
        val host = firstString(
            "[http].host",
            cli.host,
            environment.string("COCOADISKINFO_AGENT_HTTP_HOST"),
            config.http.host,
        ) ?: AgentConfigDefaults.HTTP_HOST
        val port = cli.port
            ?: environment.int("COCOADISKINFO_AGENT_HTTP_PORT")
            ?: config.http.port
            ?: AgentConfigDefaults.HTTP_PORT
        val publicEndpoint = resolvePublicEndpoint(config, environment, cli, requiredFor = "hub mode")
        val nonceTtl = cli.nonceTtlSeconds
            ?: environment.long("COCOADISKINFO_AGENT_AUTH_NONCE_TTL_SECONDS")
            ?: config.auth.nonceTtlSeconds
            ?: AgentConfigDefaults.NONCE_TTL_SECONDS
        val maxBodyBytes = cli.maxRequestBodyBytes
            ?: environment.long("COCOADISKINFO_AGENT_AUTH_MAX_REQUEST_BODY_BYTES")
            ?: config.auth.maxRequestBodyBytes
            ?: AgentConfigDefaults.MAX_REQUEST_BODY_BYTES
        val rawSnapshotDays = environment.int("COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS")
            ?: config.retention.rawSnapshotDays
            ?: AgentConfigDefaults.DEFAULT_RAW_SNAPSHOT_DAYS
        val cleanupOnStartup = environment.boolean("COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_ON_STARTUP")
            ?: config.maintenance.cleanupOnStartup
            ?: AgentConfigDefaults.DEFAULT_CLEANUP_ON_STARTUP
        val cleanupIntervalHours = environment.long("COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_INTERVAL_HOURS")
            ?: config.maintenance.cleanupIntervalHours
            ?: AgentConfigDefaults.DEFAULT_CLEANUP_INTERVAL_HOURS
        val vacuumAfterCleanup = environment.boolean("COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP")
            ?: config.maintenance.vacuumAfterCleanup
            ?: AgentConfigDefaults.DEFAULT_VACUUM_AFTER_CLEANUP

        validatePort(port)
        validateNonceTtl(nonceTtl)
        validatePositive("[auth].maxRequestBodyBytes", maxBodyBytes)
        validateRawSnapshotDays(rawSnapshotDays)
        validateCleanupInterval(cleanupIntervalHours)

        return EffectiveHubConfig(
            storage = resolveRequiredStorageSettings(config, environment, cli),
            host = host,
            port = port,
            publicEndpointBaseUrl = publicEndpoint.baseUrl,
            publicEndpointAllowInsecureTransport = publicEndpoint.allowInsecureTransport,
            nonceTtlSeconds = nonceTtl,
            maxRequestBodyBytes = maxBodyBytes,
            rawSnapshotDays = rawSnapshotDays,
            cleanupOnStartup = cleanupOnStartup,
            cleanupIntervalHours = cleanupIntervalHours,
            vacuumAfterCleanup = vacuumAfterCleanup,
        )
    }

    fun resolveBootstrapToken(
        hostMode: BootstrapTokenHostMode,
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveBootstrapTokenConfig {
        val requiredFor = when (hostMode) {
            BootstrapTokenHostMode.HUB -> "hub token creation"
            BootstrapTokenHostMode.STANDALONE -> "standalone client pairing token creation"
        }
        val publicEndpoint = resolvePublicEndpoint(config, environment, cli, requiredFor)
        val storage = when (hostMode) {
            // A central Hub must never silently choose a working-directory SQLite file.
            BootstrapTokenHostMode.HUB -> resolveRequiredStorageSettings(config, environment, cli)
            // Standalone uses the same local storage default as its runtime and db commands.
            BootstrapTokenHostMode.STANDALONE -> resolveStorageSettings(config, environment, cli)
        }
        return EffectiveBootstrapTokenConfig(
            storage = storage,
            publicEndpointBaseUrl = publicEndpoint.baseUrl,
            publicEndpointAllowInsecureTransport = publicEndpoint.allowInsecureTransport,
        )
    }

    fun resolveNodeAgent(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveNodeAgentConfig {
        val scan = cli.scan
            ?: environment.boolean("COCOADISKINFO_AGENT_SMARTCTL_SCAN")
            ?: config.smartctl.scan
        val device = firstString(
            "[smartctl].device",
            cli.device,
            environment.string("COCOADISKINFO_AGENT_SMARTCTL_DEVICE"),
            config.smartctl.device,
        )
        val interval = cli.intervalSeconds
            ?: environment.long("COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS")
            ?: config.runtime.intervalSeconds
            ?: AgentConfigDefaults.COLLECTION_INTERVAL_SECONDS
        val allowInsecure = cli.hubAllowInsecureTransport
            ?: environment.boolean("COCOADISKINFO_AGENT_HUB_ALLOW_INSECURE_TRANSPORT")
            ?: config.hub.allowInsecureTransport
            ?: false
        val endpoint = firstString(
            "[hub].endpoint",
            cli.hubEndpoint,
            environment.string("COCOADISKINFO_AGENT_HUB_ENDPOINT"),
            config.hub.endpoint,
        ) ?: throw AgentConfigValidationException("[hub].endpoint is required for node-agent mode.")
        val credential = firstString(
            "[hub].credentialFile",
            cli.credentialFile,
            environment.string("COCOADISKINFO_AGENT_HUB_CREDENTIAL_FILE"),
            config.hub.credentialFile,
        ) ?: throw AgentConfigValidationException("[hub].credentialFile is required for node-agent mode.")
        val heartbeat = cli.heartbeatIntervalSeconds
            ?: environment.long("COCOADISKINFO_AGENT_HUB_HEARTBEAT_INTERVAL_SECONDS")
            ?: config.hub.heartbeatIntervalSeconds
            ?: AgentConfigDefaults.HEARTBEAT_INTERVAL_SECONDS
        val timeout = cli.requestTimeoutSeconds
            ?: environment.long("COCOADISKINFO_AGENT_HUB_REQUEST_TIMEOUT_SECONDS")
            ?: config.hub.requestTimeoutSeconds
            ?: AgentConfigDefaults.REQUEST_TIMEOUT_SECONDS
        val retries = cli.maxRetries
            ?: environment.int("COCOADISKINFO_AGENT_HUB_MAX_RETRIES")
            ?: config.hub.maxRetries
            ?: AgentConfigDefaults.MAX_DELIVERY_RETRIES

        validateInterval(interval)
        validatePublicHttpUrl("[hub].endpoint", endpoint, allowInsecure)
        validatePositive("[hub].heartbeatIntervalSeconds", heartbeat)
        validatePositive("[hub].requestTimeoutSeconds", timeout)
        if (retries !in 0..10) {
            throw AgentConfigValidationException("[hub].maxRetries must be between 0 and 10.")
        }

        return EffectiveNodeAgentConfig(
            target = requireTarget(scan == true, device),
            outputMode = resolveOutputMode(
                cli.outputMode?.name
                    ?: firstString(
                        "[output].mode",
                        environment.string("COCOADISKINFO_AGENT_OUTPUT_MODE"),
                        config.output.mode,
                    ),
            ),
            intervalSeconds = interval,
            hubEndpoint = normalizeBaseUrl(endpoint),
            hubAllowInsecureTransport = allowInsecure,
            credentialFile = credential,
            pemCaFile = firstString(
                "[hub].pemCaFile",
                cli.pemCaFile,
                environment.string("COCOADISKINFO_AGENT_HUB_PEM_CA_FILE"),
                config.hub.pemCaFile,
            ),
            heartbeatIntervalSeconds = heartbeat,
            requestTimeoutSeconds = timeout,
            maxRetries = retries,
            deviceIdentityNamespaceSalt = resolveDeviceIdentityNamespaceSalt(config, environment),
        )
    }

    fun resolveNodeAgentJoin(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveNodeAgentJoinConfig {
        val allowInsecure = cli.hubAllowInsecureTransport
            ?: environment.boolean("COCOADISKINFO_AGENT_HUB_ALLOW_INSECURE_TRANSPORT")
            ?: config.hub.allowInsecureTransport
            ?: false
        val endpoint = firstString(
            "[hub].endpoint",
            cli.hubEndpoint,
            environment.string("COCOADISKINFO_AGENT_HUB_ENDPOINT"),
            config.hub.endpoint,
        ) ?: throw AgentConfigValidationException("[hub].endpoint is required for node-agent join.")
        val credential = firstString(
            "[hub].credentialFile",
            cli.credentialFile,
            environment.string("COCOADISKINFO_AGENT_HUB_CREDENTIAL_FILE"),
            config.hub.credentialFile,
        ) ?: throw AgentConfigValidationException("[hub].credentialFile is required for node-agent join.")
        val timeout = cli.requestTimeoutSeconds
            ?: environment.long("COCOADISKINFO_AGENT_HUB_REQUEST_TIMEOUT_SECONDS")
            ?: config.hub.requestTimeoutSeconds
            ?: AgentConfigDefaults.REQUEST_TIMEOUT_SECONDS

        validatePublicHttpUrl("[hub].endpoint", endpoint, allowInsecure)
        validatePositive("[hub].requestTimeoutSeconds", timeout)
        return EffectiveNodeAgentJoinConfig(
            hubEndpoint = normalizeBaseUrl(endpoint),
            hubAllowInsecureTransport = allowInsecure,
            credentialFile = credential,
            pemCaFile = firstString(
                "[hub].pemCaFile",
                cli.pemCaFile,
                environment.string("COCOADISKINFO_AGENT_HUB_PEM_CA_FILE"),
                config.hub.pemCaFile,
            ),
            requestTimeoutSeconds = timeout,
        )
    }

    private fun resolveStorageSettings(
        config: AgentConfig,
        environment: Map<String, String>,
        cli: AgentConfigOverrides,
    ): StorageSettings {
        val jdbcUrl = firstString(
            "[storage].jdbcUrl",
            cli.jdbcUrl,
            environment.string("COCOADISKINFO_AGENT_STORAGE_JDBC_URL"),
            config.storage.jdbcUrl,
        ) ?: AgentConfigDefaults.JDBC_URL
        val inferredBackend = StorageBackend.fromJdbcUrl(jdbcUrl)
            ?: throw AgentConfigValidationException(
                "[storage].jdbcUrl must start with one of: ${
                    StorageBackend.entries.joinToString { it.jdbcPrefix }
                }.",
            )
        val explicitBackend = firstString(
            "[storage].type",
            cli.storageType,
            environment.string("COCOADISKINFO_AGENT_STORAGE_TYPE"),
            config.storage.type,
        )?.let { type ->
            StorageBackend.fromConfigValue(type)
                ?: throw AgentConfigValidationException(
                    "[storage].type must be one of: ${
                        StorageBackend.entries.joinToString { it.configValue }
                    }.",
                )
        }
        if (explicitBackend != null && explicitBackend != inferredBackend) {
            throw AgentConfigValidationException(
                "[storage].type ${explicitBackend.configValue} does not match [storage].jdbcUrl backend ${inferredBackend.configValue}.",
            )
        }

        return StorageSettings(
            backend = explicitBackend ?: inferredBackend,
            jdbcUrl = jdbcUrl,
            username = firstString(
                "[storage].username",
                cli.storageUsername,
                environment.string("COCOADISKINFO_AGENT_STORAGE_USERNAME"),
                config.storage.username,
            ),
            password = firstString(
                "[storage].password",
                cli.storagePassword,
                environment.string("COCOADISKINFO_AGENT_STORAGE_PASSWORD"),
                config.storage.password,
            ),
        )
    }

    private fun resolveRequiredStorageSettings(
        config: AgentConfig,
        environment: Map<String, String>,
        cli: AgentConfigOverrides,
    ): StorageSettings {
        if (
            cli.jdbcUrl == null &&
            environment["COCOADISKINFO_AGENT_STORAGE_JDBC_URL"] == null &&
            config.storage.jdbcUrl == null
        ) {
            throw AgentConfigValidationException("[storage].jdbcUrl is required for hub mode.")
        }
        return resolveStorageSettings(config, environment, cli)
    }

    private fun resolvePublicEndpoint(
        config: AgentConfig,
        environment: Map<String, String>,
        cli: AgentConfigOverrides,
        requiredFor: String,
    ): ResolvedPublicEndpoint {
        val allowInsecure = cli.publicEndpointAllowInsecureTransport
            ?: environment.boolean("COCOADISKINFO_AGENT_PUBLIC_ENDPOINT_ALLOW_INSECURE_TRANSPORT")
            ?: config.publicEndpoint.allowInsecureTransport
            ?: false
        val baseUrl = firstString(
            "[publicEndpoint].baseUrl",
            cli.publicEndpointBaseUrl,
            environment.string("COCOADISKINFO_AGENT_PUBLIC_ENDPOINT_BASE_URL"),
            config.publicEndpoint.baseUrl,
        ) ?: throw AgentConfigValidationException("[publicEndpoint].baseUrl is required for $requiredFor.")
        validatePublicHttpUrl("[publicEndpoint].baseUrl", baseUrl, allowInsecure)
        return ResolvedPublicEndpoint(
            baseUrl = normalizeBaseUrl(baseUrl),
            allowInsecureTransport = allowInsecure,
        )
    }

    private fun resolveDeviceIdentityNamespaceSalt(
        config: AgentConfig,
        environment: Map<String, String>,
    ): String {
        return firstString(
            "[deviceIdentity].namespaceSalt",
            environment.string("COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT"),
            config.deviceIdentity.namespaceSalt,
        ) ?: AgentConfigDefaults.DEVICE_IDENTITY_NAMESPACE_SALT
    }

    private fun requireTarget(scan: Boolean, device: String?): TargetDevice {
        if (scan && device != null) {
            throw AgentConfigValidationException("Use either scan or device, not both.")
        }
        if (scan) return TargetDevice.Scan
        return device?.let {
            TargetDevice.Explicit(Paths.get(it).toAbsolutePath().normalize().toString())
        } ?: throw AgentConfigValidationException(
            "Command requires --scan or --device, or [smartctl] scan/device in config or environment.",
        )
    }

    private fun resolveOutputMode(rawMode: String?): OutputMode {
        val mode = rawMode ?: return OutputMode.DEFAULT
        return OutputMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
            ?: throw AgentConfigValidationException(
                "[output].mode must be one of: ${OutputMode.entries.joinToString { it.name }}.",
            )
    }

    private fun validateInterval(intervalSeconds: Long) {
        if (intervalSeconds <= 0) {
            throw AgentConfigValidationException("[runtime].intervalSeconds must be greater than 0 seconds.")
        }
    }

    private fun validateRawSnapshotDays(rawSnapshotDays: Int) {
        if (rawSnapshotDays !in 1..365) {
            throw AgentConfigValidationException("[retention].rawSnapshotDays must be between 1 and 365 days.")
        }
    }

    private fun validateCleanupInterval(cleanupIntervalHours: Long) {
        if (cleanupIntervalHours <= 0) {
            throw AgentConfigValidationException(
                "[maintenance].cleanupIntervalHours must be greater than 0 hours.",
            )
        }
    }

    private fun validatePort(port: Int) {
        if (port !in 1..65535) {
            throw AgentConfigValidationException("[http].port must be between 1 and 65535.")
        }
    }

    private fun validateNonceTtl(seconds: Long) {
        if (seconds !in 10..300) {
            throw AgentConfigValidationException("[auth].nonceTtlSeconds must be between 10 and 300 seconds.")
        }
    }

    private fun validatePositive(name: String, value: Long) {
        if (value <= 0) throw AgentConfigValidationException("$name must be greater than 0.")
    }

    private fun validatePublicHttpUrl(name: String, rawUrl: String, allowInsecure: Boolean) {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: throw AgentConfigValidationException("$name must be an absolute HTTP(S) URL.")
        if (!uri.isAbsolute || uri.host.isNullOrBlank() || uri.scheme.lowercase() !in setOf("http", "https")) {
            throw AgentConfigValidationException("$name must be an absolute HTTP(S) URL.")
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path !in setOf("", "/")) {
            throw AgentConfigValidationException("$name must not contain user info, a base path, query, or fragment.")
        }
        if (uri.scheme.equals("http", ignoreCase = true) && !allowInsecure) {
            throw AgentConfigValidationException("$name uses HTTP; explicitly enable allowInsecureTransport to accept the risk.")
        }
    }

    private fun normalizeBaseUrl(rawUrl: String): String = rawUrl.trim().removeSuffix("/")

    private fun firstString(name: String, vararg values: String?): String? {
        return values.firstNotNullOfOrNull { value ->
            value?.also { requireNonBlank(name, it) }
        }
    }

    private fun requireNonBlank(name: String, value: String): String {
        if (value.isBlank()) {
            throw AgentConfigValidationException("$name must not be blank.")
        }
        return value
    }

    private fun Map<String, String>.string(name: String): String? {
        return this[name]?.also { requireNonBlank(name, it) }
    }

    private fun Map<String, String>.boolean(name: String): Boolean? {
        val value = string(name) ?: return null
        return when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw AgentConfigValidationException("$name must be true or false.")
        }
    }

    private fun Map<String, String>.long(name: String): Long? {
        val value = string(name) ?: return null
        return value.toLongOrNull()
            ?: throw AgentConfigValidationException("$name must be an integer.")
    }

    private fun Map<String, String>.int(name: String): Int? {
        val value = long(name) ?: return null
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
            throw AgentConfigValidationException("$name is outside Int range.")
        }
        return value.toInt()
    }

    private data class ResolvedPublicEndpoint(
        val baseUrl: String,
        val allowInsecureTransport: Boolean,
    )
}
