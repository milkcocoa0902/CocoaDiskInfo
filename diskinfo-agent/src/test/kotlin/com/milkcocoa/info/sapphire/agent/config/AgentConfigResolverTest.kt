package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.agent.OutputMode
import com.milkcocoa.info.sapphire.agent.TargetDevice
import com.milkcocoa.info.sapphire.agent.datastore.StorageBackend
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
                storage = AgentConfig.StorageConfig(
                    type = "sqlite",
                    jdbcUrl = "jdbc:sqlite:/tmp/config.db",
                    username = "config-user",
                    password = "config-password",
                ),
            ),
            environment = mapOf(
                "COCOADISKINFO_AGENT_OUTPUT_MODE" to "json",
                "COCOADISKINFO_AGENT_RUNTIME_PERSIST" to "true",
                "COCOADISKINFO_AGENT_STORAGE_JDBC_URL" to "jdbc:sqlite:/tmp/env.db",
                "COCOADISKINFO_AGENT_STORAGE_USERNAME" to "env-user",
                "COCOADISKINFO_AGENT_STORAGE_PASSWORD" to "env-password",
                "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "env-salt",
            ),
            cli = AgentConfigOverrides(
                outputMode = OutputMode.CBOR,
            ),
        )

        assertEquals(TargetDevice.Scan, resolved.target)
        assertEquals(OutputMode.CBOR, resolved.outputMode)
        assertEquals(true, resolved.persist)
        assertEquals(StorageBackend.SQLITE, resolved.storage.backend)
        assertEquals("jdbc:sqlite:/tmp/env.db", resolved.jdbcUrl)
        assertEquals("env-user", resolved.storage.username)
        assertEquals("env-password", resolved.storage.password)
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
                storage = AgentConfig.StorageConfig(
                    type = "postgresql",
                    jdbcUrl = "jdbc:postgresql://localhost:5432/config",
                    username = "config-user",
                    password = "config-password",
                ),
                http = AgentConfig.HttpConfig(host = "127.0.0.1", port = 14631),
                retention = AgentConfig.RetentionConfig(rawSnapshotDays = 60),
                maintenance = AgentConfig.MaintenanceConfig(
                    cleanupOnStartup = false,
                    cleanupIntervalHours = 48,
                    vacuumAfterCleanup = false,
                ),
            ),
            environment = mapOf(
                "COCOADISKINFO_AGENT_OUTPUT_MODE" to "json",
                "COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS" to "30",
                "COCOADISKINFO_AGENT_STORAGE_JDBC_URL" to "jdbc:postgresql://localhost:5432/env",
                "COCOADISKINFO_AGENT_STORAGE_USERNAME" to "env-user",
                "COCOADISKINFO_AGENT_STORAGE_PASSWORD" to "env-password",
                "COCOADISKINFO_AGENT_HTTP_HOST" to "192.0.2.10",
                "COCOADISKINFO_AGENT_HTTP_PORT" to "15000",
                "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "env-salt",
                "COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS" to "45",
                "COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_ON_STARTUP" to "true",
                "COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_INTERVAL_HOURS" to "12",
                "COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP" to "true",
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
        assertEquals(StorageBackend.POSTGRESQL, resolved.storage.backend)
        assertEquals("jdbc:postgresql://localhost:5432/env", resolved.jdbcUrl)
        assertEquals("env-user", resolved.storage.username)
        assertEquals("env-password", resolved.storage.password)
        assertEquals("0.0.0.0", resolved.host)
        assertEquals(15000, resolved.port)
        assertEquals("env-salt", resolved.deviceIdentityNamespaceSalt)
        assertEquals(45, resolved.rawSnapshotDays)
        assertEquals(true, resolved.cleanupOnStartup)
        assertEquals(12, resolved.cleanupIntervalHours)
        assertEquals(true, resolved.vacuumAfterCleanup)
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
    fun `db cleanup resolves defaults and config environment cli precedence`() {
        val defaults = AgentConfigResolver.resolveDbCleanup(
            config = AgentConfig(),
            environment = emptyMap(),
        )
        assertEquals(30, defaults.rawSnapshotDays)
        assertEquals(false, defaults.vacuumAfterCleanup)

        val config = AgentConfig(
            storage = AgentConfig.StorageConfig(jdbcUrl = "jdbc:sqlite:/tmp/config.db"),
            retention = AgentConfig.RetentionConfig(rawSnapshotDays = 60),
            maintenance = AgentConfig.MaintenanceConfig(vacuumAfterCleanup = false),
        )
        val fromConfig = AgentConfigResolver.resolveDbCleanup(
            config = config,
            environment = emptyMap(),
        )
        assertEquals(60, fromConfig.rawSnapshotDays)
        assertEquals(false, fromConfig.vacuumAfterCleanup)

        val fromEnvironment = AgentConfigResolver.resolveDbCleanup(
            config = config,
            environment = mapOf(
                "COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS" to "45",
                "COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP" to "true",
            ),
        )
        assertEquals(45, fromEnvironment.rawSnapshotDays)
        assertEquals(true, fromEnvironment.vacuumAfterCleanup)

        val fromCli = AgentConfigResolver.resolveDbCleanup(
            config = config,
            environment = mapOf(
                "COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS" to "45",
                "COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP" to "true",
            ),
            cli = AgentConfigOverrides(
                rawSnapshotDays = 14,
                vacuumAfterCleanup = false,
            ),
        )

        assertEquals("jdbc:sqlite:/tmp/config.db", fromCli.jdbcUrl)
        assertEquals(14, fromCli.rawSnapshotDays)
        assertEquals(false, fromCli.vacuumAfterCleanup)
    }

    @Test
    fun `db cleanup rejects retention outside allowed range`() {
        listOf(0, 366).forEach { rawSnapshotDays ->
            assertFailsWith<AgentConfigValidationException> {
                AgentConfigResolver.resolveDbCleanup(
                    config = AgentConfig(
                        retention = AgentConfig.RetentionConfig(rawSnapshotDays = rawSnapshotDays),
                    ),
                    environment = emptyMap(),
                )
            }
        }
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
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), retention = AgentConfig.RetentionConfig(rawSnapshotDays = 366)) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true), maintenance = AgentConfig.MaintenanceConfig(cleanupIntervalHours = 0)) to emptyMap<String, String>(),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true)) to
                mapOf("COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to ""),
            AgentConfig(smartctl = AgentConfig.SmartctlConfig(scan = true)) to
                mapOf("COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_ON_STARTUP" to "sometimes"),
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

    @Test
    fun `storage type is inferred from jdbc url and explicit mismatch is rejected`() {
        val inferred = AgentConfigResolver.resolveDbMigrate(
            config = AgentConfig(
                storage = AgentConfig.StorageConfig(
                    jdbcUrl = "jdbc:postgresql://localhost:5432/cocoadiskinfo",
                ),
            ),
        )

        assertEquals(StorageBackend.POSTGRESQL, inferred.storage.backend)

        assertFailsWith<AgentConfigValidationException> {
            AgentConfigResolver.resolveDbMigrate(
                config = AgentConfig(
                    storage = AgentConfig.StorageConfig(
                        type = "sqlite",
                        jdbcUrl = "jdbc:postgresql://localhost:5432/cocoadiskinfo",
                    ),
                ),
            )
        }
    }

    @Test
    fun `storage rejects unknown type and unsupported jdbc url`() {
        assertFailsWith<AgentConfigValidationException> {
            AgentConfigResolver.resolveDbMigrate(
                config = AgentConfig(
                    storage = AgentConfig.StorageConfig(
                        type = "mysql",
                        jdbcUrl = "jdbc:sqlite:/tmp/cocoadiskinfo.db",
                    ),
                ),
            )
        }

        assertFailsWith<AgentConfigValidationException> {
            AgentConfigResolver.resolveDbMigrate(
                config = AgentConfig(
                    storage = AgentConfig.StorageConfig(
                        jdbcUrl = "jdbc:mysql://localhost:3306/cocoadiskinfo",
                    ),
                ),
            )
        }
    }
}
