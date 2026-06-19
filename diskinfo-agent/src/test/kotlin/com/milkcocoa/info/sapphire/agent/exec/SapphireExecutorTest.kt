package com.milkcocoa.info.sapphire.agent.exec

import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertTrue

class SapphireExecutorTest {
    @Test
    fun `migrate creates disk snapshot table in temporary sqlite database`() {
        val databaseFile = createTempFile()
        val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePathString()}"

        runBlocking {
            SapphireExecutor.Migrate(jdbcUrl = jdbcUrl).execute()
        }

        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'disk_snapshot'",
                ).use { result ->
                    assertTrue(result.next(), "disk_snapshot table should exist after migration.")
                }
            }
        }
    }
}
