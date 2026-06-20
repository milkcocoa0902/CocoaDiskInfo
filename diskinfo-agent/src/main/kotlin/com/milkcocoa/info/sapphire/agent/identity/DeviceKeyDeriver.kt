package com.milkcocoa.info.sapphire.agent.identity

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

interface DeviceKeyDeriver {
    fun deriveFromSerial(serialNumber: String): String
}

object DeviceIdentityDefaults {
    const val NAMESPACE_SALT = "default"
}

class UuidV5DeviceKeyDeriver(
    namespaceSalt: String = DeviceIdentityDefaults.NAMESPACE_SALT,
) : DeviceKeyDeriver {
    private val effectiveNamespace: UUID

    init {
        require(namespaceSalt.isNotBlank()) {
            "Device identity namespace salt must not be blank."
        }
        effectiveNamespace = uuidV5(
            namespace = COCOADISKINFO_DEVICE_NAMESPACE,
            name = namespaceSalt,
        )
    }

    override fun deriveFromSerial(serialNumber: String): String {
        val normalizedSerialNumber = serialNumber.trim()
        require(normalizedSerialNumber.isNotBlank()) {
            "Device serial number must not be blank."
        }
        return uuidV5(
            namespace = effectiveNamespace,
            name = "serial:v1:$normalizedSerialNumber",
        ).toString()
    }

    private companion object {
        private val COCOADISKINFO_DEVICE_NAMESPACE = uuidV5(
            namespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            name = "com.milkcocoa.info.sapphire.device",
        )

        private fun uuidV5(namespace: UUID, name: String): UUID {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(namespace.mostSignificantBits.toBytes())
            md.update(namespace.leastSignificantBits.toBytes())
            md.update(name.toByteArray(Charsets.UTF_8))
            val bytes = md.digest()

            bytes[6] = (bytes[6].toInt() and 0x0f or 0x50).toByte()
            bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()

            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long)
        }

        private fun Long.toBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
            buffer.putLong(this)
            return buffer.array()
        }
    }
}
