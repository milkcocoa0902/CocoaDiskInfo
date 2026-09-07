package com.milkcocoa.info.sapphire.agent.config

import java.nio.file.Files
import java.nio.file.Path

/** Raised when the intentionally small TOML reader cannot parse the config file. */
class AgentConfigParseException(message: String) : IllegalArgumentException(message)

/** Loads an optional TOML file and leaves source precedence to [AgentConfigResolver]. */
object AgentConfigLoader {
    /**
     * Loads [path], or the installed default file when [path] is absent and [defaultPath]
     * exists. When no default file exists, the optional source produces an empty configuration;
     * malformed files fail
     * with a path and line number through [AgentConfigParseException].
     *
     * @return raw nullable values; defaults and validation are applied later by the resolver.
     */
    fun load(
        path: Path?,
        defaultPath: Path? = Path.of(AgentConfigDefaults.DEFAULT_CONFIG_PATH),
    ): AgentConfig {
        val source = path ?: defaultPath?.takeIf { Files.isRegularFile(it) }
        if (source == null) return AgentConfig()

        val entries = MinimalTomlParser.parse(source)
        return AgentConfig(
            smartctl = AgentConfig.SmartctlConfig(
                scan = entries.boolean("smartctl", "scan"),
                device = entries.string("smartctl", "device"),
            ),
            deviceIdentity = AgentConfig.DeviceIdentityConfig(
                namespaceSalt = entries.string("deviceIdentity", "namespaceSalt"),
            ),
            output = AgentConfig.OutputConfig(
                mode = entries.string("output", "mode"),
            ),
            runtime = AgentConfig.RuntimeConfig(
                persist = entries.boolean("runtime", "persist"),
                intervalSeconds = entries.long("runtime", "intervalSeconds"),
            ),
            storage = AgentConfig.StorageConfig(
                type = entries.string("storage", "type"),
                jdbcUrl = entries.string("storage", "jdbcUrl"),
                username = entries.string("storage", "username"),
                password = entries.string("storage", "password"),
            ),
            http = AgentConfig.HttpConfig(
                host = entries.string("http", "host"),
                port = entries.int("http", "port"),
            ),
            publicEndpoint = AgentConfig.PublicEndpointConfig(
                baseUrl = entries.string("publicEndpoint", "baseUrl"),
                allowInsecureTransport = entries.boolean("publicEndpoint", "allowInsecureTransport"),
            ),
            hub = AgentConfig.HubConfig(
                endpoint = entries.string("hub", "endpoint"),
                allowInsecureTransport = entries.boolean("hub", "allowInsecureTransport"),
                credentialFile = entries.string("hub", "credentialFile"),
                pemCaFile = entries.string("hub", "pemCaFile"),
                heartbeatIntervalSeconds = entries.long("hub", "heartbeatIntervalSeconds"),
                requestTimeoutSeconds = entries.long("hub", "requestTimeoutSeconds"),
                maxRetries = entries.int("hub", "maxRetries"),
            ),
            auth = AgentConfig.AuthConfig(
                nonceTtlSeconds = entries.long("auth", "nonceTtlSeconds"),
                maxRequestBodyBytes = entries.long("auth", "maxRequestBodyBytes"),
            ),
            retention = AgentConfig.RetentionConfig(
                rawSnapshotDays = entries.int("retention", "rawSnapshotDays"),
            ),
            maintenance = AgentConfig.MaintenanceConfig(
                cleanupOnStartup = entries.boolean("maintenance", "cleanupOnStartup"),
                cleanupIntervalHours = entries.long("maintenance", "cleanupIntervalHours"),
                vacuumAfterCleanup = entries.boolean("maintenance", "vacuumAfterCleanup"),
            ),
            health = AgentConfig.HealthConfig(
                policy = entries.string("health", "policy"),
            ),
        )
    }
}

/** Strict parser for the supported section/key subset, with actionable line diagnostics. */
private object MinimalTomlParser {
    private val allowedKeys = mapOf(
        "smartctl" to setOf("scan", "device"),
        "deviceIdentity" to setOf("namespaceSalt"),
        "output" to setOf("mode"),
        "runtime" to setOf("persist", "intervalSeconds"),
        "storage" to setOf("type", "jdbcUrl", "username", "password"),
        "http" to setOf("host", "port"),
        "publicEndpoint" to setOf("baseUrl", "allowInsecureTransport"),
        "hub" to setOf(
            "endpoint",
            "allowInsecureTransport",
            "credentialFile",
            "pemCaFile",
            "heartbeatIntervalSeconds",
            "requestTimeoutSeconds",
            "maxRetries",
        ),
        "auth" to setOf("nonceTtlSeconds", "maxRequestBodyBytes"),
        "retention" to setOf("rawSnapshotDays"),
        "maintenance" to setOf("cleanupOnStartup", "cleanupIntervalHours", "vacuumAfterCleanup"),
        "health" to setOf("policy"),
    )

