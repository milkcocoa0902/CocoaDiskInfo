package com.milkcocoa.info.sapphire.agent.datastore

data class StorageSettings(
    val backend: StorageBackend,
    val jdbcUrl: String,
    val username: String? = null,
    val password: String? = null,
) {
    companion object {
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
