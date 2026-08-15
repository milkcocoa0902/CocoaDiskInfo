package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.core.auth.AuthProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class LatestPageCursorTest {
    @Test
    fun `round trips node and UTF-8 device key canonically`() {
        val cursor = LatestPageCursor(testNodeId(42), "device-日本語")

        assertEquals(cursor, LatestPageCursorCodec.decode(LatestPageCursorCodec.encode(cursor)))
    }

    @Test
    fun `rejects malformed and non-canonical cursor`() {
        listOf("", "not+base64", "AQ").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                LatestPageCursorCodec.decode(value)
            }
        }
        assertFailsWith<AuthProtocolException> {
            LatestPageCursorCodec.decode(LatestPageCursorCodec.encode(LatestPageCursor(testNodeId(1), "a")) + "A")
        }
    }
}
