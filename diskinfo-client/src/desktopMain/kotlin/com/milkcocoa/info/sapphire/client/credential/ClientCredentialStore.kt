package com.milkcocoa.info.sapphire.client.credential

import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.Ed25519PublicJwk
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

@Serializable
data class ClientCredential(
    val version: Int = CURRENT_CLIENT_CREDENTIAL_VERSION,
    val hubId: String,
    val endpoint: String,
    val kid: String,
    val privateKeyPkcs8: String,
    val publicKeyJwk: Ed25519PublicJwk,
    val principalType: CredentialPrincipalType,
    val nodeId: String? = null,
)

@Serializable
enum class CredentialPrincipalType {
    CLIENT,
    NODE_AGENT,
}

interface ClientCredentialStore {
    fun load(path: Path): ClientCredential

    fun save(
        path: Path,
        credential: ClientCredential,
    )
}

class ClientCredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class OwnerOnlyJsonClientCredentialStore(
    private val json: Json = CredentialJson,
) : ClientCredentialStore {
    override fun load(path: Path): ClientCredential {
        val normalizedPath = path.toAbsolutePath().normalize()
        requireSecureRegularFile(normalizedPath)

        val encoded = runCatching { Files.readString(normalizedPath, StandardCharsets.UTF_8) }
            .getOrElse { throw ClientCredentialStoreException("Failed to read client credential file: $normalizedPath", it) }
        val credential = try {
            json.decodeFromString<ClientCredential>(encoded)
        } catch (error: SerializationException) {
            throw ClientCredentialStoreException("Client credential JSON is invalid: $normalizedPath", error)
        }
        return runCatching { validateClientCredential(credential) }
            .getOrElse {
                throw ClientCredentialStoreException(it.message ?: "Client credential is invalid.", it)
            }
    }

    override fun save(
        path: Path,
        credential: ClientCredential,
    ) {
        val validated = runCatching { validateClientCredential(credential) }
            .getOrElse {
                throw ClientCredentialStoreException(it.message ?: "Client credential is invalid.", it)
            }
        val normalizedPath = path.toAbsolutePath().normalize()
        val parent = normalizedPath.parent
            ?: throw ClientCredentialStoreException("Client credential path must have a parent directory.")
        runCatching { Files.createDirectories(parent) }
            .getOrElse { throw ClientCredentialStoreException("Failed to create client credential directory: $parent", it) }

        val fileStore = Files.getFileStore(parent)
        val temporary = createOwnerOnlyTemporaryFile(parent, fileStore)
        var moved = false
        try {
            requireSecureRegularFile(temporary)
            if (Files.exists(normalizedPath, NOFOLLOW_LINKS)) {
                requireSecureRegularFile(normalizedPath)
            }
            Files.writeString(
                temporary,
                json.encodeToString(validated),
                StandardCharsets.UTF_8,
                WRITE,
                TRUNCATE_EXISTING,
            )
            moveReplacing(temporary, normalizedPath)
            moved = true
            requireSecureRegularFile(normalizedPath)
        } catch (error: ClientCredentialStoreException) {
            if (moved) runCatching { Files.deleteIfExists(normalizedPath) }
            throw error
        } catch (error: Exception) {
            if (moved) runCatching { Files.deleteIfExists(normalizedPath) }
            throw ClientCredentialStoreException("Failed to save client credential file: $normalizedPath", error)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun createOwnerOnlyTemporaryFile(
        parent: Path,
        fileStore: FileStore,
    ): Path {
        return when {
            fileStore.supportsFileAttributeView(PosixFileAttributeView::class.java) -> {
                Files.createTempFile(parent, ".client-credential-", ".tmp", POSIX_OWNER_ONLY_ATTRIBUTE)
            }

            fileStore.supportsFileAttributeView(AclFileAttributeView::class.java) -> {
                val owner = Files.getOwner(parent, NOFOLLOW_LINKS)
                Files.createTempFile(parent, ".client-credential-", ".tmp", windowsOwnerOnlyAttribute(owner))
            }

            else -> throw ClientCredentialStoreException(
                "The filesystem cannot verify owner-only permissions for client credentials: $parent",
            )
        }
    }

    private fun moveReplacing(
        source: Path,
        target: Path,
    ) {
        runCatching { Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            .recoverCatching { Files.move(source, target, REPLACE_EXISTING) }
            .getOrThrow()
    }

    private fun requireSecureRegularFile(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw ClientCredentialStoreException("Client credential must be a regular file: $path")
        }

        val fileStore = Files.getFileStore(path)
        when {
            fileStore.supportsFileAttributeView(PosixFileAttributeView::class.java) -> requirePosixOwnerOnly(path)
            fileStore.supportsFileAttributeView(AclFileAttributeView::class.java) -> requireWindowsOwnerOnly(path)
            else -> throw ClientCredentialStoreException(
                "The filesystem cannot verify owner-only permissions for client credentials: $path",
            )
        }
    }

    private fun requirePosixOwnerOnly(path: Path) {
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        if (PosixFilePermission.OWNER_READ !in permissions ||
            permissions.any { it !in POSIX_OWNER_ONLY_PERMISSIONS }
        ) {
            throw ClientCredentialStoreException(
                "Client credential permissions must be owner-only (0400 or 0600): $path",
            )
        }
    }

    private fun requireWindowsOwnerOnly(path: Path) {
        val owner = Files.getOwner(path, NOFOLLOW_LINKS)
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw ClientCredentialStoreException("Windows ACL is unavailable for client credential: $path")
        if (!hasOnlyOwnerAccess(owner, view.acl)) {
            throw ClientCredentialStoreException("Client credential ACL must grant access only to its owner: $path")
        }
    }

    companion object {
        private val CredentialJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        private val POSIX_OWNER_ONLY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val POSIX_OWNER_ONLY_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(POSIX_OWNER_ONLY_PERMISSIONS)
    }
}

internal const val CURRENT_CLIENT_CREDENTIAL_VERSION = 1

internal fun hasOnlyOwnerAccess(
    owner: UserPrincipal,
    entries: List<AclEntry>,
): Boolean {
    val allowedEntries = entries.filter { it.type() == AclEntryType.ALLOW }
    val ownerEntries = allowedEntries.filter { it.principal() == owner }
    return ownerEntries.any { AclEntryPermission.READ_DATA in it.permissions() } &&
        allowedEntries.none { it.principal() != owner && it.permissions().isNotEmpty() }
}

private fun windowsOwnerOnlyAttribute(owner: UserPrincipal): FileAttribute<List<AclEntry>> {
    val entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        .build()
    return object : FileAttribute<List<AclEntry>> {
        override fun name(): String = "acl:acl"

        override fun value(): List<AclEntry> = listOf(entry)
    }
}

private fun validateClientCredential(credential: ClientCredential): ClientCredential {
    require(credential.version == CURRENT_CLIENT_CREDENTIAL_VERSION) {
        "Unsupported client credential version: ${credential.version}."
    }
    require(credential.hubId.isNotBlank()) { "Client credential hubId must not be blank." }
    require(credential.endpoint.isNotBlank()) { "Client credential endpoint must not be blank." }
    require(credential.kid.isNotBlank()) { "Client credential kid must not be blank." }
    require(credential.privateKeyPkcs8.isNotBlank()) { "Client credential private key must not be blank." }
    Ed25519Keys.privateKeyFromPkcs8(
        Base64Url.decode(credential.privateKeyPkcs8, "Client credential privateKeyPkcs8"),
    )
    require(credential.kid == Ed25519Keys.kid(credential.publicKeyJwk)) {
        "Client credential kid must match its public JWK."
    }
    require(credential.principalType == CredentialPrincipalType.CLIENT) {
        "Client credential principal type must be CLIENT."
    }
    require(credential.nodeId == null) { "Client credential nodeId must be absent." }
    return credential
}
