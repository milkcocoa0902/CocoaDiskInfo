package com.milkcocoa.info.sapphire.agent.datastore

import org.flywaydb.core.Flyway

/** Applies the backend's versioned schema migrations as an explicit database operation. */
interface StorageMigrator {
    /** Migrates [settings] to the latest schema; does not collect or serve snapshots. */
    fun migrate(settings: StorageSettings)
}

/** Validates that an existing database has no failed or pending migrations. */
interface StorageSchemaValidator {
    /** Throws when [settings] does not point at the current schema. */
    fun requireCurrent(settings: StorageSettings)
}

/** Factory boundary kept separate so startup and `db migrate` can choose their migrator. */
fun interface StorageMigratorFactory {
    fun create(storage: StorageSettings): StorageMigrator
}

internal object FlywayMigrator : StorageMigrator {
    /** Runs only the migrations selected by the backend-specific Flyway location. */
    override fun migrate(settings: StorageSettings) {
        configuredFlyway(settings).migrate()
    }
}

fun createStorageMigratorFactory(): StorageMigratorFactory = StorageMigratorFactory {
    FlywayMigrator
}

object FlywayStorageSchemaValidator : StorageSchemaValidator {
    /**
     * Ensures migration history validates and is complete before a long-running mode starts.
     *
     * Startup intentionally fails with an actionable message instead of implicitly changing the
     * schema; the dedicated migration command owns schema evolution.
     */
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

/** Builds Flyway with the migration directory matching [settings.backend]. */
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
