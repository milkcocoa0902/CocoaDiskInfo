package com.milkcocoa.info.sapphire.agent.datastore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorageBackendTest {
    @Test
    fun `infers backend from jdbc url`() {
        assertEquals(StorageBackend.SQLITE, StorageBackend.fromJdbcUrl("jdbc:sqlite:/tmp/cocoadiskinfo.db"))
        assertEquals(
            StorageBackend.POSTGRESQL,
            StorageBackend.fromJdbcUrl("jdbc:postgresql://localhost:5432/cocoadiskinfo"),
        )
        assertNull(StorageBackend.fromJdbcUrl("jdbc:mysql://localhost:3306/cocoadiskinfo"))
    }

    @Test
    fun `resolves backend from config value`() {
        assertEquals(StorageBackend.SQLITE, StorageBackend.fromConfigValue("sqlite"))
        assertEquals(StorageBackend.POSTGRESQL, StorageBackend.fromConfigValue("postgresql"))
        assertEquals(StorageBackend.POSTGRESQL, StorageBackend.fromConfigValue("postgres"))
        assertNull(StorageBackend.fromConfigValue("mysql"))
    }
}
