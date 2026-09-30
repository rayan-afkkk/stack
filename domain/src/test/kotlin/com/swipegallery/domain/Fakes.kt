package com.swipegallery.domain

import com.swipegallery.domain.media.PhotoMeta
import com.swipegallery.domain.media.PhotoRef
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.review.ReviewState
import com.swipegallery.domain.review.ReviewStore
import com.swipegallery.domain.review.ReviewTransaction
import com.swipegallery.domain.review.SessionAction
import com.swipegallery.domain.review.StoredDecision
import com.swipegallery.domain.time.AppClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FakeClock(var now: Long, var zoneId: ZoneId = ZoneId.of("Europe/London")) : AppClock {
    override fun nowMillis(): Long = now
    override fun zone(): ZoneId = zoneId

    fun set(dateTime: LocalDateTime) {
        now = dateTime.atZone(zoneId).toInstant().toEpochMilli()
    }

    companion object {
        fun at(dateTime: LocalDateTime, zone: ZoneId = ZoneId.of("Europe/London")) =
            FakeClock(dateTime.atZone(zone).toInstant().toEpochMilli(), zone)
    }
}

/** Snapshot of all persisted tables; the in-memory store swaps whole snapshots to be atomic. */
data class Tables(
    val charges: Map<Pair<LocalDate, String>, Long> = emptyMap(),
    val decisions: Map<String, StoredDecision> = emptyMap(),
    val queue: Map<String, QueueItem> = emptyMap(),
    val actions: List<SessionAction> = emptyList(),
    val sessionsTouched: Map<Long, Long> = emptyMap(),
)

/**
 * Behaves like the Room store: serialised, all-or-nothing transactions. Survives "restarts"
 * because engines are recreated against the same instance, just as they are against the DB.
 */
class InMemoryReviewStore : ReviewStore {
    private val mutex = Mutex()
    @Volatile var tables = Tables()
        private set

    /** Makes the next transaction throw after its writes, to prove rollback. */
    var failNextTransaction = false

    override suspend fun <T> transaction(block: suspend ReviewTransaction.() -> T): T = mutex.withLock {
        val tx = Tx(tables)
        val result = tx.block()
        if (failNextTransaction) {
            failNextTransaction = false
            throw IllegalStateException("Simulated crash before commit")
        }
        tables = tx.t
        result
    }

    private class Tx(var t: Tables) : ReviewTransaction {
        override suspend fun isCharged(day: LocalDate, key: String) = (day to key) in t.charges
        override suspend fun chargedCount(day: LocalDate) = t.charges.keys.count { it.first == day }
        override suspend fun addCharge(day: LocalDate, key: String, at: Long) {
            if ((day to key) !in t.charges) t = t.copy(charges = t.charges + ((day to key) to at))
        }

        override suspend fun findDecision(key: String) = t.decisions[key]
        override suspend fun putDecision(decision: StoredDecision) {
            t = t.copy(decisions = t.decisions + (decision.photo.key to decision))
        }

        override suspend fun deleteDecision(key: String) {
            t = t.copy(decisions = t.decisions - key)
        }

        override suspend fun findQueueItem(key: String) = t.queue[key]
        override suspend fun putQueueItem(item: QueueItem) {
            t = t.copy(queue = t.queue + (item.key to item))
        }

        override suspend fun deleteQueueItem(key: String) {
            t = t.copy(queue = t.queue - key)
        }

        override suspend fun findSessionAction(sessionId: Long, key: String) =
            t.actions.firstOrNull { it.sessionId == sessionId && it.photo.key == key }

        override suspend fun lastSessionAction(sessionId: Long) = t.actions.lastOrNull { it.sessionId == sessionId }

        override suspend fun putSessionAction(action: SessionAction) {
            t = t.copy(
                actions = t.actions.filterNot { it.sessionId == action.sessionId && it.photo.key == action.photo.key } + action,
            )
        }

        override suspend fun deleteSessionAction(sessionId: Long, key: String) {
            t = t.copy(actions = t.actions.filterNot { it.sessionId == sessionId && it.photo.key == key })
        }

        override suspend fun touchSession(sessionId: Long, at: Long) {
            t = t.copy(sessionsTouched = t.sessionsTouched + (sessionId to at))
        }

        override suspend fun clearReviewHistory() {
            t = t.copy(
                decisions = t.decisions.filterValues { it.state == ReviewState.QUEUED },
                actions = emptyList(),
                sessionsTouched = emptyMap(),
            )
        }
    }
}

fun photo(
    id: Long,
    dateMillis: Long? = 1_700_000_000_000L + id * 1000,
    size: Long? = 1_000_000L,
    bucket: String? = "camera",
    taken: Boolean = true,
    volume: String = "external_primary",
    generation: Long = id,
) = PhotoRef(
    mediaId = id,
    volume = volume,
    key = "$volume:$id:g$generation.$generation",
    dateTakenMillis = if (taken) dateMillis else null,
    dateAddedMillis = dateMillis,
    sizeBytes = size,
    width = 4000,
    height = 3000,
    bucketId = bucket,
    bucketName = bucket?.replaceFirstChar { it.uppercase() },
    mimeType = "image/jpeg",
)

fun meta(id: Long): PhotoMeta = photo(id).meta()
