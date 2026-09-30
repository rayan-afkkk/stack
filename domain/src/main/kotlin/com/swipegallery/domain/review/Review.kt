package com.swipegallery.domain.review

import com.swipegallery.domain.media.PhotoMeta
import java.time.LocalDate

/** What the user chose for a photo in a session. */
enum class Decision { KEEP, REMOVE }

/** Durable state of a reviewed photo version. */
enum class ReviewState {
    KEPT,

    /** In the pending deletion queue. Nothing has happened to the file. */
    QUEUED,

    /** Android confirmed the move to the device trash. */
    TRASHED,
}

data class StoredDecision(
    val photo: PhotoMeta,
    val state: ReviewState,
    val decidedAt: Long,
)

data class QueueItem(
    val photo: PhotoMeta,
    val addedAt: Long,
) {
    val key: String get() = photo.key
}

/** One entry in a session's undo stack, with everything needed to restore the prior state. */
data class SessionAction(
    val sessionId: Long,
    val photo: PhotoMeta,
    val decision: Decision,
    val previousState: ReviewState?,
    val previousDecidedAt: Long?,
    val previousQueuedAt: Long?,
    val at: Long,
)

/**
 * Transaction-scoped storage operations. Implementations must run the whole
 * [ReviewStore.transaction] block atomically: either every write lands or none does.
 */
interface ReviewTransaction {
    suspend fun isCharged(day: LocalDate, key: String): Boolean
    suspend fun chargedCount(day: LocalDate): Int
    suspend fun addCharge(day: LocalDate, key: String, at: Long)

    suspend fun findDecision(key: String): StoredDecision?
    suspend fun putDecision(decision: StoredDecision)
    suspend fun deleteDecision(key: String)

    suspend fun findQueueItem(key: String): QueueItem?
    suspend fun putQueueItem(item: QueueItem)
    suspend fun deleteQueueItem(key: String)

    suspend fun findSessionAction(sessionId: Long, key: String): SessionAction?
    suspend fun lastSessionAction(sessionId: Long): SessionAction?
    suspend fun putSessionAction(action: SessionAction)
    suspend fun deleteSessionAction(sessionId: Long, key: String)
    suspend fun touchSession(sessionId: Long, at: Long)

    /** Deletes every decision except [ReviewState.QUEUED], all session actions and sessions. */
    suspend fun clearReviewHistory()
}

interface ReviewStore {
    suspend fun <T> transaction(block: suspend ReviewTransaction.() -> T): T
}

/** Size estimate for queued photos. Unknown sizes are counted separately, never guessed. */
data class QueueEstimate(val count: Int, val knownBytes: Long, val unknownSizeCount: Int) {
    companion object {
        fun of(items: List<QueueItem>): QueueEstimate = QueueEstimate(
            count = items.size,
            knownBytes = items.sumOf { it.photo.sizeBytes ?: 0L },
            unknownSizeCount = items.count { it.photo.sizeBytes == null },
        )
    }
}
