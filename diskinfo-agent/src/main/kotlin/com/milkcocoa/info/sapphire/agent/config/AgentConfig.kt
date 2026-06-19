package com.milkcocoa.info.sapphire.agent.config

data class AgentConfig(
    val smartctl: SmartctlConfig = SmartctlConfig(),
    val output: OutputConfig = OutputConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    val storage: StorageConfig = StorageConfig(),
    val http: HttpConfig = HttpConfig(),
) {
    data class SmartctlConfig(
        val scan: Boolean? = null,
        val device: String? = null,
    )

    data class OutputConfig(
        val mode: String? = null,
    )

    data class RuntimeConfig(
        val persist: Boolean? = null,
        val intervalSeconds: Long? = null,
    )

    data class StorageConfig(
        val jdbcUrl: String? = null,
    )

    data class HttpConfig(
        val host: String? = null,
        val port: Int? = null,
    )
}

object AgentConfigDefaults {
    const val DEFAULT_CONFIG_PATH = "/etc/cocoadiskinfo/agent.toml"
    const val JDBC_URL = "jdbc:sqlite:./sapphire.db"
    const val HTTP_HOST = "127.0.0.1"
    const val HTTP_PORT = 14631
    const val COLLECTION_INTERVAL_SECONDS = 60L
}
