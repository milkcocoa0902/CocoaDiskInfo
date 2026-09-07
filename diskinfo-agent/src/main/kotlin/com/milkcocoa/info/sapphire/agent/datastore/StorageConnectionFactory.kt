package com.milkcocoa.info.sapphire.agent.datastore

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection

/**
 * Owns one configured Hikari pool and its Exposed database handle.
 *
 * The connection remains usable until [close] and must be closed by the mode that opened it;
 * individual JDBC connections are leased only for [useJdbcConnection] and returned immediately.
 */
class StorageConnection internal constructor(
    val settings: StorageSettings,
    val database: Database,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    internal val isClosed: Boolean
        get() = dataSource.isClosed

    /** Closes the pool; subsequent JDBC leases are invalid. */
    override fun close() {
        dataSource.close()
    }

    /** Runs [block] with one pooled JDBC connection and always returns it to the pool. */
    internal fun <T> useJdbcConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)
}

object StorageConnectionFactory {
    /** Opens a backend-specific pool; schema migration is deliberately a separate operation. */
    fun connect(settings: StorageSettings): StorageConnection {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                driverClassName = settings.backend.driverClassName
                maximumPoolSize = settings.backend.defaultMaximumPoolSize
                poolName = "cocoadiskinfo-${settings.backend.configValue}"
                settings.username?.let { username = it }
                settings.password?.let { password = it }
            },
        )
        return StorageConnection(
            settings = settings,
            database = Database.connect(dataSource),
            dataSource = dataSource,
        )
    }
}
