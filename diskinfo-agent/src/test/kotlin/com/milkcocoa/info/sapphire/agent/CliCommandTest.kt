package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.testing.test
import com.milkcocoa.info.sapphire.agent.config.AgentConfigDefaults
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenType
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliCommandTest {
    @Test
    fun `root help lists phase 2a subcommands`() {
        val result = command().test("--help")

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "oneshot")
        assertContains(result.output, "standalone")
        assertContains(result.output, "hub")
        assertContains(result.output, "node-agent")
        assertContains(result.output, "db")
    }

    @Test
    fun `subcommand help succeeds`() {
        listOf(
            listOf("oneshot", "--help"),
            listOf("standalone", "--help"),
            listOf("standalone", "principal", "disable", "--help"),
            listOf("hub", "--help"),
            listOf("hub", "principal", "disable", "--help"),
            listOf("hub", "join-token", "create", "--help"),
            listOf("hub", "client-pairing-token", "create", "--help"),
            listOf("node-agent", "--help"),
            listOf("node-agent", "join", "--help"),
            listOf("db", "migrate", "--help"),
            listOf("db", "cleanup", "--help"),
        ).forEach { args ->
            val result = command().test(args)

            assertEquals(0, result.statusCode, result.output)
            assertContains(result.output, "Usage:")
        }
    }

    @Test
    fun `standalone help includes phase 2b output and host options`() {
        val result = command().test(listOf("standalone", "--help"))

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "--output")
        assertContains(result.output, "--host")
    }

    @Test
    fun `health policy option is limited to runtime modes and documented for node agent`() {
        listOf("oneshot", "standalone", "hub").forEach { mode ->
            val result = command().test(listOf(mode, "--help"))
            assertEquals(0, result.statusCode, result.output)
            assertContains(result.output, "--health-policy")
        }

        val nodeHelp = command().test(listOf("node-agent", "--help"))
        assertEquals(0, nodeHelp.statusCode, nodeHelp.output)
        assertContains(nodeHelp.output, "--health-policy")
        assertContains(nodeHelp.output, "local console output only")
        assertContains(nodeHelp.output, "Hub-side history evaluation")

        val dbHelp = command().test(listOf("db", "migrate", "--help"))
        assertEquals(0, dbHelp.statusCode, dbHelp.output)
        assertTrue("--health-policy" !in dbHelp.output)
    }

    @Test
    fun `health policy CLI override is validated before a request is run`() {
        val runtime = RecordingRuntime()
        val result = command(runtime).test("oneshot", "--scan", "--health-policy", "unknown")

        assertTrue(result.statusCode != 0, result.output)
        assertContains(result.output, "Available policies: default")
        assertEquals(emptyList(), runtime.requests)
    }

    @Test
    fun `legacy mode flags are rejected`() {
        listOf(
            listOf("--oneshot", "--scan"),
            listOf("--agent", "--scan"),
            listOf("--migration"),
        ).forEach { args ->
            val runtime = RecordingRuntime()
            val result = command(runtime).test(args)

            assertTrue(result.statusCode != 0, result.output)
            assertContains(result.output, args.first())
            assertEquals(emptyList(), runtime.requests)
        }
    }

    @Test
    fun `oneshot scan uses scan target without persistence by default`() {
        val runtime = RecordingRuntime()
        val result = command(runtime).test(listOf("oneshot", "--scan"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Oneshot(
                target = TargetDevice.Scan,
                outputMode = OutputMode.DEFAULT,
                persist = false,
                dbUrl = AgentConfigDefaults.JDBC_URL,
                deviceIdentityNamespaceSalt = AgentConfigDefaults.DEVICE_IDENTITY_NAMESPACE_SALT,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `oneshot device output and persistence are assembled from CLI options`() {
        val device = createTempFile()
        val runtime = RecordingRuntime()
        val dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-cli-oneshot.db"

        val result = command(runtime).test(
            "oneshot",
            "--device",
            device.absolutePathString(),
            "--output",
            "json",
            "--persist",
            "--db-url",
            dbUrl,
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Oneshot(
                target = TargetDevice.Explicit(device.absolutePathString()),
                outputMode = OutputMode.JSON,
                persist = true,
                dbUrl = dbUrl,
                deviceIdentityNamespaceSalt = AgentConfigDefaults.DEVICE_IDENTITY_NAMESPACE_SALT,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `oneshot rejects missing or conflicting target`() {
        val device = createTempFile()

        listOf(
            listOf("oneshot"),
            listOf("oneshot", "--scan", "--device", device.absolutePathString()),
        ).forEach { args ->
            val runtime = RecordingRuntime()
            val result = command(runtime).test(args)

            assertTrue(result.statusCode != 0, result.output)
            assertEquals(emptyList(), runtime.requests)
        }
    }

    @Test
    fun `standalone scan uses runtime defaults`() {
        val runtime = RecordingRuntime()
        val result = command(runtime).test(listOf("standalone", "--scan"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Standalone(
                target = TargetDevice.Scan,
                outputMode = OutputMode.DEFAULT,
                intervalSeconds = AgentConfigDefaults.COLLECTION_INTERVAL_SECONDS,
                host = AgentConfigDefaults.HTTP_HOST,
                port = AgentConfigDefaults.HTTP_PORT,
                dbUrl = AgentConfigDefaults.JDBC_URL,
                deviceIdentityNamespaceSalt = AgentConfigDefaults.DEVICE_IDENTITY_NAMESPACE_SALT,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `standalone accepts CLI runtime http and storage settings`() {
        val device = createTempFile()
        val runtime = RecordingRuntime()
        val dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-cli-standalone.db"

        val result = command(runtime).test(
            "standalone",
            "--device",
            device.absolutePathString(),
            "--interval-seconds",
            "15",
            "--output",
            "json",
            "--host",
            "0.0.0.0",
            "--port",
            "15432",
            "--db-url",
            dbUrl,
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Standalone(
                target = TargetDevice.Explicit(device.absolutePathString()),
                outputMode = OutputMode.JSON,
                intervalSeconds = 15,
                host = "0.0.0.0",
                port = 15432,
                dbUrl = dbUrl,
                deviceIdentityNamespaceSalt = AgentConfigDefaults.DEVICE_IDENTITY_NAMESPACE_SALT,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `hub and bootstrap token commands assemble distributed requests`() {
        val runtime = RecordingRuntime()
        val hubResult = command(runtime).test(
            "hub",
            "--host",
            "0.0.0.0",
            "--port",
            "15431",
            "--public-endpoint",
            "http://192.0.2.10:15431",
            "--allow-insecure-transport",
            "--db-url",
            "jdbc:sqlite:/tmp/cocoadiskinfo-hub.db",
        )
        assertEquals(0, hubResult.statusCode, hubResult.output)
        assertTrue(runtime.requests.single() is SapphireCommandRequest.Hub)

        val tokenRuntime = RecordingRuntime()
        val tokenResult = command(tokenRuntime).test(
            "hub",
            "join-token",
            "create",
            "--public-endpoint",
            "http://192.0.2.10:15431",
            "--allow-insecure-transport",
            "--db-url",
            "jdbc:sqlite:/tmp/cocoadiskinfo-hub.db",
            "--node-name",
            "node-a",
            "--ttl-seconds",
            "120",
        )
        assertEquals(0, tokenResult.statusCode, tokenResult.output)
        val tokenRequest = tokenRuntime.singleRequest() as SapphireCommandRequest.BootstrapTokenCreate
        assertEquals(BootstrapTokenType.JOIN_TOKEN, tokenRequest.tokenType)
        assertEquals("node-a", tokenRequest.expectedDisplayName)
        assertEquals(120, tokenRequest.ttlSeconds)
    }

    @Test
    fun `standalone pairing token uses standalone default storage without hub requirements`() {
        val runtime = RecordingRuntime()
        val result = command(runtime).test(
            "standalone",
            "client-pairing-token",
            "create",
            "--public-endpoint",
            "http://127.0.0.1:14631",
            "--allow-insecure-transport",
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.BootstrapTokenCreate(
                tokenType = BootstrapTokenType.PAIRING_TOKEN,
                storage = StorageSettings.fromJdbcUrl(AgentConfigDefaults.JDBC_URL),
                publicEndpointBaseUrl = "http://127.0.0.1:14631",
                ttlSeconds = 600,
                expectedDisplayName = null,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `hub and standalone principal disable resolve only storage and kid`() {
        val kid = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        listOf("hub", "standalone").forEach { mode ->
            val runtime = RecordingRuntime()
            val dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-$mode-principal.db"
            val result = command(runtime).test(
                mode,
                "principal",
                "disable",
                "--kid",
                kid,
                "--db-url",
                dbUrl,
            )

            assertEquals(0, result.statusCode, result.output)
            assertEquals(
                SapphireCommandRequest.PrincipalDisable(
                    storage = StorageSettings.fromJdbcUrl(dbUrl),
                    kid = kid,
                ),
                runtime.singleRequest(),
            )
        }
    }

    @Test
    fun `principal disable requires kid`() {
        listOf("hub", "standalone").forEach { mode ->
            val runtime = RecordingRuntime()
            val result = command(runtime).test(mode, "principal", "disable")

            assertTrue(result.statusCode != 0, result.output)
            assertContains(result.output, "--kid")
            assertEquals(emptyList(), runtime.requests)
        }
    }

    @Test
    fun `node agent run and join commands assemble transport requests without storage`() {
        val credential = "/tmp/cocoadiskinfo-node-credential.json"
        val runtime = RecordingRuntime()
        val runResult = command(runtime).test(
            "node-agent",
            "--scan",
            "--hub",
            "http://192.0.2.10:15431",
            "--allow-insecure-transport",
            "--credential-file",
            credential,
            "--interval-seconds",
            "30",
        )
        assertEquals(0, runResult.statusCode, runResult.output)
        val runRequest = runtime.singleRequest() as SapphireCommandRequest.NodeAgent
        assertEquals(TargetDevice.Scan, runRequest.target)
        assertEquals(30, runRequest.intervalSeconds)

        val joinRuntime = RecordingRuntime()
        val joinResult = command(joinRuntime).test(
            "node-agent",
            "join",
            "--hub",
            "http://192.0.2.10:15431",
            "--allow-insecure-transport",
            "--credential-file",
            credential,
            "--hub-id",
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "--token-id",
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "--token-secret",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "--node-name",
            "node-a",
        )
        assertEquals(0, joinResult.statusCode, joinResult.output)
        val joinRequest = joinRuntime.singleRequest() as SapphireCommandRequest.NodeAgentJoin
        assertEquals("node-a", joinRequest.nodeName)
        assertEquals(credential, joinRequest.credentialFile)
    }

    @Test
    fun `standalone rejects invalid target interval host and port`() {
        val device = createTempFile()

        listOf(
            listOf("standalone"),
            listOf("standalone", "--scan", "--device", device.absolutePathString()),
            listOf("standalone", "--scan", "--interval-seconds", "0"),
            listOf("standalone", "--scan", "--output", "xml"),
            listOf("standalone", "--scan", "--host", ""),
            listOf("standalone", "--scan", "--port", "70000"),
        ).forEach { args ->
            val runtime = RecordingRuntime()
            val result = command(runtime).test(args)

            assertTrue(result.statusCode != 0, result.output)
            assertEquals(emptyList(), runtime.requests)
        }
    }

    @Test
    fun `db migrate uses default and CLI db url`() {
        val defaultRuntime = RecordingRuntime()
        val defaultResult = command(defaultRuntime).test(listOf("db", "migrate"))

        assertEquals(0, defaultResult.statusCode, defaultResult.output)
        assertEquals(
            SapphireCommandRequest.DbMigrate(dbUrl = AgentConfigDefaults.JDBC_URL),
            defaultRuntime.singleRequest(),
        )

        val cliRuntime = RecordingRuntime()
        val dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-cli-migrate.db"
        val cliResult = command(cliRuntime).test("db", "migrate", "--db-url", dbUrl)

        assertEquals(0, cliResult.statusCode, cliResult.output)
        assertEquals(
            SapphireCommandRequest.DbMigrate(dbUrl = dbUrl),
            cliRuntime.singleRequest(),
        )
    }

    @Test
    fun `db cleanup assembles defaults and CLI overrides without device settings`() {
        val defaultRuntime = RecordingRuntime()
        val defaultResult = command(defaultRuntime).test(listOf("db", "cleanup"))

        assertEquals(0, defaultResult.statusCode, defaultResult.output)
        assertEquals(
            SapphireCommandRequest.DbCleanup(
                dbUrl = AgentConfigDefaults.JDBC_URL,
                rawSnapshotDays = AgentConfigDefaults.DEFAULT_RAW_SNAPSHOT_DAYS,
                dryRun = false,
                vacuumAfterCleanup = AgentConfigDefaults.DEFAULT_VACUUM_AFTER_CLEANUP,
            ),
            defaultRuntime.singleRequest(),
        )

        val cliRuntime = RecordingRuntime()
        val dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-cli-cleanup.db"
        val cliResult = command(cliRuntime).test(
            "db",
            "cleanup",
            "--db-url",
            dbUrl,
            "--raw-snapshot-days",
            "14",
            "--dry-run",
            "--vacuum",
        )

        assertEquals(0, cliResult.statusCode, cliResult.output)
        assertEquals(
            SapphireCommandRequest.DbCleanup(
                dbUrl = dbUrl,
                rawSnapshotDays = 14,
                dryRun = true,
                vacuumAfterCleanup = true,
            ),
            cliRuntime.singleRequest(),
        )
    }

    @Test
    fun `db cleanup rejects retention outside allowed range`() {
        listOf("0", "366").forEach { rawSnapshotDays ->
            val runtime = RecordingRuntime()
            val result = command(runtime).test(
                "db",
                "cleanup",
                "--raw-snapshot-days",
                rawSnapshotDays,
                "--dry-run",
            )

            assertTrue(result.statusCode != 0, result.output)
            assertContains(result.output, "between 1 and 365")
            assertEquals(emptyList(), runtime.requests)
        }
    }

    @Test
    fun `db cleanup CLI overrides environment and config`() {
        val config = createTempFile().apply {
            writeText(
                """
                [retention]
                rawSnapshotDays = 60

                [maintenance]
                vacuumAfterCleanup = false
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()
        val result = command(
            runtime = runtime,
            environment = mapOf(
                "COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS" to "45",
                "COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP" to "false",
            ),
        ).test(
            "db",
            "cleanup",
            "--config",
            config.absolutePathString(),
            "--raw-snapshot-days",
            "14",
            "--vacuum",
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.DbCleanup(
                dbUrl = AgentConfigDefaults.JDBC_URL,
                rawSnapshotDays = 14,
                dryRun = false,
                vacuumAfterCleanup = true,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `db alone prints help without running runtime`() {
        val runtime = RecordingRuntime()
        val result = command(runtime).test("db")

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "migrate")
        assertContains(result.output, "cleanup")
        assertEquals(emptyList(), runtime.requests)
    }

    @Test
    fun `config can provide oneshot target and output`() {
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true

                [output]
                mode = "json"

                [deviceIdentity]
                namespaceSalt = "config-salt"
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()

        val result = command(runtime).test("oneshot", "--config", config.absolutePathString())

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Oneshot(
                target = TargetDevice.Scan,
                outputMode = OutputMode.JSON,
                persist = false,
                dbUrl = AgentConfigDefaults.JDBC_URL,
                deviceIdentityNamespaceSalt = "config-salt",
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `default config path is read when present`() {
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true

                [output]
                mode = "text"

                [runtime]
                persist = true
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()

        val result = command(runtime, defaultConfigPath = config).test("oneshot")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Oneshot(
                target = TargetDevice.Scan,
                outputMode = OutputMode.TEXT,
                persist = true,
                dbUrl = AgentConfigDefaults.JDBC_URL,
                deviceIdentityNamespaceSalt = AgentConfigDefaults.DEVICE_IDENTITY_NAMESPACE_SALT,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `CLI target overrides config target`() {
        val configuredDevice = createTempFile()
        val cliDevice = createTempFile()
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                device = "${configuredDevice.absolutePathString()}"
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()

        val result = command(runtime).test(
            "oneshot",
            "--config",
            config.absolutePathString(),
            "--device",
            cliDevice.absolutePathString(),
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Oneshot(
                target = TargetDevice.Explicit(cliDevice.absolutePathString()),
                outputMode = OutputMode.DEFAULT,
                persist = false,
                dbUrl = AgentConfigDefaults.JDBC_URL,
                deviceIdentityNamespaceSalt = AgentConfigDefaults.DEVICE_IDENTITY_NAMESPACE_SALT,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `environment overrides config and CLI overrides environment`() {
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true

                [output]
                mode = "text"

                [runtime]
                intervalSeconds = 60

                [deviceIdentity]
                namespaceSalt = "config-salt"

                [storage]
                jdbcUrl = "jdbc:sqlite:/tmp/config.db"

                [http]
                host = "127.0.0.1"
                port = 14631
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()
        val environment = mapOf(
            "COCOADISKINFO_AGENT_OUTPUT_MODE" to "json",
            "COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS" to "30",
            "COCOADISKINFO_AGENT_STORAGE_JDBC_URL" to "jdbc:sqlite:/tmp/env.db",
            "COCOADISKINFO_AGENT_HTTP_HOST" to "192.0.2.10",
            "COCOADISKINFO_AGENT_HTTP_PORT" to "15000",
            "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "env-salt",
        )

        val result = command(runtime, environment = environment).test(
            "standalone",
            "--config",
            config.absolutePathString(),
            "--interval-seconds",
            "15",
            "--output",
            "cbor",
            "--host",
            "0.0.0.0",
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Standalone(
                target = TargetDevice.Scan,
                outputMode = OutputMode.CBOR,
                intervalSeconds = 15,
                host = "0.0.0.0",
                port = 15000,
                dbUrl = "jdbc:sqlite:/tmp/env.db",
                deviceIdentityNamespaceSalt = "env-salt",
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `config smartctl target conflict is rejected for device commands`() {
        val device = createTempFile()
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true
                device = "${device.absolutePathString()}"
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()

        val result = command(runtime).test("oneshot", "--config", config.absolutePathString())

        assertTrue(result.statusCode != 0, result.output)
        assertContains(result.output, "Use either scan or device")
        assertEquals(emptyList(), runtime.requests)
    }

    @Test
    fun `db migrate config is overridden by CLI db url and does not require target`() {
        val device = createTempFile()
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true
                device = "${device.absolutePathString()}"

                [output]
                mode = "xml"

                [runtime]
                intervalSeconds = 0

                [deviceIdentity]
                namespaceSalt = ""

                [storage]
                jdbcUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-config.db"

                [http]
                host = ""
                port = 70000
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()
        val dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-cli.db"

        val result = command(
            runtime,
            environment = mapOf(
                "COCOADISKINFO_AGENT_SMARTCTL_SCAN" to "maybe",
                "COCOADISKINFO_AGENT_OUTPUT_MODE" to "xml",
                "COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS" to "slow",
                "COCOADISKINFO_AGENT_HTTP_PORT" to "invalid",
                "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "",
            ),
        ).test(
            "db",
            "migrate",
            "--config",
            config.absolutePathString(),
            "--db-url",
            dbUrl,
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.DbMigrate(dbUrl = dbUrl),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `invalid oneshot environment values are rejected`() {
        listOf(
            mapOf("COCOADISKINFO_AGENT_RUNTIME_PERSIST" to "yes"),
            mapOf("COCOADISKINFO_AGENT_STORAGE_JDBC_URL" to ""),
            mapOf("COCOADISKINFO_AGENT_OUTPUT_MODE" to "xml"),
            mapOf("COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to ""),
        ).forEach { environment ->
            val runtime = RecordingRuntime()
            val result = command(runtime, environment = environment).test("oneshot", "--scan")

            assertTrue(result.statusCode != 0, result.output)
            assertEquals(emptyList(), runtime.requests)
        }
    }

    @Test
    fun `unused environment values do not affect db migrate`() {
        val runtime = RecordingRuntime()
        val result = command(
            runtime,
            environment = mapOf(
                "COCOADISKINFO_AGENT_SMARTCTL_SCAN" to "maybe",
                "COCOADISKINFO_AGENT_OUTPUT_MODE" to "xml",
                "COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS" to "fast",
                "COCOADISKINFO_AGENT_HTTP_PORT" to "70000",
                "COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT" to "",
            ),
            defaultConfigPath = null,
        ).test(listOf("db", "migrate"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.DbMigrate(dbUrl = AgentConfigDefaults.JDBC_URL),
            runtime.singleRequest(),
        )
    }

    private fun command(
        runtime: RecordingRuntime = RecordingRuntime(),
        environment: Map<String, String> = emptyMap(),
        defaultConfigPath: Path? = null,
    ) = createSapphireAgentCommand(runtime, environment, defaultConfigPath)

    private fun assertContains(actual: String, expected: String) {
        assertTrue(actual.contains(expected), "Expected output to contain <$expected>, but was:\n$actual")
    }

    private class RecordingRuntime : SapphireCommandRuntime {
        val requests = mutableListOf<SapphireCommandRequest>()

        override fun run(request: SapphireCommandRequest) {
            requests += request
        }

        fun singleRequest(): SapphireCommandRequest {
            assertEquals(1, requests.size)
            return requests.single()
        }
    }
}
