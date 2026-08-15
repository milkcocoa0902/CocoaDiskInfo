package com.milkcocoa.info.sapphire.agent.auth

import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.NonceBinding
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class IssuedNonce(
    val value: String,
    val expiresAt: Instant,
)

enum class NonceConsumeResult {
    CONSUMED,
    UNAVAILABLE,
}

interface NonceStore {
    fun issue(binding: NonceBinding, ttl: Duration = InMemoryNonceStore.DEFAULT_TTL): IssuedNonce
    fun consume(nonce: String, expectedBinding: NonceBinding): NonceConsumeResult
    fun purgeExpired(): Int
}

class NonceCapacityExceededException(message: String) : IllegalStateException(message)

class InMemoryNonceStore(
    private val now: () -> Instant = { kotlin.time.Clock.System.now() },
    private val secureRandom: SecureRandom = SecureRandom(),
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val maximumEntriesPerSubject: Int = DEFAULT_MAXIMUM_ENTRIES_PER_SUBJECT,
) : NonceStore {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    init {
        require(maximumEntries > 0) { "Nonce maximum entries must be greater than zero." }
        require(maximumEntriesPerSubject > 0) { "Nonce per-subject maximum must be greater than zero." }
        require(maximumEntriesPerSubject <= maximumEntries) {
            "Nonce per-subject maximum must not exceed the global maximum."
        }
    }

    override fun issue(binding: NonceBinding, ttl: Duration): IssuedNonce {
        require(ttl in MINIMUM_TTL..MAXIMUM_TTL) {
            "Nonce TTL must be between $MINIMUM_TTL and $MAXIMUM_TTL."
        }
        return synchronized(lock) {
            val issuedAt = now()
            purgeExpiredLocked(issuedAt)
            if (entries.size >= maximumEntries) {
                throw NonceCapacityExceededException("Nonce store capacity is exhausted.")
            }
            val subjectEntryCount = entries.values.count { it.binding.sameSubjectAs(binding) }
            if (subjectEntryCount >= maximumEntriesPerSubject) {
                throw NonceCapacityExceededException("Nonce subject capacity is exhausted.")
            }

            var value: String
            do {
                value = ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes).let(Base64Url::encode)
            } while (value in entries)

            val expiresAt = issuedAt + ttl
            entries[value] = Entry(binding, expiresAt)
            IssuedNonce(value, expiresAt)
        }
    }

    override fun consume(nonce: String, expectedBinding: NonceBinding): NonceConsumeResult = synchronized(lock) {
        val currentTime = now()
        val entry = entries[nonce] ?: return@synchronized NonceConsumeResult.UNAVAILABLE
        if (entry.expiresAt <= currentTime) {
            entries.remove(nonce)
            return@synchronized NonceConsumeResult.UNAVAILABLE
        }
        if (entry.binding != expectedBinding) {
            return@synchronized NonceConsumeResult.UNAVAILABLE
        }

        // Binding comparison and removal share the same monitor. Keeping this as
        // one operation is the replay-prevention invariant, not a cache optimization.
        entries.remove(nonce)
        NonceConsumeResult.CONSUMED
    }

    override fun purgeExpired(): Int = synchronized(lock) {
        purgeExpiredLocked(now())
    }

    private fun purgeExpiredLocked(currentTime: Instant): Int {
        val expired = entries.filterValues { it.expiresAt <= currentTime }.keys
        expired.forEach(entries::remove)
        return expired.size
    }

    private fun NonceBinding.sameSubjectAs(other: NonceBinding): Boolean =
        subjectType == other.subjectType && subjectId == other.subjectId

    private data class Entry(
        val binding: NonceBinding,
        val expiresAt: Instant,
    )

    companion object {
        const val NONCE_SIZE_BYTES = 32
        const val DEFAULT_MAXIMUM_ENTRIES = 4_096
        const val DEFAULT_MAXIMUM_ENTRIES_PER_SUBJECT = 32
        val DEFAULT_TTL = 60.seconds
        val MINIMUM_TTL = 10.seconds
        val MAXIMUM_TTL = 300.seconds
    }
}
