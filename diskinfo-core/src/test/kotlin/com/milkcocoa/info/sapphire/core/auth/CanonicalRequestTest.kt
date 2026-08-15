package com.milkcocoa.info.sapphire.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalRequestTest {
    @Test
    fun `query uses validated effective values sorted by encoded name`() {
        val query = CanonicalRequestCodec.query(
            linkedMapOf(
                "to" to "2026-08-15T00:00:00Z",
                "limit" to "100",
                "from" to null,
                "order" to "desc",
                "cursor" to "node/a+b",
            ),
        )

        assertEquals(
            "cursor=node%2Fa%2Bb&limit=100&order=desc&to=2026-08-15T00%3A00%3A00Z",
            query,
        )
    }

    @Test
    fun `query shape rejects unknown and duplicate raw parameters`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalRequestCodec.validateRawQueryShape(
                rawParameters = listOf("limit" to "100", "unexpected" to "value"),
                knownNames = setOf("limit", "order"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalRequestCodec.validateRawQueryShape(
                rawParameters = listOf("limit" to "100", "limit" to "200"),
                knownNames = setOf("limit", "order"),
            )
        }
    }

    @Test
    fun `path is rebuilt from decoded segments without binding raw encoding`() {
        assertEquals(
            "/api/v1/nodes/node%2F01/devices/device%20one/snapshots",
            CanonicalRequestCodec.path(
                "api",
                "v1",
                "nodes",
                "node/01",
                "devices",
                "device one",
                "snapshots",
            ),
        )
    }
}
