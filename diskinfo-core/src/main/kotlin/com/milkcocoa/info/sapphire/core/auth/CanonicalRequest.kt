package com.milkcocoa.info.sapphire.core.auth

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

data class CanonicalRequest(
    val method: String,
    val path: String,
    val query: String,
    val purpose: AuthPurpose,
    val bodySha256: String? = null,
) {
    init {
        require(method == method.uppercase(Locale.ROOT) && method.isNotBlank()) {
            "Canonical HTTP method must be uppercase."
        }
        require(path.startsWith('/') && '?' !in path && '#' !in path) {
            "Canonical API path must be absolute and must not contain a query or fragment."
        }
        require(!query.startsWith('?')) { "Canonical query must not include a leading '?'." }
        bodySha256?.let { Base64Url.decodeExact(it, expectedSize = 32, fieldName = "bodySha256") }
    }

    fun signedPayload(nonce: String): SignedRequestPayload {
        Base64Url.decodeExact(nonce, expectedSize = 32, fieldName = "nonce")
        return SignedRequestPayload(
            nonce = nonce,
            method = method,
            path = path,
            query = query,
            purpose = purpose,
            bodySha256 = bodySha256,
        )
    }
}

object CanonicalRequestCodec {
    fun method(value: String): String {
        require(value.isNotBlank()) { "HTTP method must not be blank." }
        return value.uppercase(Locale.ROOT)
    }

    fun path(vararg decodedSegments: String): String {
        require(decodedSegments.isNotEmpty()) { "Canonical path requires at least one segment." }
        require(decodedSegments.none(String::isBlank)) { "Canonical path segments must not be blank." }

        // Rebuilding from decoded, validated route values makes a proof independent of
        // equivalent raw percent encodings while still keeping '/' inside a value escaped.
        return decodedSegments.joinToString(separator = "/", prefix = "/") { percentEncode(it) }
    }

    fun query(effectiveValues: Map<String, String?>): String {
        require(effectiveValues.keys.none(String::isBlank)) { "Canonical query names must not be blank." }

        // Defaults belong in effectiveValues. Sorting here means raw query order never
        // becomes part of the signature contract.
        return effectiveValues
            .asSequence()
            .filter { it.value != null }
            .sortedBy { it.key }
            .joinToString("&") { (name, value) ->
                "${percentEncode(name)}=${percentEncode(checkNotNull(value))}"
            }
    }

    fun validateRawQueryShape(
        rawParameters: List<Pair<String, String>>,
        knownNames: Set<String>,
    ) {
        val unknownNames = rawParameters.map(Pair<String, String>::first).filterNot(knownNames::contains).toSet()
        require(unknownNames.isEmpty()) {
            "Unknown query parameter(s): ${unknownNames.sorted().joinToString()}"
        }

        val duplicateNames = rawParameters.groupingBy(Pair<String, String>::first)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate query parameter(s): ${duplicateNames.sorted().joinToString()}"
        }
    }

    private fun percentEncode(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream(bytes.size)
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if (unsigned.isUnreserved()) {
                output.write(unsigned)
            } else {
                output.write('%'.code)
                output.write(HEX[unsigned ushr 4].code)
                output.write(HEX[unsigned and 0x0f].code)
            }
        }
        return output.toString(StandardCharsets.US_ASCII)
    }

    private fun Int.isUnreserved(): Boolean =
        this in 'A'.code..'Z'.code ||
            this in 'a'.code..'z'.code ||
            this in '0'.code..'9'.code ||
            this == '-'.code || this == '.'.code || this == '_'.code || this == '~'.code

    private const val HEX = "0123456789ABCDEF"
}
