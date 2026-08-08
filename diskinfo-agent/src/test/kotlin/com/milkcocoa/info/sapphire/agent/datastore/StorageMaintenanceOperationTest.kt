package com.milkcocoa.info.sapphire.agent.datastore

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageMaintenanceOperationTest {
    @Test
    fun `sqlite vacuum runs outside transaction and leaves database usable`() = runBlocking {
        val databaseFile = createTempFile()
        val storage = StorageSettings.fromJdbcUrl("jdbc:sqlite:${databaseFile.absolutePathString()}")
        createStorageMigratorFactory().create(storage).migrate(storage)

        StorageConnectionFactory.connect(storage).use { connection ->
            val transactionRunner = ExposedTransactionRunner(connection.database)
            val maintenance = createStorageMaintenanceOperation(connection)

            maintenance.vacuum()

            val rowCount = transactionRunner.readOnly {
                DiskSnapshotTable.selectAll().count()
            }
            assertEquals(0, rowCount)
        }
    }
}
