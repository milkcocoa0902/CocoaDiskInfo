package com.milkcocoa.info.sapphire.agent.datastore

import org.flywaydb.core.Flyway

interface StorageMigrator {
    fun migrate(settings: StorageSettings)
}

interface StorageSchemaValidator {
    fun requireCurrent(settings: StorageSettings)
}

fun interface StorageMigratorFactory {
    fun create(storage: StorageSettings): StorageMigrator
}

internal object FlywayMigrator : StorageMigrator {
    override fun migrate(settings: StorageSettings) {
        configuredFlyway(settings).migrate()
    }
}

fun createStorageMigratorFactory(): StorageMigratorFactory = StorageMigratorFactory {
    FlywayMigrator
}

object FlywayStorageSchemaValidator : StorageSchemaValidator {
    override fun requireCurrent(settings: StorageSettings) {
        val flyway = configuredFlyway(settings)
        val validation = flyway.validateWithResult()
        if (!validation.validationSuccessful) {
            throw IllegalStateException(
                "Storage schema validation failed. Run 'cocoadiskinfo-agent db migrate' before starting hub or standalone.",
            )
        }
        if (flyway.info().pending().isNotEmpty()) {
            throw IllegalStateException(
                "Storage schema has pending migrations. Run 'cocoadiskinfo-agent db migrate' before starting hub or standalone.",
            )
        }
    }
}

private fun configuredFlyway(settings: StorageSettings): Flyway {
    val migrationLocation = when (settings.backend) {
        StorageBackend.SQLITE -> "classpath:db/migration/sqlite"
        StorageBackend.POSTGRESQL -> "classpath:db/migration/postgresql"
    }
    return Flyway.configure()
        .dataSource(settings.jdbcUrl, settings.username, settings.password)
        .locations(migrationLocation)
        .load()
}
