package com.milkcocoa.info.sapphire.client.api

import com.milkcocoa.info.sapphire.client.profile.ConnectionProfile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConnectionHttpClientFactoryTest {
    @Test
    fun `HTTPS uses platform trust when PEM CA is absent`() {
        val client = DefaultConnectionHttpClientFactory.create(
            ConnectionProfile(
                id = "hub-a",
                name = "Hub A",
                baseUrl = "https://hub.example",
                credentialPath = "/credentials/client.json",
            ),
        )

        client.close()
    }

    @Test
    fun `invalid PEM CA fails instead of falling back to trust-all`() {
        val pem = Files.createTempFile("cocoadiskinfo-invalid-ca-", ".pem")
        try {
            Files.writeString(pem, "not a certificate")

            assertFailsWith<AgentApiException> {
                DefaultConnectionHttpClientFactory.create(
                    ConnectionProfile(
                        id = "hub-a",
                        name = "Hub A",
                        baseUrl = "https://hub.example",
                        credentialPath = "/credentials/client.json",
                        pemCaPath = pem.toString(),
                    ),
                )
            }
        } finally {
            Files.deleteIfExists(pem)
        }
    }
}
