package com.milkcocoa.info.sapphire.agent.exec

import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertTrue

class SapphireExecutorTest {
    @Test
    fun `migrate creates disk snapshot table in temporary sqlite database and is idempotent`() {
        val databaseFile = createTempFile()
        val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePathString()}"

        runBlocking {
            SapphireExecutor.Migrate(jdbcUrl = jdbcUrl).execute()
            SapphireExecutor.Migrate(jdbcUrl = jdbcUrl).execute()
        }

        assertTrue(tableExists(jdbcUrl, "disk_snapshot"), "disk_snapshot table should exist after migration.")
        assertTrue(tableExists(jdbcUrl, "flyway_schema_history"), "Flyway history table should exist after migration.")
    }
}

private fun tableExists(
    jdbcUrl: String,
    tableName: String,
): Boolean =
    DriverManager.getConnection(jdbcUrl).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$tableName'",
            ).use { result ->
                result.next()
            }
        }
    }
