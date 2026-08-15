package com.milkcocoa.info.sapphire.agent.credential

import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeAgentCredentialStoreTest {
    private val keyPair = Ed25519Keys.generate()
    private val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
    private val credential = NodeAgentCredential(
        privateKeyPkcs8 = Base64Url.encode(keyPair.private.encoded),
        publicKeyJwk = publicJwk,
        kid = Ed25519Keys.kid(publicJwk),
        hubId = "hub-a",
        nodeId = "node-a",
        nodeName = "Node A",
        endpoint = "https://hub.example",
    )

    @Test
    fun `credential round trips with owner-only permissions`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("node.json")
        val store = OwnerOnlyJsonNodeAgentCredentialStore()

        store.save(path, credential)

        assertEquals(credential, store.load(path))
        if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path),
            )
        }
    }

    @Test
    fun `credential load accepts owner read-only and rejects broad POSIX permissions`() =
        withTemporaryDirectory { directory ->
            val path = directory.resolve("node.json")
            val store = OwnerOnlyJsonNodeAgentCredentialStore()
            store.save(path, credential)
            if (!Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
                return@withTemporaryDirectory
            }

            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))
            assertEquals(credential, store.load(path))

            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                ),
            )
            assertFailsWith<NodeAgentCredentialStoreException> { store.load(path) }
        }

    @Test
    fun `credential rejects a private key that does not match its public JWK`() = withTemporaryDirectory { directory ->
        val otherKey = Ed25519Keys.generate()

        assertFailsWith<NodeAgentCredentialStoreException> {
            OwnerOnlyJsonNodeAgentCredentialStore().save(
                directory.resolve("node.json"),
                credential.copy(privateKeyPkcs8 = Base64Url.encode(otherKey.private.encoded)),
            )
        }
    }

    @Test
    fun `credential load fails closed for unsupported version and symlink`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("node.json")
        val link = directory.resolve("node-link.json")
        val store = OwnerOnlyJsonNodeAgentCredentialStore()
        store.save(path, credential)
        Files.writeString(path, Files.readString(path).replace("\"version\":1", "\"version\":2"))

        assertFailsWith<NodeAgentCredentialStoreException> { store.load(path) }

        runCatching { Files.createSymbolicLink(link, path.fileName) }.getOrElse { return@withTemporaryDirectory }
        assertFailsWith<NodeAgentCredentialStoreException> { store.load(link) }
    }

    @Test
    fun `Windows ACL evaluator rejects access granted to another principal`() {
        val owner = TestPrincipal("owner")
        val other = TestPrincipal("other")
        val ownerEntry = allowRead(owner)

        assertTrue(hasOnlyOwnerAccess(owner, listOf(ownerEntry)))
        assertFalse(hasOnlyOwnerAccess(owner, listOf(ownerEntry, allowRead(other))))
    }
}

private fun allowRead(principal: UserPrincipal): AclEntry = AclEntry.newBuilder()
    .setType(AclEntryType.ALLOW)
    .setPrincipal(principal)
    .setPermissions(AclEntryPermission.READ_DATA)
    .build()

private data class TestPrincipal(
    private val value: String,
) : UserPrincipal {
    override fun getName(): String = value
}

private fun withTemporaryDirectory(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("cocoadiskinfo-node-credential-test-")
    try {
        block(directory)
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Path::deleteIfExists)
        }
    }
}
