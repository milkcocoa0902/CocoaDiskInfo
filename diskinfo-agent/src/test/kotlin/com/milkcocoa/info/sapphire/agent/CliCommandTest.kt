package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.testing.test
import com.milkcocoa.info.sapphire.agent.config.AgentConfigDefaults
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
        assertContains(result.output, "db")
    }

    @Test
    fun `subcommand help succeeds`() {
        listOf(
            listOf("oneshot", "--help"),
            listOf("standalone", "--help"),
            listOf("db", "migrate", "--help"),
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
            ),
            runtime.singleRequest(),
        )
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
    fun `db alone prints help without running runtime`() {
        val runtime = RecordingRuntime()
        val result = command(runtime).test("db")

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "migrate")
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
