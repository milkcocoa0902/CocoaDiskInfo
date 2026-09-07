package com.milkcocoa.info.sapphire.agent.identity

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Derives a stable storage key from the identifying serial reported by a device. */
interface DeviceKeyDeriver {
    /**
     * Returns the canonical key for a non-blank serial number.
     *
     * Implementations must keep the result stable for the same namespace and serial;
     * callers use it to correlate snapshots without persisting the raw serial as the key.
     */
    fun deriveFromSerial(serialNumber: String): String
}

/** Defaults shared by configuration and the UUIDv5 key derivation implementation. */
object DeviceIdentityDefaults {
    const val NAMESPACE_SALT = "default"
}

/**
 * Produces UUIDv5 keys using a project namespace, a configurable namespace salt, and
 * the normalized serial. Changing the salt intentionally creates a new identity space.
 *
 * @throws IllegalArgumentException if the namespace salt or serial is blank.
 */
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

    /** Derives a deterministic UUID string after trimming the serial number. */
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

        /** Implements RFC 4122 UUIDv5 bytes with SHA-1 and the UUID version/variant bits. */
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
