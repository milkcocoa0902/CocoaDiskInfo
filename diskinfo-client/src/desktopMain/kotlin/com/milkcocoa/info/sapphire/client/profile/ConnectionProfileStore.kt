package com.milkcocoa.info.sapphire.client.profile

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences

interface ConnectionProfileStore {
    /** Loads the current profile, migrating the legacy URL preference when necessary. */
    fun load(): ConnectionProfile

    /** Validates and durably writes the current profile and its legacy URL mirror. */
    fun save(profile: ConnectionProfile)
}

/** Signals invalid stored profile data or an unavailable Java Preferences backend. */
class ConnectionProfileStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Stores one versioned profile in the Java user preference node.
 *
 * The old URL-only preference is migrated to an unpaired legacy profile; no credential or
 * insecure-HTTP permission is inferred during migration. Invalid stored JSON is reported rather
 * than silently replaced.
 */
class PreferencesConnectionProfileStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(AgentUrlStore::class.java),
    private val json: Json = ProfileJson,
) : ConnectionProfileStore {
    override fun load(): ConnectionProfile {
        val storedProfile = preferences.get(PREF_KEY_CONNECTION_PROFILE, null)
        if (storedProfile != null) {
            return decodeProfile(storedProfile)
        }

        // 旧URLからcredentialやHTTP opt-inを推測せず、再登録が必要な状態として移行する。
        val migratedProfile = runCatching {
            ConnectionProfile(
                id = LEGACY_CONNECTION_PROFILE_ID,
                name = "Legacy Agent",
                baseUrl = preferences.get(PREF_KEY_AGENT_URL, DEFAULT_AGENT_URL),
                allowInsecureTransport = false,
                credentialPath = null,
            ).validate()
        }.getOrElse {
            throw ConnectionProfileStoreException(
                "Legacy Agent URL cannot be migrated: ${it.message ?: "invalid URL"}",
                it,
            )
        }
        save(migratedProfile)
        return migratedProfile
    }

    override fun save(profile: ConnectionProfile) {
        val validated = runCatching { profile.validate() }
            .getOrElse { throw ConnectionProfileStoreException(it.message ?: "Connection profile is invalid.", it) }
        runCatching {
            preferences.put(PREF_KEY_CONNECTION_PROFILE, json.encodeToString(validated))
            preferences.put(PREF_KEY_AGENT_URL, validated.baseUrl)
            preferences.flush()
        }.getOrElse {
            throw ConnectionProfileStoreException("Failed to save the connection profile.", it)
        }
    }

    /** Decodes and validates stored JSON while converting parser failures to store errors. */
    private fun decodeProfile(value: String): ConnectionProfile {
        return try {
            json.decodeFromString<ConnectionProfile>(value).validate()
        } catch (error: SerializationException) {
            throw ConnectionProfileStoreException("Stored connection profile JSON is invalid.", error)
        } catch (error: IllegalArgumentException) {
            throw ConnectionProfileStoreException(error.message ?: "Stored connection profile is invalid.", error)
        }
    }

    companion object {
        internal const val PREF_KEY_CONNECTION_PROFILE = "connection_profile_json"
        internal const val PREF_KEY_AGENT_URL = "agent_url"
        internal const val DEFAULT_AGENT_URL = "http://localhost:14631"

        private val ProfileJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
