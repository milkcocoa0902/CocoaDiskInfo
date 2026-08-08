package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.agent.OutputMode
import com.milkcocoa.info.sapphire.agent.TargetDevice
import com.milkcocoa.info.sapphire.agent.datastore.StorageBackend
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
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
}
