package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.agent.OutputMode
import com.milkcocoa.info.sapphire.agent.TargetDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentConfigResolverTest {
    @Test
    fun `resolves oneshot config environment and cli precedence`() {
        val resolved = AgentConfigResolver.resolveOneshot(
            config = AgentConfig(
                smartctl = AgentConfig.SmartctlConfig(scan = true),
                deviceIdentity = AgentConfig.DeviceIdentityConfig(namespaceSalt = "config-salt"),
                output = AgentConfig.OutputConfig(mode = "text"),
                runtime = AgentConfig.RuntimeConfig(persist = false),
                storage = AgentConfig.StorageConfig(jdbcUrl = "jdbc:sqlite:/tmp/config.db"),
            ),
            environment = mapOf(
                "COCOADISKINFO_AGENT_OUTPUT_MODE" to "json",
                "COCOADISKINFO_AGENT_RUNTIME_PERSIST" to "true",
                "COCOADISKINFO_AGENT_STORAGE_JDBC_URL" to "jdbc:sqlite:/tmp/env.db",
                "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "env-salt",
            ),
            cli = AgentConfigOverrides(
                outputMode = OutputMode.CBOR,
            ),
        )

        assertEquals(TargetDevice.Scan, resolved.target)
        assertEquals(OutputMode.CBOR, resolved.outputMode)
        assertEquals(true, resolved.persist)
        assertEquals("jdbc:sqlite:/tmp/env.db", resolved.jdbcUrl)
        assertEquals("env-salt", resolved.deviceIdentityNamespaceSalt)
    }

    @Test
    fun `resolves standalone config environment and cli precedence`() {
        val resolved = AgentConfigResolver.resolveStandalone(
            config = AgentConfig(
                smartctl = AgentConfig.SmartctlConfig(scan = true),
                deviceIdentity = AgentConfig.DeviceIdentityConfig(namespaceSalt = "config-salt"),
                output = AgentConfig.OutputConfig(mode = "text"),
                runtime = AgentConfig.RuntimeConfig(intervalSeconds = 60),
                storage = AgentConfig.StorageConfig(jdbcUrl = "jdbc:sqlite:/tmp/config.db"),
                http = AgentConfig.HttpConfig(host = "127.0.0.1", port = 14631),
            ),
            environment = mapOf(
                "COCOADISKINFO_AGENT_OUTPUT_MODE" to "json",
                "COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS" to "30",
                "COCOADISKINFO_AGENT_STORAGE_JDBC_URL" to "jdbc:sqlite:/tmp/env.db",
                "COCOADISKINFO_AGENT_HTTP_HOST" to "192.0.2.10",
                "COCOADISKINFO_AGENT_HTTP_PORT" to "15000",
                "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "env-salt",
            ),
            cli = AgentConfigOverrides(
                outputMode = OutputMode.CBOR,
                intervalSeconds = 15,
                host = "0.0.0.0",
            ),
        )

        assertEquals(TargetDevice.Scan, resolved.target)
        assertEquals(OutputMode.CBOR, resolved.outputMode)
        assertEquals(15, resolved.intervalSeconds)
        assertEquals("jdbc:sqlite:/tmp/env.db", resolved.jdbcUrl)
        assertEquals("0.0.0.0", resolved.host)
        assertEquals(15000, resolved.port)
        assertEquals("env-salt", resolved.deviceIdentityNamespaceSalt)
    }

    @Test
    fun `db migrate resolves only storage and ignores unused invalid config and environment`() {
        val resolved = AgentConfigResolver.resolveDbMigrate(
            config = AgentConfig(
                smartctl = AgentConfig.SmartctlConfig(scan = true, device = "/dev/sda"),
                deviceIdentity = AgentConfig.DeviceIdentityConfig(namespaceSalt = ""),
                output = AgentConfig.OutputConfig(mode = "xml"),
                runtime = AgentConfig.RuntimeConfig(persist = false, intervalSeconds = 0),
                storage = AgentConfig.StorageConfig(jdbcUrl = "jdbc:sqlite:/tmp/config.db"),
                http = AgentConfig.HttpConfig(host = "", port = 70000),
            ),
            environment = mapOf(
                "COCOADISKINFO_AGENT_SMARTCTL_SCAN" to "maybe",
                "COCOADISKINFO_AGENT_OUTPUT_MODE" to "xml",
                "COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS" to "slow",
                "COCOADISKINFO_AGENT_HTTP_PORT" to "invalid",
                "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "",
            ),
        )

        assertEquals("jdbc:sqlite:/tmp/config.db", resolved.jdbcUrl)
    }

    @Test
    fun `oneshot rejects target output persist and storage errors`() {
        listOf(
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true, device = "/dev/sda")) to emptyMap<String, String>(),
            AgentConfig(output = AgentConfig.OutputConfig(mode = "xml"), smartctl = AgentConfig.SmartctlConfig(scan = true)) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true)) to
                mapOf("COCOADISKINFO_AGENT_RUNTIME_PERSIST" to "yes"),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), storage = AgentConfig.StorageConfig(jdbcUrl = "")) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), deviceIdentity = AgentConfig.DeviceIdentityConfig(namespaceSalt = "")) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true)) to
                mapOf("COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to ""),
        ).forEach { (config, environment) ->
            assertFailsWith<AgentConfigValidationException> {
                AgentConfigResolver.resolveOneshot(config = config, environment = environment)
            }
        }
    }

    @Test
    fun `standalone rejects target runtime http output and storage errors`() {
        listOf(
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true, device = "/dev/sda")) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), runtime = AgentConfig.RuntimeConfig(intervalSeconds = 0)) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), http = AgentConfig.HttpConfig(host = "")) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), http = AgentConfig.HttpConfig(port = 70000)) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), output = AgentConfig.OutputConfig(mode = "xml")) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), storage = AgentConfig.StorageConfig(jdbcUrl = "")) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), deviceIdentity = AgentConfig.DeviceIdentityConfig(namespaceSalt = "")) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true)) to
                mapOf("COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to ""),
        ).forEach { (config, environment) ->
            assertFailsWith<AgentConfigValidationException> {
                AgentConfigResolver.resolveStandalone(config = config, environment = environment)
            }
        }
    }

    @Test
    fun `db migrate rejects blank storage only`() {
        assertFailsWith<AgentConfigValidationException> {
            AgentConfigResolver.resolveDbMigrate(
                config = AgentConfig(),
                environment = mapOf("COCOADISKINFO_AGENT_STORAGE_JDBC_URL" to ""),
            )
        }
    }
}
