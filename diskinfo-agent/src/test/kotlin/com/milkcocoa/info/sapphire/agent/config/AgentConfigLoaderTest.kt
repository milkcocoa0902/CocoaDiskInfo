package com.milkcocoa.info.sapphire.agent.config

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentConfigLoaderTest {
    @Test
    fun `null path returns empty config`() {
        val config = AgentConfigLoader.load(null)

        assertNull(config.smartctl.scan)
        assertNull(config.smartctl.device)
        assertNull(config.output.mode)
        assertNull(config.runtime.persist)
        assertNull(config.runtime.intervalSeconds)
        assertNull(config.storage.jdbcUrl)
        assertNull(config.http.port)
    }

    @Test
    fun `loads supported phase 2a config values`() {
        val file = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan = true
                device = "/dev/sda"

                [output]
                mode = "json"

                [runtime]
                persist = false
                intervalSeconds = 30

                [storage]
                jdbcUrl = "jdbc:sqlite:/tmp/cocoadiskinfo.db"

                [http]
                port = 14631
                """.trimIndent(),
            )
        }

        val config = AgentConfigLoader.load(file)

        assertEquals(true, config.smartctl.scan)
        assertEquals("/dev/sda", config.smartctl.device)
        assertEquals("json", config.output.mode)
        assertEquals(false, config.runtime.persist)
        assertEquals(30, config.runtime.intervalSeconds)
        assertEquals("jdbc:sqlite:/tmp/cocoadiskinfo.db", config.storage.jdbcUrl)
        assertEquals(14631, config.http.port)
    }

    @Test
    fun `rejects malformed line with actionable error`() {
        val file = createTempFile().apply {
            writeText(
                """
                [smartctl]
                scan true
                """.trimIndent(),
            )
        }

        val error = assertFailsWith<AgentConfigParseException> {
            AgentConfigLoader.load(file)
        }

        assertMessageContains(error, "expected key = value")
    }

    @Test
    fun `rejects unknown section and key`() {
        val unknownSection = createTempFile().apply {
            writeText(
                """
                [unknown]
                value = true
                """.trimIndent(),
            )
        }
        val unknownSectionError = assertFailsWith<AgentConfigParseException> {
            AgentConfigLoader.load(unknownSection)
        }
        assertMessageContains(unknownSectionError, "unknown section [unknown]")

        val unknownKey = createTempFile().apply {
            writeText(
                """
                [smartctl]
                unknown = true
                """.trimIndent(),
            )
        }
        val unknownKeyError = assertFailsWith<AgentConfigParseException> {
            AgentConfigLoader.load(unknownKey)
        }
        assertMessageContains(unknownKeyError, "unknown key unknown")
    }

    @Test
    fun `rejects type mismatch`() {
        val file = createTempFile().apply {
            writeText(
                """
                [runtime]
                intervalSeconds = "60"
                """.trimIndent(),
            )
        }

        val error = assertFailsWith<AgentConfigParseException> {
            AgentConfigLoader.load(file)
        }

        assertMessageContains(error, "[runtime].intervalSeconds must be an integer")
    }

    @Test
    fun `rejects blank and unsupported values`() {
        val blankValue = createTempFile().apply {
            writeText(
                """
                [output]
                mode =
                """.trimIndent(),
            )
        }
        val blankValueError = assertFailsWith<AgentConfigParseException> {
            AgentConfigLoader.load(blankValue)
        }
        assertMessageContains(blankValueError, "value must not be blank")

        val unsupportedValue = createTempFile().apply {
            writeText(
                """
                [output]
                mode = json
                """.trimIndent(),
            )
        }
        val unsupportedValueError = assertFailsWith<AgentConfigParseException> {
            AgentConfigLoader.load(unsupportedValue)
        }
        assertMessageContains(unsupportedValueError, "unsupported value")
    }

    private fun assertMessageContains(error: AgentConfigParseException, expected: String) {
        assertTrue(
            error.message.orEmpty().contains(expected),
            "Expected error message to contain <$expected>, but was <${error.message}>",
        )
    }
}
