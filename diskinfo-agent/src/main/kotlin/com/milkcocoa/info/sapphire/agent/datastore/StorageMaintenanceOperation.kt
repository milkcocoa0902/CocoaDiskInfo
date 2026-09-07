package com.milkcocoa.info.sapphire.agent.datastore

/** Backend-specific maintenance that runs outside the normal repository transaction. */
interface StorageMaintenanceOperation {
    /** Performs backend-specific maintenance after cleanup; may be an expensive operation. */
    fun vacuum()
}

/** Selects maintenance SQL for the backend owned by [connection]. */
fun createStorageMaintenanceOperation(
    connection: StorageConnection,
): StorageMaintenanceOperation = when (connection.settings.backend) {
    StorageBackend.SQLITE -> SqliteStorageMaintenanceOperation(connection)
    StorageBackend.POSTGRESQL -> PostgreSqlStorageMaintenanceOperation(connection)
}

private class SqliteStorageMaintenanceOperation(
    private val connection: StorageConnection,
) : StorageMaintenanceOperation {
    /** SQLite requires VACUUM on an auto-commit connection, outside a transaction. */
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
    /** PostgreSQL VACUUM (ANALYZE) is likewise issued outside a transaction. */
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