    /** Parses a regular file and rejects unknown sections, keys, and value forms. */
    fun parse(path: Path): TomlEntries {
        var section = ""
        val values = mutableMapOf<Pair<String, String>, TomlValue>()

        Files.readAllLines(path).forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = stripComment(rawLine).trim()
            if (line.isBlank()) return@forEachIndexed

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.removePrefix("[").removeSuffix("]").trim()
                if (section !in allowedKeys) {
                    throw AgentConfigParseException("${path}:$lineNumber unknown section [$section].")
                }
                return@forEachIndexed
            }

            if (section.isBlank()) {
                throw AgentConfigParseException("${path}:$lineNumber key must be inside a section.")
            }

            val separator = line.indexOf('=')
            if (separator <= 0) {
                throw AgentConfigParseException("${path}:$lineNumber expected key = value.")
            }

            val key = line.substring(0, separator).trim()
            val rawValue = line.substring(separator + 1).trim()
            if (key !in allowedKeys.getValue(section)) {
                throw AgentConfigParseException("${path}:$lineNumber unknown key $key in [$section].")
            }
            values[section to key] = parseValue(path, lineNumber, rawValue)
        }

        return TomlEntries(path, values)
    }

    /** Removes `#` comments only when outside a quoted string. */
    private fun stripComment(line: String): String {
        var inString = false
        var escaped = false
        line.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                char == '#' && !inString -> return line.substring(0, index)
            }
        }
        return line
    }

    /** Parses one supported scalar and reports malformed values with source location. */
    private fun parseValue(path: Path, lineNumber: Int, rawValue: String): TomlValue {
        if (rawValue.isBlank()) {
            throw AgentConfigParseException("${path}:$lineNumber value must not be blank.")
        }
        return when {
            rawValue.startsWith("\"") -> TomlValue.StringValue(parseString(path, lineNumber, rawValue))
            rawValue == "true" -> TomlValue.BooleanValue(true)
            rawValue == "false" -> TomlValue.BooleanValue(false)
            rawValue.matches(Regex("-?\\d+")) -> TomlValue.LongValue(rawValue.toLong())
            else -> throw AgentConfigParseException(
                "${path}:$lineNumber unsupported value '$rawValue'. Use a quoted string, boolean, or integer.",
            )
        }
    }

    /** Decodes quoted TOML strings and the small escape set accepted by this parser. */
    private fun parseString(path: Path, lineNumber: Int, rawValue: String): String {
        if (!rawValue.endsWith("\"") || rawValue.length < 2) {
            throw AgentConfigParseException("${path}:$lineNumber unterminated string.")
        }
        return buildString {
            var escaped = false
            rawValue.substring(1, rawValue.lastIndex).forEach { char ->
                when {
                    escaped -> {
                        append(
                            when (char) {
                                '"' -> '"'
                                '\\' -> '\\'
                                'n' -> '\n'
                                't' -> '\t'
                                else -> throw AgentConfigParseException(
                                    "${path}:$lineNumber unsupported escape sequence \\$char.",
                                )
                            },
                        )
                        escaped = false
                    }

                    char == '\\' -> escaped = true
                    else -> append(char)
                }
            }
            if (escaped) {
                throw AgentConfigParseException("${path}:$lineNumber unterminated escape sequence.")
            }
        }
    }
}

/** Typed scalar values retained until they are mapped to [AgentConfig]. */
private sealed interface TomlValue {
    data class StringValue(val value: String) : TomlValue
    data class BooleanValue(val value: Boolean) : TomlValue
    data class LongValue(val value: Long) : TomlValue
}

/** Typed view over parsed entries; mismatched types are configuration errors, not nulls. */
private class TomlEntries(
    private val path: Path,
    private val values: Map<Pair<String, String>, TomlValue>,
) {
    /** Returns a string value or `null` when the key was not present. */
    fun string(section: String, key: String): String? {
        return value<TomlValue.StringValue>(section, key)?.value
    }

    /** Returns a boolean value or `null` when the key was not present. */
    fun boolean(section: String, key: String): Boolean? {
        return value<TomlValue.BooleanValue>(section, key)?.value
    }

    /** Returns an integer value or `null` when the key was not present. */
    fun long(section: String, key: String): Long? {
        return value<TomlValue.LongValue>(section, key)?.value
    }

    /** Converts an integer value to [Int], failing when it exceeds the platform range. */
    fun int(section: String, key: String): Int? {
        val value = long(section, key) ?: return null
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
            throw AgentConfigParseException("$path [$section].$key is outside Int range.")
        }
        return value.toInt()
    }

    private inline fun <reified T : TomlValue> value(section: String, key: String): T? {
        val value = values[section to key] ?: return null
        if (value !is T) {
            throw AgentConfigParseException(
                "$path [$section].$key must be ${expectedType<T>()}.",
            )
        }
        return value
    }

    private inline fun <reified T : TomlValue> expectedType(): String {
        return when (T::class) {
            TomlValue.StringValue::class -> "a quoted string"
            TomlValue.BooleanValue::class -> "a boolean"
            TomlValue.LongValue::class -> "an integer"
            else -> "the expected type"
        }
    }
}
