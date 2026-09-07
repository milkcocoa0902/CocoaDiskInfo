package com.milkcocoa.info.sapphire.client.profile

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

/** Persisted endpoint and local material needed to authenticate one Hub/Agent connection. */
@Serializable
data class ConnectionProfile(
    /** Serialized profile schema revision. */
    val version: Int = CURRENT_CONNECTION_PROFILE_VERSION,
    /** Stable node/Hub identity used to select this profile. */
    val id: String,
    /** Operator-facing profile name. */
    val name: String,
    /** Absolute HTTP(S) endpoint with host and optional port, but no base path/query/fragment. */
    val baseUrl: String,
    /** Explicit opt-in permitting clear-text HTTP for this profile. */
    val allowInsecureTransport: Boolean = false,
    /** Owner-only credential file containing the Ed25519 signing key. */
    val credentialPath: String?,
    /** Optional PEM CA file that supplements platform trust for HTTPS. */
    val pemCaPath: String? = null,
)

internal const val CURRENT_CONNECTION_PROFILE_VERSION = 1
internal const val LEGACY_CONNECTION_PROFILE_ID = "legacy"

/**
 * Validates a profile without normalizing its stored value.
 *
 * A non-legacy profile must name a credential; only the legacy profile ID may temporarily lack
 * one. The endpoint is restricted to an absolute HTTP(S) URL with a host and optional port, but
 * no base path/query/fragment, so API paths cannot be accidentally duplicated or signed differently
 * from the requested URL.
 */
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
