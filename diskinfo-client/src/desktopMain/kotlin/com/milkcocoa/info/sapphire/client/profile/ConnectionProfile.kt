package com.milkcocoa.info.sapphire.client.profile

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

@Serializable
data class ConnectionProfile(
    val version: Int = CURRENT_CONNECTION_PROFILE_VERSION,
    val id: String,
    val name: String,
    val baseUrl: String,
    val allowInsecureTransport: Boolean = false,
    val credentialPath: String?,
    val pemCaPath: String? = null,
)

internal const val CURRENT_CONNECTION_PROFILE_VERSION = 1
internal const val LEGACY_CONNECTION_PROFILE_ID = "legacy"

internal fun ConnectionProfile.validate(): ConnectionProfile {
    require(version == CURRENT_CONNECTION_PROFILE_VERSION) {
        "Unsupported connection profile version: $version."
    }
    require(id.isNotBlank()) { "Connection profile id must not be blank." }
    require(name.isNotBlank()) { "Connection profile name must not be blank." }
    require(credentialPath != null || id == LEGACY_CONNECTION_PROFILE_ID) {
        "Paired connection profile credential path must not be missing."
    }
    credentialPath?.let {
        require(it.isNotBlank()) { "Connection profile credential path must not be blank." }
    }
    pemCaPath?.let {
        require(it.isNotBlank()) { "Connection profile PEM CA path must not be blank." }
    }

    val endpoint = runCatching { URI(baseUrl.trim()) }
        .getOrElse { throw IllegalArgumentException("Connection profile base URL is invalid.", it) }
    require(endpoint.isAbsolute && endpoint.scheme.lowercase(Locale.ROOT) in setOf("http", "https")) {
        "Connection profile base URL must be an absolute HTTP(S) URL."
    }
    require(!endpoint.host.isNullOrBlank()) { "Connection profile base URL must include a host." }
    require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
        "Connection profile base URL must not include user info, query, or fragment."
    }
    require(endpoint.path.isNullOrEmpty() || endpoint.path == "/") {
        "Connection profile base URL must not include a base path."
    }

    return this
}
