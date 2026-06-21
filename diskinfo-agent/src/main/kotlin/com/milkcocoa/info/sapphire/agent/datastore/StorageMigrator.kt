package com.milkcocoa.info.sapphire.agent.datastore

import org.flywaydb.core.Flyway

interface StorageMigrator {
    fun migrate(settings: StorageSettings)
}

fun interface StorageMigratorFactory {
    fun create(storage: StorageSettings): StorageMigrator
}

internal object FlywayMigrator : StorageMigrator {
    override fun migrate(settings: StorageSettings) {
        val migrationLocation = when (settings.backend) {
            StorageBackend.SQLITE -> "classpath:db/migration/sqlite"
            StorageBackend.POSTGRESQL -> "classpath:db/migration/postgresql"
        }

        Flyway.configure()
            .dataSource(settings.jdbcUrl, settings.username, settings.password)
            .locations(migrationLocation)
            .load()
            .migrate()
    }
}

fun createStorageMigratorFactory(): StorageMigratorFactory = StorageMigratorFactory {
    FlywayMigrator
}
