package com.milkcocoa.info.sapphire.client.credential

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

class ClientCredentialStoreTest {
    private val keyPair = Ed25519Keys.generate()
    private val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
    private val credential = ClientCredential(
        hubId = "hub-a",
        endpoint = "https://hub.example",
        kid = Ed25519Keys.kid(publicJwk),
        privateKeyPkcs8 = Base64Url.encode(keyPair.private.encoded),
        publicKeyJwk = publicJwk,
        principalType = CredentialPrincipalType.CLIENT,
    )

    @Test
    fun `credential round trips with owner-only permissions`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("client.json")
        val store = OwnerOnlyJsonClientCredentialStore()

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
    fun `credential load rejects broad POSIX permissions`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("client.json")
        val store = OwnerOnlyJsonClientCredentialStore()
        store.save(path, credential)
        if (!Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            return@withTemporaryDirectory
        }
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
            ),
        )

        assertFailsWith<ClientCredentialStoreException> { store.load(path) }
    }

    @Test
    fun `credential load accepts owner read-only POSIX permissions`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("client.json")
        val store = OwnerOnlyJsonClientCredentialStore()
        store.save(path, credential)
        if (!Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            return@withTemporaryDirectory
        }
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))

        assertEquals(credential, store.load(path))
    }

    @Test
    fun `client store rejects Node Agent credentials`() = withTemporaryDirectory { directory ->
        assertFailsWith<ClientCredentialStoreException> {
            OwnerOnlyJsonClientCredentialStore().save(
                directory.resolve("node.json"),
                credential.copy(
                    principalType = CredentialPrincipalType.NODE_AGENT,
                    nodeId = "node-a",
                ),
            )
        }
    }

    @Test
    fun `credential load rejects unsupported version`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("client.json")
        val store = OwnerOnlyJsonClientCredentialStore()
        store.save(path, credential)
        val unsupported = Files.readString(path).replace("\"version\":1", "\"version\":2")
        Files.writeString(path, unsupported)

        assertFailsWith<ClientCredentialStoreException> { store.load(path) }
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
    val directory = Files.createTempDirectory("cocoadiskinfo-client-test-")
    try {
        block(directory)
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Path::deleteIfExists)
        }
    }
}
