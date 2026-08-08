package com.milkcocoa.info.sapphire.agent.datastore

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection

class StorageConnection internal constructor(
    val settings: StorageSettings,
    val database: Database,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    internal val isClosed: Boolean
        get() = dataSource.isClosed

    override fun close() {
        dataSource.close()
    }

    internal fun <T> useJdbcConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)
}

object StorageConnectionFactory {
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
