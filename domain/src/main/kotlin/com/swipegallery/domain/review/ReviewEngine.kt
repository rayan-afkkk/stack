package com.swipegallery.domain.review

import com.swipegallery.domain.allowance.AllowancePolicy
import com.swipegallery.domain.allowance.ChargeDecision
import com.swipegallery.domain.allowance.FREE_DAILY_REVIEWS
import com.swipegallery.domain.media.PhotoMeta
import com.swipegallery.domain.time.AppClock
import com.swipegallery.domain.time.today

sealed interface CommitResult {
    /** Decision saved. [charged] is false when the photo was already charged today. */
    data class Committed(val charged: Boolean, val usedToday: Int) : CommitResult

    /** The same decision was already recorded for this photo in this session (idempotent retry). */
    data object AlreadyApplied : CommitResult

    /** Free allowance exhausted; nothing was written. */
    data class Blocked(val usedToday: Int) : CommitResult

    /** The photo has already been moved to the device trash; a new decision makes no sense. */
    data object AlreadyTrashed : CommitResult
}

sealed interface UndoResult {
    data class Undone(val photo: PhotoMeta, val decision: Decision) : UndoResult

    /**
     * The last decision was a removal that has since been confirmed through Android's trash
     * dialog. It cannot be undone in the app; the user must use the device trash. The entry is
     * dropped from the undo stack so earlier actions remain undoable.
     */
    data class AlreadyTrashed(val photo: PhotoMeta) : UndoResult

    data object NothingToUndo : UndoResult
}

/** Counts reported after applying a reconciled trash outcome. */
data class QueueCleanup(val trashed: Int, val missing: Int, val changed: Int)

/**
 * The single place where review decisions, the daily ledger, the deletion queue and the
 * session undo stack change. Every public operation is one atomic transaction and is
 * idempotent, so a crash or a duplicate call can never double-charge or lose a queue item.
 */
