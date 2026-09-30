package com.swipegallery.data.review

import com.swipegallery.data.db.AppDatabase
import com.swipegallery.data.db.SessionEntity
import com.swipegallery.data.db.toDomain
import com.swipegallery.domain.review.Decision
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.review.ReviewEngine
import com.swipegallery.domain.session.ScopeCodec
import com.swipegallery.domain.session.SessionOrder
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.domain.session.SessionSpec
import com.swipegallery.domain.time.AppClock
import com.swipegallery.domain.time.ledgerKey
import com.swipegallery.domain.time.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class SessionInfo(val id: Long, val spec: SessionSpec, val updatedAt: Long, val completed: Boolean)

data class SessionCounts(val kept: Int, val queued: Int) {
    val total: Int get() = kept + queued
}

/** Read models and session bookkeeping on top of the [ReviewEngine]. */
class ReviewRepository(
    private val db: AppDatabase,
    val engine: ReviewEngine,
    private val clock: AppClock,
) {
    private val dao = db.reviewDao()

    /** Keys of every photo version with a durable decision (kept, queued or trashed). */
    val decidedKeys: Flow<Set<String>> = dao.observeDecidedKeys()
        .map { keys -> keys.toHashSet() }
        .flowOn(Dispatchers.Default)

    val queue: Flow<List<QueueItem>> = dao.observeQueue().map { list -> list.map { it.toDomain() } }

    val queueCount: Flow<Int> = dao.observeQueueCount().distinctUntilChanged()

    val resumableSession: Flow<SessionInfo?> = dao.observeResumableSession().map { it?.toInfo() }

    suspend fun decidedKeysNow(): Set<String> = dao.decidedKeys().toHashSet()

    suspend fun queueNow(): List<QueueItem> = dao.queue().map { it.toDomain() }

    suspend fun session(id: Long): SessionInfo? = dao.session(id)?.toInfo()

    suspend fun actedKeys(sessionId: Long): Set<String> = dao.actedKeys(sessionId).toHashSet()

    suspend fun counts(sessionId: Long): SessionCounts {
        val counts = dao.decisionCounts(sessionId).associate { it.decision to it.count }
        return SessionCounts(kept = counts[Decision.KEEP.name] ?: 0, queued = counts[Decision.REMOVE.name] ?: 0)
    }

    suspend fun findOpenSession(spec: SessionSpec): SessionInfo? = dao.findOpenSession(
        scopeType = ScopeCodec.type(spec.scope),
        scopeArg = ScopeCodec.arg(spec.scope),
        sortOrder = spec.order.name,
        includeReviewed = spec.includeReviewed,
    )?.toInfo()

    /** Resumes the matching unfinished session, or starts a new one. */
    suspend fun startOrResume(spec: SessionSpec): Long {
        findOpenSession(spec)?.let { return it.id }
        val now = clock.nowMillis()
        return dao.insertSession(
            SessionEntity(
                scopeType = ScopeCodec.type(spec.scope),
                scopeArg = ScopeCodec.arg(spec.scope),
                scopeLabel = (spec.scope as? SessionScope.Album)?.name,
                sortOrder = spec.order.name,
                includeReviewed = spec.includeReviewed,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
            ),
        )
    }

    suspend fun markCompleted(sessionId: Long) = dao.completeSession(sessionId, clock.nowMillis())

    /** An undo after the last photo makes a completed session resumable again. */
    suspend fun reopen(sessionId: Long) = dao.reopenSession(sessionId)

    /** Old ledger days are irrelevant to the allowance; keep a short tail for diagnostics. */
    suspend fun pruneLedger() = dao.pruneChargesBefore(clock.today().minusDays(7).ledgerKey())

    private fun SessionEntity.toInfo(): SessionInfo? {
        val scope = ScopeCodec.decode(scopeType, scopeArg, scopeLabel) ?: return null
        val order = runCatching { SessionOrder.valueOf(sortOrder) }.getOrNull() ?: return null
        return SessionInfo(id, SessionSpec(scope, order, includeReviewed), updatedAt, completedAt != null)
    }
}
