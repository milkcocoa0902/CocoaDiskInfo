package com.milkcocoa.info.sapphire.agent.datastore

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class ExposedTransactionRunnerTest {
    @Test
    fun `readOnly transaction works with SQLite connection`() = runBlocking {
        val databaseFile = createTempFile()
        val storage = StorageSettings.fromJdbcUrl("jdbc:sqlite:${databaseFile.absolutePathString()}")

        createStorageMigratorFactory().create(storage).migrate(storage)

        StorageConnectionFactory.connect(storage).use { connection ->
            val runner = ExposedTransactionRunner(connection.database)

            val rowCount = runner.readOnly {
                DiskSnapshotTable.selectAll().toList().size
            }

            assertEquals(0, rowCount)
        }
    }
}
