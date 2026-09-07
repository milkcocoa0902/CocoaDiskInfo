package com.milkcocoa.info.sapphire.agent.datastore

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.Connection

/**
 * Runs repository work with one Exposed transaction boundary.
 *
 * The read-only/read-write names communicate application intent; the current SQLite-compatible
 * implementation uses the same JDBC transaction mode for both and commits or rolls back the
 * complete [block] atomically.
 */
interface TransactionRunner {
    /** Executes [block] in a transaction intended for reads. */
    suspend fun <T> readOnly(block: suspend () -> T): T

    /** Executes [block] in a transaction that may mutate storage. */
    suspend fun <T> readWrite(block: suspend () -> T): T
}

class ExposedTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override suspend fun <T> readOnly(block: suspend () -> T): T =
        inTransaction(block)

    override suspend fun <T> readWrite(block: suspend () -> T): T =
        inTransaction(block)

    private suspend fun <T> inTransaction(
        block: suspend () -> T,
    ): T =
        // Keep readOnly/readWrite as application-level intent for now. SQLite JDBC
        // rejects changing the JDBC read-only flag after opening a connection.
        suspendTransaction(
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
            readOnly = false,
            db = database,
        ) {
            block()
        }
}
