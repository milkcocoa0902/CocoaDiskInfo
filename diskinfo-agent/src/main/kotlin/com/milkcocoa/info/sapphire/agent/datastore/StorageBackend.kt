package com.milkcocoa.info.sapphire.agent.datastore

/** Supported SQL storage backends and their JDBC/pool defaults. */
enum class StorageBackend(
    val configValue: String,
    val jdbcPrefix: String,
    val driverClassName: String,
    val defaultMaximumPoolSize: Int,
) {
    SQLITE(
        configValue = "sqlite",
        jdbcPrefix = "jdbc:sqlite:",
        driverClassName = "org.sqlite.JDBC",
        defaultMaximumPoolSize = 1,
    ),
    POSTGRESQL(
        configValue = "postgresql",
        jdbcPrefix = "jdbc:postgresql:",
        driverClassName = "org.postgresql.Driver",
        defaultMaximumPoolSize = 5,
    ),
    ;

    companion object {
        /** Resolves a config name, accepting `postgres` as a PostgreSQL alias. */
        fun fromConfigValue(value: String): StorageBackend? {
            return entries.firstOrNull { backend ->
                backend.configValue.equals(value, ignoreCase = true)
            } ?: when (value.lowercase()) {
                "postgres" -> POSTGRESQL
                else -> null
            }
        }

        /** Resolves the backend from the JDBC URL scheme, or null when unsupported. */
        fun fromJdbcUrl(jdbcUrl: String): StorageBackend? {
            return entries.firstOrNull { backend ->
                jdbcUrl.startsWith(backend.jdbcPrefix, ignoreCase = true)
            }
        }
    }
}
