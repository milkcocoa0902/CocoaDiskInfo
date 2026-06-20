package com.milkcocoa.info.sapphire.agent.identity

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class UuidV5DeviceKeyDeriverTest {
    @Test
    fun `derives stable UUIDv5 device key from serial with default salt`() {
        val key = UuidV5DeviceKeyDeriver().deriveFromSerial("SERIAL-123")

        assertEquals("b25b5b07-5629-5c33-89ba-1ef17c03cc0c", key)
        assertEquals(key, UUID.fromString(key).toString())
    }

    @Test
    fun `normalizes surrounding serial whitespace`() {
        val deriver = UuidV5DeviceKeyDeriver()

        assertEquals(
            deriver.deriveFromSerial("SERIAL-123"),
            deriver.deriveFromSerial(" SERIAL-123 "),
        )
    }

    @Test
    fun `namespace salt changes derived device key`() {
        val defaultKey = UuidV5DeviceKeyDeriver("default").deriveFromSerial("SERIAL-123")
        val customKey = UuidV5DeviceKeyDeriver("custom").deriveFromSerial("SERIAL-123")

        assertEquals("36a2c1b1-8b7e-5f99-8ad9-bc6ecd2662f2", customKey)
        assertNotEquals(defaultKey, customKey)
    }

    @Test
    fun `rejects blank namespace salt`() {
        assertFailsWith<IllegalArgumentException> {
            UuidV5DeviceKeyDeriver(" ")
        }
    }

    @Test
    fun `rejects blank serial`() {
        assertFailsWith<IllegalArgumentException> {
            UuidV5DeviceKeyDeriver().deriveFromSerial(" ")
        }
    }
}
