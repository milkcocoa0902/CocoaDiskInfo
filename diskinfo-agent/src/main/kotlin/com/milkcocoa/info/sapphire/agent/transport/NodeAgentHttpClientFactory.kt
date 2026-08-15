package com.milkcocoa.info.sapphire.agent.transport

import com.milkcocoa.info.sapphire.agent.credential.validateEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class NodeAgentConnection(
    val endpoint: String,
    val allowInsecureTransport: Boolean = false,
    val pemCaPath: Path? = null,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    fun validate(): NodeAgentConnection {
        val normalizedEndpoint = validateEndpoint(endpoint)
        val scheme = java.net.URI(normalizedEndpoint).scheme.lowercase()
        require(scheme != "http" || allowInsecureTransport) {
            "Plain HTTP requires explicit insecure transport opt-in for the Node Agent connection."
        }
        require(requestTimeoutMillis > 0) { "Node Agent request timeout must be greater than zero." }
        require(pemCaPath == null || scheme == "https") {
            "A PEM CA may only be configured for an HTTPS Node Agent connection."
        }
        return copy(endpoint = normalizedEndpoint, pemCaPath = pemCaPath?.toAbsolutePath()?.normalize())
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
    }
}

fun interface NodeAgentHttpClientFactory {
    fun create(connection: NodeAgentConnection): HttpClient
}

object DefaultNodeAgentHttpClientFactory : NodeAgentHttpClientFactory {
    override fun create(connection: NodeAgentConnection): HttpClient {
        val validated = connection.validate()
        val additionalTrustManager = validated.pemCaPath?.let(::pemTrustManager)
        return HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = validated.requestTimeoutMillis
                connectTimeoutMillis = validated.requestTimeoutMillis
                socketTimeoutMillis = validated.requestTimeoutMillis
            }
            if (additionalTrustManager != null) {
                engine {
                    https {
                        // A private CA augments platform trust; it must not disable the
                        // public trust store or CIO hostname verification.
                        trustManager = CompositeTrustManager(
                            listOf(platformTrustManager(), additionalTrustManager),
                        )
                    }
                }
            }
        }
    }
}

private fun pemTrustManager(path: Path): X509TrustManager {
    if (!Files.isRegularFile(path)) {
        throw NodeAgentTransportException("PEM CA file does not exist or is not a regular file: $path")
    }
    val certificates = runCatching {
        Files.newInputStream(path).use { input ->
            CertificateFactory.getInstance("X.509")
                .generateCertificates(input)
                .map {
                    it as? X509Certificate
                        ?: throw CertificateException("PEM contains a non-X.509 certificate.")
                }
        }
    }.getOrElse { throw NodeAgentTransportException("Failed to load PEM CA file: $path", cause = it) }
    if (certificates.isEmpty()) {
        throw NodeAgentTransportException("PEM CA file does not contain an X.509 certificate: $path")
    }

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    certificates.forEachIndexed { index, certificate ->
        keyStore.setCertificateEntry("cocoadiskinfo-node-pem-ca-$index", certificate)
    }
    return trustManager(keyStore)
}

private fun platformTrustManager(): X509TrustManager = trustManager(null)

private fun trustManager(keyStore: KeyStore?): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
        ?: throw NodeAgentTransportException("X.509 trust manager is unavailable.")
}

private class CompositeTrustManager(
    private val delegates: List<X509TrustManager>,
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { it.acceptedIssuers.asList() }.distinct().toTypedArray()

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        checkTrusted { it.checkClientTrusted(chain, authType) }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        checkTrusted { it.checkServerTrusted(chain, authType) }
    }

    private fun checkTrusted(block: (X509TrustManager) -> Unit) {
        var lastFailure: CertificateException? = null
        delegates.forEach { manager ->
            try {
                block(manager)
                return
            } catch (error: CertificateException) {
                lastFailure = error
            }
        }
        throw lastFailure ?: CertificateException("Certificate chain is not trusted.")
    }
}

class NodeAgentTransportException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
