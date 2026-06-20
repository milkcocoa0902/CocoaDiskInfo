package com.milkcocoa.info.sapphire.agent.datastore

import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageConnectionFactoryTest {
    @Test
    fun `connect creates closeable hikari backed sqlite connection`() {
        val databaseFile = createTempFile()
        val connection = StorageConnectionFactory.connect(
            StorageSettings.fromJdbcUrl("jdbc:sqlite:${databaseFile.absolutePathString()}"),
        )

        assertFalse(connection.isClosed)

        connection.close()

        assertTrue(connection.isClosed)
    }

    @Test
    fun `storage settings redacts password in string representation`() {
        val settings = StorageSettings(
            backend = StorageBackend.POSTGRESQL,
            jdbcUrl = "jdbc:postgresql://localhost:5432/cocoadiskinfo",
            username = "user",
            password = "secret",
        )

        val text = settings.toString()

        assertTrue(text.contains("password=<redacted>"))
        assertFalse(text.contains("secret"))
    }
}
