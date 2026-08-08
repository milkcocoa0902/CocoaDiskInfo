package com.milkcocoa.info.sapphire.agent.datastore

interface StorageMaintenanceOperation {
    fun vacuum()
}

fun createStorageMaintenanceOperation(
    connection: StorageConnection,
): StorageMaintenanceOperation = when (connection.settings.backend) {
    StorageBackend.SQLITE -> SqliteStorageMaintenanceOperation(connection)
    StorageBackend.POSTGRESQL -> PostgreSqlStorageMaintenanceOperation(connection)
}

private class SqliteStorageMaintenanceOperation(
    private val connection: StorageConnection,
) : StorageMaintenanceOperation {
    override fun vacuum() {
        connection.useJdbcConnection { jdbcConnection ->
            check(jdbcConnection.autoCommit) {
                "SQLite VACUUM must run outside a transaction."
            }
            jdbcConnection.createStatement().use { statement ->
                statement.execute("VACUUM")
            }
        }
    }
}

private class PostgreSqlStorageMaintenanceOperation(
    private val connection: StorageConnection,
) : StorageMaintenanceOperation {
    override fun vacuum() {
        connection.useJdbcConnection { jdbcConnection ->
            check(jdbcConnection.autoCommit) {
                "PostgreSQL VACUUM must run outside a transaction."
            }
            jdbcConnection.createStatement().use { statement ->
                statement.execute("VACUUM (ANALYZE) disk_snapshot")
            }
        }
    }
}
