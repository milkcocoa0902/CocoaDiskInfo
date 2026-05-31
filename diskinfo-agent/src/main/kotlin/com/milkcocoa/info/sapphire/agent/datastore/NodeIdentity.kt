package com.milkcocoa.info.sapphire.agent.datastore

import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object NodeIdentity {
    val nodeName: String by lazy {
        InetAddress.getLocalHost().hostName
    }

    @OptIn(ExperimentalUuidApi::class)
    val nodeId: Uuid by lazy {
        val namespace = UUID.nameUUIDFromBytes("com.milkcocoa.info.sapphire.node".toByteArray())
        val md = MessageDigest.getInstance("SHA-1")
        md.update(namespace.mostSignificantBits.toBytes())
        md.update(namespace.leastSignificantBits.toBytes())
        md.update(nodeName.toByteArray())
        val bytes = md.digest()

        bytes[6] = (bytes[6].toInt() and 0x0f or 0x50).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()

        val buffer = ByteBuffer.wrap(bytes)
        Uuid.fromLongs(buffer.long, buffer.long)
    }

    private fun Long.toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(this)
        return buffer.array()
    }
}
