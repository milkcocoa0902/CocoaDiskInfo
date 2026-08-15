package com.milkcocoa.info.sapphire.agent.transport

import com.milkcocoa.info.sapphire.agent.credential.NodeAgentCredential
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeAgentHttpClientFactoryTest {
    @Test
    fun `plain HTTP requires explicit opt-in`() {
        val error = assertFailsWith<IllegalArgumentException> {
            NodeAgentConnection("http://hub.local:14631").validate()
        }

        assertTrue(error.message.orEmpty().contains("explicit insecure transport opt-in"))
        assertEquals(
            "http://hub.local:14631",
            NodeAgentConnection("http://hub.local:14631/", allowInsecureTransport = true)
                .validate()
                .endpoint,
        )
    }

    @Test
    fun `PEM CA is accepted only for HTTPS and invalid files fail before connecting`() {
        assertFailsWith<IllegalArgumentException> {
            NodeAgentConnection(
                endpoint = "http://hub.local:14631",
                allowInsecureTransport = true,
                pemCaPath = Path.of("private-ca.pem"),
            ).validate()
        }

        val error = assertFailsWith<NodeAgentTransportException> {
            DefaultNodeAgentHttpClientFactory.create(
                NodeAgentConnection(
                    endpoint = "https://hub.example",
                    pemCaPath = Path.of("missing-private-ca.pem"),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("does not exist"))
    }

    @Test
    fun `signed transport assembly validates opt-in against the credential endpoint`() {
        val keyPair = Ed25519Keys.generate()
        val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
        val credential = NodeAgentCredential(
            privateKeyPkcs8 = Base64Url.encode(keyPair.private.encoded),
            publicKeyJwk = publicJwk,
            kid = Ed25519Keys.kid(publicJwk),
            hubId = "hub-a",
            nodeId = "node-a",
            nodeName = "Node A",
            endpoint = "http://hub.local:14631",
        )
        var factoryCalls = 0

        assertFailsWith<IllegalArgumentException> {
            SignedNodeAgentTransport.create(
                credential = credential,
                connection = NodeAgentConnection("http://hub.local:14631"),
                httpClientFactory = NodeAgentHttpClientFactory {
                    factoryCalls += 1
                    error("Must not create a client.")
                },
            )
        }
        assertEquals(0, factoryCalls)
    }
}
