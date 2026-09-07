package com.milkcocoa.info.sapphire.agent.datastore

import java.time.OffsetDateTime

/**
 * Bounded raw-history selection for a single node/device pair.
 *
 * [from] and [to] are inclusive collection-time bounds. A null bound is open-ended, but the
 * bounded [limit] still prevents unbounded reads; callers should treat a missing older row as
 * normal once retention has removed it.
 */
data class HistoryQuery(
    val limit: Int = DEFAULT_HISTORY_LIMIT,
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val order: HistoryOrder = HistoryOrder.DESC,
)

/** Ordering applied to the collection-time history cursor. */
enum class HistoryOrder {
    ASC,
    DESC,
}

/** Default number of raw history rows returned by [HistoryQuery]. */
const val DEFAULT_HISTORY_LIMIT = 100

/** Maximum number of raw history rows a single query may request. */
const val MAX_HISTORY_LIMIT = 1000
