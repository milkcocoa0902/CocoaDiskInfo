package com.milkcocoa.info.sapphire.agent.datastore

/** Connection settings shared by Flyway, Exposed, and the Hikari pool. */
data class StorageSettings(
    val backend: StorageBackend,
    val jdbcUrl: String,
    val username: String? = null,
    val password: String? = null,
) {
    companion object {
        /** Creates settings from a supported JDBC URL without credentials.
         *
         * @throws IllegalArgumentException when [jdbcUrl] uses an unsupported scheme
         */
        fun fromJdbcUrl(jdbcUrl: String): StorageSettings {
            return StorageSettings(
                backend = StorageBackend.fromJdbcUrl(jdbcUrl)
                    ?: throw IllegalArgumentException("Unsupported storage JDBC URL: $jdbcUrl"),
                jdbcUrl = jdbcUrl,
            )
        }
    }

    override fun toString(): String {
        val passwordText = if (password == null) "null" else "<redacted>"
        return "StorageSettings(backend=$backend, jdbcUrl=$jdbcUrl, username=$username, password=$passwordText)"
    }
}
