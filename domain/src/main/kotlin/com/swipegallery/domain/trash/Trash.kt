package com.swipegallery.domain.trash

import com.swipegallery.domain.review.QueueItem

/** What MediaStore says about a queued photo right now. */
enum class ProbeState {
    /** Visible, not trashed, same version as queued. */
    PRESENT,

    /** IS_TRASHED = 1. */
    TRASHED,

    /** Not found (deleted elsewhere, or no longer visible to the app). */
    ABSENT,

    /** Same row, but the file was modified after it was queued. */
    CHANGED,
}

data class TrashPreparation(
    /** Items to send to MediaStore.createTrashRequest, split into batches. */
    val batches: List<List<QueueItem>>,
    /** Already in the device trash (e.g. trashed from another app). */
    val alreadyTrashed: List<QueueItem>,
    /** Gone from the device. */
    val missing: List<QueueItem>,
    /** Edited since queued; returned for fresh review instead of being trashed unseen. */
    val changed: List<QueueItem>,
    /** Not visible with the current (selected-photos) access; left in the queue untouched. */
    val inaccessible: List<QueueItem>,
) {
    val requestCount: Int get() = batches.sumOf { it.size }
}

data class BatchOutcome(
    val trashed: List<QueueItem>,
    /** Still present and not trashed: remains in the queue. */
    val notTrashed: List<QueueItem>,
    val missing: List<QueueItem>,
    val changed: List<QueueItem>,
)

data class TrashSummary(
    val moved: Int = 0,
    val notMoved: Int = 0,
    val missing: Int = 0,
    val changed: Int = 0,
    val inaccessible: Int = 0,
    val cancelled: Boolean = false,
) {
    val isPartial: Boolean get() = moved > 0 && notMoved > 0
}

object TrashPlanner {
    /**
     * The platform documents no fixed per-request maximum, but a PendingIntent carries every
     * URI through Binder. Batches keep each request comfortably small.
     */
    const val MAX_URIS_PER_REQUEST: Int = 250

    fun prepare(
        selected: List<QueueItem>,
        probe: Map<String, ProbeState>,
        fullAccess: Boolean,
        batchSize: Int = MAX_URIS_PER_REQUEST,
    ): TrashPreparation {
        require(batchSize > 0)
        val send = ArrayList<QueueItem>()
        val trashed = ArrayList<QueueItem>()
        val missing = ArrayList<QueueItem>()
        val changed = ArrayList<QueueItem>()
        val inaccessible = ArrayList<QueueItem>()
        for (item in selected) {
            when (probe[item.key] ?: ProbeState.ABSENT) {
                ProbeState.PRESENT -> send += item
                ProbeState.TRASHED -> trashed += item
                ProbeState.CHANGED -> changed += item
                // With selected-photos access, "absent" may simply mean "not shared with us".
                ProbeState.ABSENT -> if (fullAccess) missing += item else inaccessible += item
            }
        }
        return TrashPreparation(send.chunked(batchSize), trashed, missing, changed, inaccessible)
    }

    /**
     * Decides each item's fate from its *actual* state after the system dialog, instead of
     * trusting the activity result alone.
     *
     * @param confirmed whether the system dialog returned RESULT_OK.
     */
    fun reconcile(batch: List<QueueItem>, confirmed: Boolean, probe: Map<String, ProbeState>): BatchOutcome {
        val trashed = ArrayList<QueueItem>()
        val notTrashed = ArrayList<QueueItem>()
        val missing = ArrayList<QueueItem>()
        val changed = ArrayList<QueueItem>()
        for (item in batch) {
            when (probe[item.key] ?: ProbeState.ABSENT) {
                ProbeState.TRASHED -> trashed += item
                ProbeState.PRESENT -> notTrashed += item
                // After a confirmed request, disappearance means the system moved it.
                ProbeState.ABSENT -> if (confirmed) trashed += item else missing += item
                ProbeState.CHANGED -> changed += item
            }
        }
        return BatchOutcome(trashed, notTrashed, missing, changed)
    }
}

/**
 * Tracks a multi-batch trash run. A cancelled batch stops the run; later batches are never
 * launched, and everything not confirmed stays queued.
 */
class TrashRun(private val preparation: TrashPreparation) {
    private var nextIndex = 0
    private var summary = TrashSummary(
        moved = preparation.alreadyTrashed.size,
        missing = preparation.missing.size,
        changed = preparation.changed.size,
        inaccessible = preparation.inaccessible.size,
    )

    val currentSummary: TrashSummary get() = summary
    val isFinished: Boolean get() = summary.cancelled || nextIndex >= preparation.batches.size

    fun nextBatch(): List<QueueItem>? = if (isFinished) null else preparation.batches[nextIndex]

    fun record(outcome: BatchOutcome, cancelled: Boolean) {
        check(!isFinished) { "No batch in flight" }
        nextIndex++
        val remainingUnsent = if (cancelled) preparation.batches.drop(nextIndex).sumOf { it.size } else 0
        summary = summary.copy(
            moved = summary.moved + outcome.trashed.size,
            notMoved = summary.notMoved + outcome.notTrashed.size + remainingUnsent,
            missing = summary.missing + outcome.missing.size,
            changed = summary.changed + outcome.changed.size,
            cancelled = summary.cancelled || cancelled,
        )
    }
}
