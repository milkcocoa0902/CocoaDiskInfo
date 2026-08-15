package com.milkcocoa.info.sapphire.agent.credential

import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.Ed25519PublicJwk
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
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
import java.security.PrivateKey
import java.security.Signature
import java.util.EnumSet
import java.util.Locale

@Serializable
data class NodeAgentCredential(
    val version: Int = CURRENT_NODE_AGENT_CREDENTIAL_VERSION,
    val privateKeyPkcs8: String,
    val publicKeyJwk: Ed25519PublicJwk,
    val kid: String,
    val hubId: String,
    val nodeId: String,
    val nodeName: String,
    val endpoint: String,
)

interface NodeAgentCredentialStore {
    fun load(path: Path): NodeAgentCredential

    fun save(
        path: Path,
        credential: NodeAgentCredential,
    )
}

class NodeAgentCredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class OwnerOnlyJsonNodeAgentCredentialStore(
    private val json: Json = CredentialJson,
) : NodeAgentCredentialStore {
    override fun load(path: Path): NodeAgentCredential {
        val normalizedPath = path.toAbsolutePath().normalize()
        requireSecureRegularFile(normalizedPath)

        val encoded = runCatching { Files.readString(normalizedPath, StandardCharsets.UTF_8) }
            .getOrElse { throw NodeAgentCredentialStoreException("Failed to read Node Agent credential: $normalizedPath", it) }
        val credential = try {
            json.decodeFromString<NodeAgentCredential>(encoded)
        } catch (error: SerializationException) {
            throw NodeAgentCredentialStoreException("Node Agent credential JSON is invalid: $normalizedPath", error)
        }
        return runCatching { validateNodeAgentCredential(credential) }
            .getOrElse {
                throw NodeAgentCredentialStoreException(it.message ?: "Node Agent credential is invalid.", it)
            }
    }

    override fun save(
        path: Path,
        credential: NodeAgentCredential,
    ) {
        val validated = runCatching { validateNodeAgentCredential(credential) }
            .getOrElse {
                throw NodeAgentCredentialStoreException(it.message ?: "Node Agent credential is invalid.", it)
            }
        val normalizedPath = path.toAbsolutePath().normalize()
        val parent = normalizedPath.parent
            ?: throw NodeAgentCredentialStoreException("Node Agent credential path must have a parent directory.")
        runCatching { Files.createDirectories(parent) }
            .getOrElse { throw NodeAgentCredentialStoreException("Failed to create credential directory: $parent", it) }

        val temporary = createOwnerOnlyTemporaryFile(parent, Files.getFileStore(parent))
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
        } catch (error: NodeAgentCredentialStoreException) {
            if (moved) runCatching { Files.deleteIfExists(normalizedPath) }
            throw error
        } catch (error: Exception) {
            if (moved) runCatching { Files.deleteIfExists(normalizedPath) }
            throw NodeAgentCredentialStoreException("Failed to save Node Agent credential: $normalizedPath", error)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun createOwnerOnlyTemporaryFile(
        parent: Path,
        fileStore: FileStore,
    ): Path = when {
        fileStore.supportsFileAttributeView(PosixFileAttributeView::class.java) ->
            Files.createTempFile(parent, ".node-agent-credential-", ".tmp", POSIX_OWNER_ONLY_ATTRIBUTE)

        fileStore.supportsFileAttributeView(AclFileAttributeView::class.java) -> {
            val owner = Files.getOwner(parent, NOFOLLOW_LINKS)
            Files.createTempFile(parent, ".node-agent-credential-", ".tmp", windowsOwnerOnlyAttribute(owner))
        }

        else -> throw NodeAgentCredentialStoreException(
            "The filesystem cannot verify owner-only permissions for Node Agent credentials: $parent",
        )
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
            throw NodeAgentCredentialStoreException("Node Agent credential must be a regular file: $path")
        }

        val fileStore = Files.getFileStore(path)
        when {
            fileStore.supportsFileAttributeView(PosixFileAttributeView::class.java) -> requirePosixOwnerOnly(path)
            fileStore.supportsFileAttributeView(AclFileAttributeView::class.java) -> requireWindowsOwnerOnly(path)
            else -> throw NodeAgentCredentialStoreException(
                "The filesystem cannot verify owner-only permissions for Node Agent credentials: $path",
            )
        }
    }

    private fun requirePosixOwnerOnly(path: Path) {
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        if (PosixFilePermission.OWNER_READ !in permissions ||
            permissions.any { it !in POSIX_OWNER_ONLY_PERMISSIONS }
        ) {
            throw NodeAgentCredentialStoreException(
                "Node Agent credential permissions must be owner-only (0400 or 0600): $path",
            )
        }
    }

    private fun requireWindowsOwnerOnly(path: Path) {
        val owner = Files.getOwner(path, NOFOLLOW_LINKS)
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw NodeAgentCredentialStoreException("Windows ACL is unavailable for Node Agent credential: $path")
        if (!hasOnlyOwnerAccess(owner, view.acl)) {
            throw NodeAgentCredentialStoreException("Node Agent credential ACL must grant access only to its owner: $path")
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

internal const val CURRENT_NODE_AGENT_CREDENTIAL_VERSION = 1

internal fun validateNodeAgentCredential(credential: NodeAgentCredential): NodeAgentCredential {
    require(credential.version == CURRENT_NODE_AGENT_CREDENTIAL_VERSION) {
        "Unsupported Node Agent credential version: ${credential.version}."
    }
    require(credential.hubId.isNotBlank()) { "Node Agent credential hubId must not be blank." }
    require(credential.nodeId.isNotBlank()) { "Node Agent credential nodeId must not be blank." }
    require(credential.nodeName.isNotBlank()) { "Node Agent credential nodeName must not be blank." }
    require(credential.kid.isNotBlank()) { "Node Agent credential kid must not be blank." }
    require(credential.privateKeyPkcs8.isNotBlank()) { "Node Agent credential private key must not be blank." }

    val privateKey = Ed25519Keys.privateKeyFromPkcs8(
        Base64Url.decode(credential.privateKeyPkcs8, "Node Agent credential privateKeyPkcs8"),
    )
    val publicKey = Ed25519Keys.publicKey(credential.publicKeyJwk)
    require(credential.kid == Ed25519Keys.kid(credential.publicKeyJwk)) {
        "Node Agent credential kid must match its public JWK."
    }
    require(keysMatch(privateKey, publicKey)) {
        "Node Agent credential private key must match its public JWK."
    }

    val endpoint = validateEndpoint(credential.endpoint)
    return credential.copy(endpoint = endpoint)
}

internal fun NodeAgentCredential.privateKey(): PrivateKey =
    Ed25519Keys.privateKeyFromPkcs8(Base64Url.decode(privateKeyPkcs8, "Node Agent credential privateKeyPkcs8"))

internal fun validateEndpoint(value: String): String {
    val normalized = value.trim().trimEnd('/')
    val endpoint = runCatching { URI(normalized) }
        .getOrElse { throw IllegalArgumentException("Node Agent endpoint is invalid.", it) }
    require(endpoint.isAbsolute && endpoint.scheme.lowercase(Locale.ROOT) in setOf("http", "https")) {
        "Node Agent endpoint must be an absolute HTTP(S) URL."
    }
    require(!endpoint.host.isNullOrBlank()) { "Node Agent endpoint must include a host." }
    require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
        "Node Agent endpoint must not include user info, query, or fragment."
    }
    require(endpoint.path.isNullOrEmpty() || endpoint.path == "/") {
        "Node Agent endpoint must not include a base path."
    }
    return normalized
}

internal fun hasOnlyOwnerAccess(
    owner: UserPrincipal,
    entries: List<AclEntry>,
): Boolean {
    val allowedEntries = entries.filter { it.type() == AclEntryType.ALLOW }
    val ownerEntries = allowedEntries.filter { it.principal() == owner }
    return ownerEntries.any { AclEntryPermission.READ_DATA in it.permissions() } &&
        allowedEntries.none { it.principal() != owner && it.permissions().isNotEmpty() }
}

private fun keysMatch(
    privateKey: PrivateKey,
    publicKey: java.security.PublicKey,
): Boolean {
    val message = "cocoadiskinfo.node-credential.v1".toByteArray(StandardCharsets.US_ASCII)
    val signature = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(message)
        sign()
    }
    return Signature.getInstance("Ed25519").run {
        initVerify(publicKey)
        update(message)
        verify(signature)
    }
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
