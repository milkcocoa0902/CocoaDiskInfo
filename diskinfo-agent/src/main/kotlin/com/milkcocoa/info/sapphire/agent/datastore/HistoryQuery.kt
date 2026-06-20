package com.milkcocoa.info.sapphire.agent.datastore

import java.time.OffsetDateTime

data class HistoryQuery(
    val limit: Int = DEFAULT_HISTORY_LIMIT,
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val order: HistoryOrder = HistoryOrder.DESC,
)

enum class HistoryOrder {
    ASC,
    DESC,
}

const val DEFAULT_HISTORY_LIMIT = 100
const val MAX_HISTORY_LIMIT = 1000
