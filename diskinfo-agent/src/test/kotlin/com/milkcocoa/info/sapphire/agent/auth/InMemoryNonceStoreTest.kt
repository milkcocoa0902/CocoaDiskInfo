package com.milkcocoa.info.sapphire.agent.auth

import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.NonceBinding
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class InMemoryNonceStoreTest {
    private var currentTime = Instant.parse("2026-08-15T00:00:00Z")
    private val binding = NonceBinding(
        subjectType = NonceSubjectType.PRINCIPAL,
        subjectId = "kid-a",
        purpose = AuthPurpose.SNAPSHOT_INGEST,
    )

    @Test
    fun `issued nonce is 256 bit purpose bound and single use`() {
        val store = store()
        val issued = store.issue(binding)
        val wrongPurpose = binding.copy(purpose = AuthPurpose.HEARTBEAT)

        assertEquals(32, Base64Url.decode(issued.value).size)
        assertEquals(currentTime + 60.seconds, issued.expiresAt)
        assertEquals(NonceConsumeResult.UNAVAILABLE, store.consume(issued.value, wrongPurpose))
        assertEquals(NonceConsumeResult.CONSUMED, store.consume(issued.value, binding))
        assertEquals(NonceConsumeResult.UNAVAILABLE, store.consume(issued.value, binding))
    }

    @Test
    fun `expiry boundary is unavailable and purge frees capacity`() {
        val store = store(maximumEntries = 1, maximumEntriesPerSubject = 1)
        val expired = store.issue(binding, ttl = 10.seconds)
        currentTime += 10.seconds

        assertEquals(NonceConsumeResult.UNAVAILABLE, store.consume(expired.value, binding))
        val replacement = store.issue(binding, ttl = 10.seconds)

        assertNotEquals(expired.value, replacement.value)
    }

    @Test
    fun `global and per subject capacities are bounded`() {
        val perSubjectStore = store(maximumEntries = 2, maximumEntriesPerSubject = 1)
        perSubjectStore.issue(binding)
        assertFailsWith<NonceCapacityExceededException> { perSubjectStore.issue(binding) }

        val globalStore = store(maximumEntries = 2, maximumEntriesPerSubject = 2)
        globalStore.issue(binding)
        globalStore.issue(binding.copy(subjectId = "kid-b"))
        assertFailsWith<NonceCapacityExceededException> {
            globalStore.issue(binding.copy(subjectId = "kid-c"))
        }
    }

    @Test
    fun `concurrent consume has exactly one winner`() {
        val store = store()
        val issued = store.issue(binding)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val results = mutableListOf<NonceConsumeResult>()
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                executor.execute {
                    start.await()
                    val result = store.consume(issued.value, binding)
                    synchronized(results) { results += result }
                    done.countDown()
                }
            }
            start.countDown()
            done.await()
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it == NonceConsumeResult.CONSUMED })
        assertEquals(7, results.count { it == NonceConsumeResult.UNAVAILABLE })
    }

    @Test
    fun `ttl outside configured contract is rejected`() {
        val store = store()

        assertFailsWith<IllegalArgumentException> { store.issue(binding, 9.seconds) }
        assertFailsWith<IllegalArgumentException> { store.issue(binding, 301.seconds) }
    }

    private fun store(
        maximumEntries: Int = 16,
        maximumEntriesPerSubject: Int = 4,
    ): InMemoryNonceStore = InMemoryNonceStore(
        now = { currentTime },
        secureRandom = SecureRandom(),
        maximumEntries = maximumEntries,
        maximumEntriesPerSubject = maximumEntriesPerSubject,
    )
}
