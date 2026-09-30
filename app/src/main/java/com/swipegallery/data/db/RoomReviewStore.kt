package com.swipegallery.data.db

import androidx.room.withTransaction
import com.swipegallery.domain.media.PhotoMeta
import com.swipegallery.domain.review.Decision
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.review.ReviewState
import com.swipegallery.domain.review.ReviewStore
import com.swipegallery.domain.review.ReviewTransaction
import com.swipegallery.domain.review.SessionAction
import com.swipegallery.domain.review.StoredDecision
import com.swipegallery.domain.time.ledgerKey
import java.time.LocalDate

/** Room-backed [ReviewStore]. Each engine operation runs inside one SQLite transaction. */
class RoomReviewStore(private val db: AppDatabase) : ReviewStore {
    private val dao = db.reviewDao()

    override suspend fun <T> transaction(block: suspend ReviewTransaction.() -> T): T =
        db.withTransaction { RoomTransaction(dao).block() }

    private class RoomTransaction(private val dao: ReviewDao) : ReviewTransaction {
        override suspend fun isCharged(day: LocalDate, key: String) = dao.isCharged(day.ledgerKey(), key)
        override suspend fun chargedCount(day: LocalDate) = dao.chargedCount(day.ledgerKey())
        override suspend fun addCharge(day: LocalDate, key: String, at: Long) =
            dao.insertCharge(ChargeEntity(day.ledgerKey(), key, at))

        override suspend fun findDecision(key: String) = dao.decision(key)?.toDomain()
        override suspend fun putDecision(decision: StoredDecision) = dao.upsertDecision(decision.toEntity())
        override suspend fun deleteDecision(key: String) = dao.deleteDecision(key)

        override suspend fun findQueueItem(key: String) = dao.queueItem(key)?.toDomain()
        override suspend fun putQueueItem(item: QueueItem) = dao.upsertQueueItem(item.toEntity())
        override suspend fun deleteQueueItem(key: String) = dao.deleteQueueItem(key)

        override suspend fun findSessionAction(sessionId: Long, key: String) =
            dao.sessionAction(sessionId, key)?.toDomain()

        override suspend fun lastSessionAction(sessionId: Long) = dao.lastSessionAction(sessionId)?.toDomain()

        override suspend fun putSessionAction(action: SessionAction) {
            val seq = dao.maxSeq(action.sessionId) + 1
            dao.upsertSessionAction(action.toEntity(seq))
        }

        override suspend fun deleteSessionAction(sessionId: Long, key: String) =
            dao.deleteSessionAction(sessionId, key)

        override suspend fun touchSession(sessionId: Long, at: Long) = dao.touchSession(sessionId, at)

        override suspend fun clearReviewHistory() {
            dao.deleteSettledDecisions()
            dao.deleteAllSessionActions()
            dao.deleteAllSessions()
        }
    }
}

private fun photoMeta(key: String, id: Long, volume: String, size: Long?, date: Long?, w: Int?, h: Int?) =
    PhotoMeta(key = key, mediaId = id, volume = volume, sizeBytes = size, dateMillis = date, width = w, height = h)

fun DecisionEntity.toDomain() = StoredDecision(
    photo = photoMeta(mediaKey, mediaId, volume, sizeBytes, dateMillis, width, height),
    state = ReviewState.valueOf(state),
    decidedAt = decidedAt,
)

fun StoredDecision.toEntity() = DecisionEntity(
    mediaKey = photo.key,
    mediaId = photo.mediaId,
    volume = photo.volume,
    sizeBytes = photo.sizeBytes,
    dateMillis = photo.dateMillis,
    width = photo.width,
    height = photo.height,
    state = state.name,
    decidedAt = decidedAt,
)

fun QueueEntity.toDomain() = QueueItem(
    photo = photoMeta(mediaKey, mediaId, volume, sizeBytes, dateMillis, width, height),
    addedAt = addedAt,
)

fun QueueItem.toEntity() = QueueEntity(
    mediaKey = photo.key,
    mediaId = photo.mediaId,
    volume = photo.volume,
    sizeBytes = photo.sizeBytes,
    dateMillis = photo.dateMillis,
    width = photo.width,
    height = photo.height,
    addedAt = addedAt,
)

fun SessionActionEntity.toDomain() = SessionAction(
    sessionId = sessionId,
    photo = photoMeta(mediaKey, mediaId, volume, sizeBytes, dateMillis, width, height),
    decision = Decision.valueOf(decision),
    previousState = previousState?.let { ReviewState.valueOf(it) },
    previousDecidedAt = previousDecidedAt,
    previousQueuedAt = previousQueuedAt,
    at = at,
)

fun SessionAction.toEntity(seq: Long) = SessionActionEntity(
    sessionId = sessionId,
    mediaKey = photo.key,
    seq = seq,
    mediaId = photo.mediaId,
    volume = photo.volume,
    sizeBytes = photo.sizeBytes,
    dateMillis = photo.dateMillis,
    width = photo.width,
    height = photo.height,
    decision = decision.name,
    previousState = previousState?.name,
    previousDecidedAt = previousDecidedAt,
    previousQueuedAt = previousQueuedAt,
    at = at,
)
