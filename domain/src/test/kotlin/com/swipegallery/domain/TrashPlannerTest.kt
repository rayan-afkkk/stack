package com.swipegallery.domain

import com.swipegallery.domain.review.Decision
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.review.ReviewEngine
import com.swipegallery.domain.review.ReviewState
import com.swipegallery.domain.trash.ProbeState
import com.swipegallery.domain.trash.TrashPlanner
import com.swipegallery.domain.trash.TrashRun
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrashPlannerTest {
    private val clock = FakeClock.at(LocalDateTime.of(2026, 9, 30, 12, 0))
    private val store = InMemoryReviewStore()
    private val engine = ReviewEngine(store, clock, isPremium = { false })

    private suspend fun queue(vararg ids: Long): List<QueueItem> {
        ids.forEach { engine.commit(1, meta(it), Decision.REMOVE) }
        return store.tables.queue.values.sortedBy { it.photo.mediaId }
    }

    /** Mirrors what the Review screen does with a reconciled outcome. */
    private suspend fun applyOutcome(run: TrashRun, confirmed: Boolean, probe: Map<String, ProbeState>) {
        val batch = run.nextBatch()!!
        val outcome = TrashPlanner.reconcile(batch, confirmed, probe)
        engine.applyQueueCleanup(
            trashed = outcome.trashed.map { it.key },
            missing = outcome.missing.map { it.key },
            changed = outcome.changed.map { it.key },
        )
        run.record(outcome, cancelled = !confirmed)
    }

    @Test
    fun `cancelled trash confirmation preserves the queue`() = runTest {
        val items = queue(1, 2, 3)
        val present = items.associate { it.key to ProbeState.PRESENT }
        val run = TrashRun(TrashPlanner.prepare(items, present, fullAccess = true))
        applyOutcome(run, confirmed = false, probe = present)

        val summary = run.currentSummary
        assertTrue(summary.cancelled)
        assertEquals(0, summary.moved)
        assertEquals(3, summary.notMoved)
        assertEquals(3, store.tables.queue.size)
        assertTrue(run.isFinished)
    }

    @Test
    fun `partial trash success updates only successful items`() = runTest {
        val items = queue(1, 2, 3, 4)
        val before = items.associate { it.key to ProbeState.PRESENT }
        val run = TrashRun(TrashPlanner.prepare(items, before, fullAccess = true))
        val after = mapOf(
            items[0].key to ProbeState.TRASHED,
            items[1].key to ProbeState.TRASHED,
            items[2].key to ProbeState.PRESENT, // e.g. the system could not move this one
            items[3].key to ProbeState.PRESENT,
        )
        applyOutcome(run, confirmed = true, probe = after)

        assertEquals(2, run.currentSummary.moved)
        assertEquals(2, run.currentSummary.notMoved)
        assertTrue(run.currentSummary.isPartial)
        assertEquals(setOf(items[2].key, items[3].key), store.tables.queue.keys)
        assertEquals(ReviewState.TRASHED, store.tables.decisions[items[0].key]?.state)
        assertEquals(ReviewState.QUEUED, store.tables.decisions[items[2].key]?.state)
    }

    @Test
    fun `missing, changed and inaccessible items are separated before the request`() = runTest {
        val items = queue(1, 2, 3, 4, 5)
        val probe = mapOf(
            items[0].key to ProbeState.PRESENT,
            items[1].key to ProbeState.ABSENT,
            items[2].key to ProbeState.CHANGED,
            items[3].key to ProbeState.TRASHED,
            // items[4] not returned by the probe at all
        )
        val full = TrashPlanner.prepare(items, probe, fullAccess = true)
        assertEquals(listOf(items[0]), full.batches.flatten())
        assertEquals(listOf(items[1], items[4]), full.missing)
        assertEquals(listOf(items[2]), full.changed)
        assertEquals(listOf(items[3]), full.alreadyTrashed)

        val selectedOnly = TrashPlanner.prepare(items, probe, fullAccess = false)
        assertTrue(selectedOnly.missing.isEmpty(), "absent may just mean not shared")
        assertEquals(listOf(items[1], items[4]), selectedOnly.inaccessible)
    }

    @Test
    fun `large requests are split and a cancelled batch stops the run`() = runTest {
        val items = (1L..7L).map { QueueItem(meta(it), 0L) }
        val probe = items.associate { it.key to ProbeState.PRESENT }
        val prep = TrashPlanner.prepare(items, probe, fullAccess = true, batchSize = 3)
        assertEquals(listOf(3, 3, 1), prep.batches.map { it.size })

        val run = TrashRun(prep)
        val first = run.nextBatch()!!
        run.record(TrashPlanner.reconcile(first, true, first.associate { it.key to ProbeState.TRASHED }), cancelled = false)
        val second = run.nextBatch()!!
        run.record(TrashPlanner.reconcile(second, false, probe), cancelled = true)

        assertTrue(run.isFinished)
        assertEquals(null, run.nextBatch())
        assertEquals(3, run.currentSummary.moved)
        assertEquals(4, run.currentSummary.notMoved)
    }

    @Test
    fun `items gone after a confirmed request count as moved, but not after a cancel`() {
        val item = QueueItem(meta(1), 0L)
        val absent = mapOf(item.key to ProbeState.ABSENT)
        assertEquals(listOf(item), TrashPlanner.reconcile(listOf(item), confirmed = true, probe = absent).trashed)
        assertEquals(listOf(item), TrashPlanner.reconcile(listOf(item), confirmed = false, probe = absent).missing)
    }
}
