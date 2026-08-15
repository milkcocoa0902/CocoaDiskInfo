package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.core.auth.AuthProtocolException
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class LatestPageCursor(
    val nodeId: Uuid,
    val deviceKey: String,
) {
    init {
        require(deviceKey.isNotBlank()) { "Cursor device key must not be blank." }
    }
}

object LatestPageCursorCodec {
    private const val VERSION: Byte = 1
    private const val UUID_BYTES = 16
    private const val LENGTH_BYTES = Int.SIZE_BYTES

    @OptIn(ExperimentalUuidApi::class)
    fun encode(cursor: LatestPageCursor): String {
        val deviceKey = cursor.deviceKey.toByteArray(StandardCharsets.UTF_8)
        require(deviceKey.isNotEmpty() && deviceKey.size <= 255) {
            "Cursor device key must contain between 1 and 255 UTF-8 bytes."
        }
        val bytes = ByteBuffer.allocate(1 + UUID_BYTES + LENGTH_BYTES + deviceKey.size)
            .put(VERSION)
            .putLong(cursor.nodeId.toLongs { mostSignificantBits, _ -> mostSignificantBits })
            .putLong(cursor.nodeId.toLongs { _, leastSignificantBits -> leastSignificantBits })
            .putInt(deviceKey.size)
            .put(deviceKey)
            .array()
        return Base64Url.encode(bytes)
    }

    @OptIn(ExperimentalUuidApi::class)
    fun decode(value: String): LatestPageCursor {
        val bytes = Base64Url.decode(value, "cursor")
        if (bytes.size < 1 + UUID_BYTES + LENGTH_BYTES || bytes[0] != VERSION) {
            throw AuthProtocolException("Cursor version or length is invalid.")
        }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val nodeId = Uuid.fromLongs(buffer.long, buffer.long)
        val deviceKeyLength = buffer.int
        if (deviceKeyLength !in 1..255 || buffer.remaining() != deviceKeyLength) {
            throw AuthProtocolException("Cursor device key length is invalid.")
        }
        val deviceKeyBytes = ByteArray(deviceKeyLength).also(buffer::get)
        val deviceKey = try {
            deviceKeyBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (error: IllegalArgumentException) {
            throw AuthProtocolException("Cursor device key must be valid UTF-8.", error)
        }
        val cursor = LatestPageCursor(nodeId, deviceKey)
        // Re-encoding rejects alternate binary/text representations before the
        // cursor is copied into the signed canonical query.
        if (encode(cursor) != value) throw AuthProtocolException("Cursor is not canonical.")
        return cursor
    }
}
