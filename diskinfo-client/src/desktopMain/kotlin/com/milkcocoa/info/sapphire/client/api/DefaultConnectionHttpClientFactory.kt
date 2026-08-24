package com.milkcocoa.info.sapphire.client.api

import com.milkcocoa.info.sapphire.client.profile.ConnectionProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal object DefaultConnectionHttpClientFactory : ConnectionHttpClientFactory {
    override fun create(profile: ConnectionProfile): HttpClient {
        validateTransportOptIn(profile)
        val additionalTrustManager = profile.pemCaPath?.let(::pemTrustManager)
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(ClientJson)
            }
            if (additionalTrustManager != null) {
                engine {
                    https {
                        // private CAを追加してもplatform trustとhostname verificationは維持する。
                        trustManager = CompositeTrustManager(
                            listOf(platformTrustManager(), additionalTrustManager),
                        )
                    }
                }
            }
        }
    }
}

internal fun validateTransportOptIn(profile: ConnectionProfile) {
    val scheme = java.net.URI(profile.baseUrl).scheme.lowercase(java.util.Locale.ROOT)
    if (scheme == "http" && !profile.allowInsecureTransport) {
        throw AgentApiException(
            "Plain HTTP requires explicit insecure transport opt-in in the connection profile.",
        )
    }
}

private fun pemTrustManager(pathValue: String): X509TrustManager {
    val path = Path.of(pathValue).toAbsolutePath().normalize()
    if (!Files.isRegularFile(path)) {
        throw AgentApiException("PEM CA file does not exist or is not a regular file: $path")
    }
    val certificates = runCatching {
        Files.newInputStream(path).use { input ->
            CertificateFactory.getInstance("X.509")
                .generateCertificates(input)
                .map { it as? X509Certificate ?: throw CertificateException("PEM contains a non-X.509 certificate.") }
        }
    }.getOrElse { throw AgentApiException("Failed to load PEM CA file: $path", cause = it) }
    if (certificates.isEmpty()) {
        throw AgentApiException("PEM CA file does not contain an X.509 certificate: $path")
    }

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    certificates.forEachIndexed { index, certificate ->
        keyStore.setCertificateEntry("cocoadiskinfo-pem-ca-$index", certificate)
    }
    return trustManager(keyStore)
}

private fun platformTrustManager(): X509TrustManager = trustManager(null)

private fun trustManager(keyStore: KeyStore?): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
        ?: throw AgentApiException("X.509 trust manager is unavailable.")
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
