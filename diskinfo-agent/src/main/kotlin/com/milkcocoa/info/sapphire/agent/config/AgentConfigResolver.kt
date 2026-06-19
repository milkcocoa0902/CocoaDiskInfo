package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.agent.OutputMode
import com.milkcocoa.info.sapphire.agent.TargetDevice
import java.nio.file.Paths

class AgentConfigValidationException(message: String) : IllegalArgumentException(message)

data class AgentConfigOverrides(
    val scan: Boolean? = null,
    val device: String? = null,
    val outputMode: OutputMode? = null,
    val persist: Boolean? = null,
    val intervalSeconds: Long? = null,
    val jdbcUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
)

data class EffectiveOneshotConfig(
    val target: TargetDevice,
    val outputMode: OutputMode,
    val persist: Boolean,
    val jdbcUrl: String,
)

data class EffectiveStandaloneConfig(
    val target: TargetDevice,
    val outputMode: OutputMode,
    val intervalSeconds: Long,
    val jdbcUrl: String,
    val host: String,
    val port: Int,
)

data class EffectiveDbMigrateConfig(
    val jdbcUrl: String,
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
        val jdbcUrl = resolveJdbcUrl(config, environment, cli)

        return EffectiveOneshotConfig(
            target = requireTarget(scan == true, device),
            outputMode = outputMode,
            persist = persist,
            jdbcUrl = jdbcUrl,
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
        val jdbcUrl = resolveJdbcUrl(config, environment, cli)
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

        validateInterval(intervalSeconds)
        validatePort(port)

        return EffectiveStandaloneConfig(
            target = requireTarget(scan == true, device),
            outputMode = outputMode,
            intervalSeconds = intervalSeconds,
            jdbcUrl = jdbcUrl,
            host = host,
            port = port,
        )
    }

    fun resolveDbMigrate(
        config: AgentConfig,
        environment: Map<String, String> = System.getenv(),
        cli: AgentConfigOverrides = AgentConfigOverrides(),
    ): EffectiveDbMigrateConfig {
        return EffectiveDbMigrateConfig(
            jdbcUrl = resolveJdbcUrl(config, environment, cli),
        )
    }

    private fun resolveJdbcUrl(
        config: AgentConfig,
        environment: Map<String, String>,
        cli: AgentConfigOverrides,
    ): String {
        return firstString(
            "[storage].jdbcUrl",
            cli.jdbcUrl,
            environment.string("COCOADISKINFO_AGENT_STORAGE_JDBC_URL"),
            config.storage.jdbcUrl,
        ) ?: AgentConfigDefaults.JDBC_URL
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
