package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.testing.test
import com.milkcocoa.info.sapphire.agent.config.AgentConfigDefaults
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
                intervalSeconds = AgentConfigDefaults.COLLECTION_INTERVAL_SECONDS,
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
            "--port",
            "15432",
            "--db-url",
            dbUrl,
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Standalone(
                target = TargetDevice.Explicit(device.absolutePathString()),
                intervalSeconds = 15,
                port = 15432,
                dbUrl = dbUrl,
            ),
            runtime.singleRequest(),
        )
    }

    @Test
    fun `standalone rejects invalid target interval and port`() {
        val device = createTempFile()

        listOf(
            listOf("standalone"),
            listOf("standalone", "--scan", "--device", device.absolutePathString()),
            listOf("standalone", "--scan", "--interval-seconds", "0"),
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
    fun `config can provide standalone runtime http and storage settings`() {
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true

                [runtime]
                intervalSeconds = 45

                [storage]
                jdbcUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-standalone-config.db"

                [http]
                port = 15431
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()

        val result = command(runtime).test("standalone", "--config", config.absolutePathString())

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            SapphireCommandRequest.Standalone(
                target = TargetDevice.Scan,
                intervalSeconds = 45,
                port = 15431,
                dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-standalone-config.db",
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
    fun `config smartctl target conflict is rejected`() {
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
        assertContains(result.output, "must not set both scan=true and device")
        assertEquals(emptyList(), runtime.requests)
    }

    @Test
    fun `config output mode validation is actionable`() {
        val config = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true

                [output]
                mode = "xml"
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()

        val result = command(runtime).test("oneshot", "--config", config.absolutePathString())

        assertTrue(result.statusCode != 0, result.output)
        assertContains(result.output, "Config [output].mode must be one of")
        assertEquals(emptyList(), runtime.requests)
    }

    @Test
    fun `db migrate config is overridden by CLI db url`() {
        val config = createTempFile().apply {
            writeText(
                """
                [storage]
                jdbcUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-config.db"
                """.trimIndent(),
            )
        }
        val runtime = RecordingRuntime()
        val dbUrl = "jdbc:sqlite:/tmp/cocoadiskinfo-cli.db"

        val result = command(runtime).test(
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

    private fun command(runtime: RecordingRuntime = RecordingRuntime()) = createSapphireAgentCommand(runtime)

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
