package com.milkcocoa.info.sapphire.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences

interface ConnectionProfileStore {
    fun load(): ConnectionProfile

    fun save(profile: ConnectionProfile)
}

class ConnectionProfileStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

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
