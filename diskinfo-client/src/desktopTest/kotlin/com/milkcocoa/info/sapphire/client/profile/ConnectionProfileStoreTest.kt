package com.milkcocoa.info.sapphire.client.profile

import java.util.prefs.AbstractPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionProfileStoreTest {
    @Test
    fun `legacy URL migrates without credentials or insecure transport opt-in`() {
        val preferences = MemoryPreferences()
        preferences.put(PreferencesConnectionProfileStore.PREF_KEY_AGENT_URL, "http://host.local:14631")
        val store = PreferencesConnectionProfileStore(preferences)

        val profile = store.load()

        assertEquals(LEGACY_CONNECTION_PROFILE_ID, profile.id)
        assertEquals("http://host.local:14631", profile.baseUrl)
        assertFalse(profile.allowInsecureTransport)
        assertNull(profile.credentialPath)
        assertTrue(preferences.get(PreferencesConnectionProfileStore.PREF_KEY_CONNECTION_PROFILE, null) != null)
    }

    @Test
    fun `paired profile round trips through Preferences`() {
        val preferences = MemoryPreferences()
        val store = PreferencesConnectionProfileStore(preferences)
        val expected = ConnectionProfile(
            id = "hub-a",
            name = "Hub A",
            baseUrl = "https://hub.example",
            credentialPath = "/secure/client.json",
            pemCaPath = "/trust/ca.pem",
        )

        store.save(expected)

        assertEquals(expected, store.load())
        assertEquals(expected.baseUrl, preferences.get(PreferencesConnectionProfileStore.PREF_KEY_AGENT_URL, null))
    }

    @Test
    fun `paired profile requires credential path`() {
        val store = PreferencesConnectionProfileStore(MemoryPreferences())

        assertFailsWith<ConnectionProfileStoreException> {
            store.save(
                ConnectionProfile(
                    id = "hub-a",
                    name = "Hub A",
                    baseUrl = "https://hub.example",
                    credentialPath = null,
                ),
            )
        }
    }

    @Test
    fun `unsupported stored profile version fails closed`() {
        val preferences = MemoryPreferences().apply {
            put(
                PreferencesConnectionProfileStore.PREF_KEY_CONNECTION_PROFILE,
                """{"version":2,"id":"hub-a","name":"Hub A","baseUrl":"https://hub.example","allowInsecureTransport":false,"credentialPath":"/secure/client.json"}""",
            )
        }

        assertFailsWith<ConnectionProfileStoreException> {
            PreferencesConnectionProfileStore(preferences).load()
        }
    }
}

private class MemoryPreferences(
    parent: AbstractPreferences? = null,
    name: String = "",
) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val children = mutableMapOf<String, MemoryPreferences>()

    override fun putSpi(key: String, value: String) {
        values[key] = value
    }

    override fun getSpi(key: String): String? = values[key]

    override fun removeSpi(key: String) {
        values.remove(key)
    }

    override fun removeNodeSpi() {
        values.clear()
        children.clear()
    }

    override fun keysSpi(): Array<String> = values.keys.toTypedArray()

    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()

    override fun childSpi(name: String): AbstractPreferences =
        children.getOrPut(name) { MemoryPreferences(this, name) }

    override fun syncSpi() = Unit

    override fun flushSpi() = Unit
}