class ReviewEngine(
    private val store: ReviewStore,
    private val clock: AppClock,
    private val isPremium: suspend () -> Boolean,
    private val dailyLimit: Int = FREE_DAILY_REVIEWS,
) {

    /** Whether a decision on [key] would be accepted right now. */
    suspend fun canDecide(key: String): Boolean = store.transaction {
        val day = clock.today()
        AllowancePolicy.evaluate(
            isPremium = isPremium(),
            usedToday = chargedCount(day),
            alreadyChargedToday = isCharged(day, key),
            limit = dailyLimit,
        ) != ChargeDecision.BLOCKED
    }

    suspend fun usedToday(): Int = store.transaction { chargedCount(clock.today()) }

    suspend fun commit(sessionId: Long, photo: PhotoMeta, decision: Decision): CommitResult {
        val premium = isPremium()
        return store.transaction {
            val now = clock.nowMillis()
            val day = clock.today()
            val existingAction = findSessionAction(sessionId, photo.key)
            if (existingAction != null && existingAction.decision == decision) {
                return@transaction CommitResult.AlreadyApplied
            }
            val current = findDecision(photo.key)
            if (current?.state == ReviewState.TRASHED) return@transaction CommitResult.AlreadyTrashed

            val charge = AllowancePolicy.evaluate(
                isPremium = premium,
                usedToday = chargedCount(day),
                alreadyChargedToday = isCharged(day, photo.key),
                limit = dailyLimit,
            )
            if (charge == ChargeDecision.BLOCKED) {
                return@transaction CommitResult.Blocked(chargedCount(day))
            }
            if (charge == ChargeDecision.CHARGE) addCharge(day, photo.key, now)

            // Preserve the state from *before this session touched the photo* for undo.
            val previousState = existingAction?.previousState ?: current?.state
            val previousDecidedAt = existingAction?.previousDecidedAt ?: current?.decidedAt
            val previousQueuedAt = existingAction?.previousQueuedAt ?: findQueueItem(photo.key)?.addedAt

            when (decision) {
                Decision.KEEP -> {
                    putDecision(StoredDecision(photo, ReviewState.KEPT, now))
                    deleteQueueItem(photo.key)
                }

                Decision.REMOVE -> {
                    putDecision(StoredDecision(photo, ReviewState.QUEUED, now))
                    val queuedAt = findQueueItem(photo.key)?.addedAt ?: now
                    putQueueItem(QueueItem(photo, queuedAt))
                }
            }
            putSessionAction(
                SessionAction(
                    sessionId = sessionId,
                    photo = photo,
                    decision = decision,
                    previousState = previousState,
                    previousDecidedAt = previousDecidedAt,
                    previousQueuedAt = previousQueuedAt,
                    at = now,
                ),
            )
            touchSession(sessionId, now)
            CommitResult.Committed(charged = charge == ChargeDecision.CHARGE, usedToday = chargedCount(day))
        }
    }

    /** Restores the state before the session's most recent decision. Never refunds a review. */
    suspend fun undo(sessionId: Long): UndoResult = store.transaction {
        val last = lastSessionAction(sessionId) ?: return@transaction UndoResult.NothingToUndo
        val key = last.photo.key
        val current = findDecision(key)
        deleteSessionAction(sessionId, key)
        if (current?.state == ReviewState.TRASHED) {
            return@transaction UndoResult.AlreadyTrashed(last.photo)
        }
        when (last.previousState) {
            null -> {
                deleteDecision(key)
                deleteQueueItem(key)
            }

            ReviewState.KEPT -> {
                putDecision(StoredDecision(last.photo, ReviewState.KEPT, last.previousDecidedAt ?: last.at))
                deleteQueueItem(key)
            }

            ReviewState.QUEUED -> {
                putDecision(StoredDecision(last.photo, ReviewState.QUEUED, last.previousDecidedAt ?: last.at))
                putQueueItem(QueueItem(last.photo, last.previousQueuedAt ?: last.at))
            }

            ReviewState.TRASHED -> {
                // A trashed photo is never offered for review, so this cannot normally happen.
                putDecision(StoredDecision(last.photo, ReviewState.TRASHED, last.previousDecidedAt ?: last.at))
                deleteQueueItem(key)
            }
        }
        touchSession(sessionId, clock.nowMillis())
        UndoResult.Undone(last.photo, last.decision)
    }

    /** "Keep instead" from the review queue. Not a new review, so never charged. */
    suspend fun keepInsteadOfRemove(keys: Collection<String>): Int = store.transaction {
        val now = clock.nowMillis()
        var changed = 0
        for (key in keys) {
            val item = findQueueItem(key) ?: continue
            deleteQueueItem(key)
            putDecision(StoredDecision(item.photo, ReviewState.KEPT, now))
            changed++
        }
        changed
    }

    /**
     * Applies a reconciled trash outcome:
     * - [trashed]: confirmed in the device trash → state TRASHED, removed from the queue.
     * - [missing]: no longer on the device (removed elsewhere) → forgotten.
     * - [changed]: the file was edited after it was queued → forgotten, so the new version can
     *   be reviewed on its own merits instead of being deleted unseen.
     */
    suspend fun applyQueueCleanup(
        trashed: Collection<String>,
        missing: Collection<String> = emptyList(),
        changed: Collection<String> = emptyList(),
    ): QueueCleanup = store.transaction {
        val now = clock.nowMillis()
        var t = 0
        var m = 0
        var c = 0
        for (key in trashed) {
            val item = findQueueItem(key)
            val photo = item?.photo ?: findDecision(key)?.photo ?: continue
            deleteQueueItem(key)
            putDecision(StoredDecision(photo, ReviewState.TRASHED, now))
            t++
        }
        for (key in missing) {
            if (findQueueItem(key) != null) m++
            deleteQueueItem(key)
            deleteDecision(key)
        }
        for (key in changed) {
            if (findQueueItem(key) != null) c++
            deleteQueueItem(key)
            deleteDecision(key)
        }
        QueueCleanup(t, m, c)
    }

    /**
     * Forgets "kept"/"trashed" markers and session history so photos can be reviewed again.
     * Leaves the pending queue, today's ledger and the Premium entitlement untouched.
     */
    suspend fun resetReviewHistory() = store.transaction { clearReviewHistory() }
}
