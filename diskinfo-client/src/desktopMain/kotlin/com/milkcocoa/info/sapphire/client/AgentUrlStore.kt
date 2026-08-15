package com.milkcocoa.info.sapphire.client

import java.util.prefs.Preferences

object AgentUrlStore {
    private const val PREF_KEY_AGENT_URL = "agent_url"
    private const val PREF_KEY_REFRESH_INTERVAL = "refresh_interval"
    private const val DEFAULT_AGENT_URL = "http://localhost:14631"
    private const val DEFAULT_REFRESH_INTERVAL = 60L

    private val prefs: Preferences by lazy {
        Preferences.userNodeForPackage(AgentUrlStore::class.java)
    }
    private val connectionProfiles: ConnectionProfileStore by lazy {
        PreferencesConnectionProfileStore(prefs)
    }

    var agentUrl: String
        get() = prefs.get(PREF_KEY_AGENT_URL, DEFAULT_AGENT_URL)
        set(value) {
            prefs.put(PREF_KEY_AGENT_URL, value)
            prefs.flush()

            // Settingsは入力途中の値も保存するため、完全なURLだけversioned profileへ反映する。
            runCatching {
                connectionProfiles.load().copy(baseUrl = value).validate()
            }.getOrNull()?.let(connectionProfiles::save)
        }

    var refreshIntervalSeconds: Long
        get() = prefs.getLong(PREF_KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
        set(value) {
            prefs.putLong(PREF_KEY_REFRESH_INTERVAL, value)
            prefs.flush()
        }
}
