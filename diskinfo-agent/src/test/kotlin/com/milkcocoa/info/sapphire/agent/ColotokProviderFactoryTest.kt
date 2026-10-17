package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.colotok.core.logger.ColotokLogger
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Isolated("Captures process-wide stdout")
class ColotokProviderFactoryTest {
    @Test
    fun `structured output is a JSON object without ANSI colors`() = runBlocking {
        for (mode in listOf(OutputMode.DEFAULT, OutputMode.JSON)) {
            val text = captureOutput(mode) { logger ->
                logger.info(TestMessage("test"))
            }
            assertFalse(text.contains('\u001B'), "$mode output contains ANSI escape codes")
            assertIs<JsonObject>(Json.parseToJsonElement(text), "$mode output must be a JSON object")
        }
    }

    @Test
    fun `plain text output retains ANSI colors`() = runBlocking {
        val text = captureOutput(OutputMode.TEXT) { logger ->
            logger.info("test")
        }
        assertTrue(Regex("\u001B\\[[0-9;]+m").containsMatchIn(text), "TEXT output must contain ANSI colors")
        assertTrue(text.contains("[INFO] test"), "TEXT output must contain the log message")
    }

    private suspend fun captureOutput(mode: OutputMode, log: (ColotokLogger) -> Unit): String {
        val originalOut = System.out
        val output = ByteArrayOutputStream()
        PrintStream(output, true, Charsets.UTF_8).use { stream ->
            try {
                System.setOut(stream)
                val context = ColotokProviderFactory.create(mode)
                try {
                    log(context.getLogger())
                } finally {
                    context.shutdown()
                }
            } finally {
                System.setOut(originalOut)
            }
        }
        return output.toString(Charsets.UTF_8)
    }

    @Serializable
    private data class TestMessage(val message: String) : LogStructure
}
