package com.swipegallery.domain

import com.swipegallery.domain.allowance.FREE_DAILY_REVIEWS
import com.swipegallery.domain.review.CommitResult
import com.swipegallery.domain.review.Decision
import com.swipegallery.domain.review.ReviewEngine
import com.swipegallery.domain.review.ReviewState
import com.swipegallery.domain.review.UndoResult
import com.swipegallery.domain.swipe.SwipeConfig
import com.swipegallery.domain.swipe.SwipeDecider
import com.swipegallery.domain.swipe.SwipeOutcome
import com.swipegallery.domain.time.today
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewEngineTest {
    private val clock = FakeClock.at(LocalDateTime.of(2026, 9, 30, 10, 0))
    private val store = InMemoryReviewStore()
    private var premium = false
    private fun engine() = ReviewEngine(store, clock, isPremium = { premium })
    private val session = 1L

    private suspend fun ReviewEngine.review(id: Long, d: Decision = Decision.KEEP) = commit(session, meta(id), d)

    @Test
    fun `reviews 1 through 50 succeed and 51 is blocked for free users`() = runTest {
        val engine = engine()
        for (i in 1..FREE_DAILY_REVIEWS) {
            val decision = if (i % 2 == 0) Decision.KEEP else Decision.REMOVE
            val result = engine.review(i.toLong(), decision)
            assertEquals(CommitResult.Committed(charged = true, usedToday = i), result, "review $i")
        }
        val blocked = engine.review(51)
        assertEquals(CommitResult.Blocked(usedToday = 50), blocked)
        assertNull(store.tables.decisions[meta(51).key], "blocked review must not be written")
        assertFalse(engine.canDecide(meta(51).key))
        assertEquals(25, store.tables.queue.size)
    }

    @Test
    fun `premium bypasses the allowance`() = runTest {
        premium = true
        val engine = engine()
        for (i in 1..120) {
            assertIs<CommitResult.Committed>(engine.review(i.toLong()))
        }
        assertEquals(120, engine.usedToday())
        assertTrue(engine.canDecide(meta(500).key))
    }

    @Test
    fun `keep and pending remove each count exactly once`() = runTest {
        val engine = engine()
        engine.review(1, Decision.KEEP)
        engine.review(2, Decision.REMOVE)
        assertEquals(2, engine.usedToday())
        assertEquals(ReviewState.KEPT, store.tables.decisions[meta(1).key]?.state)
        assertEquals(ReviewState.QUEUED, store.tables.decisions[meta(2).key]?.state)
        assertEquals(setOf(meta(2).key), store.tables.queue.keys)
    }

    @Test
    fun `duplicate commit of the same decision is idempotent`() = runTest {
        val engine = engine()
        engine.review(1, Decision.REMOVE)
        assertEquals(CommitResult.AlreadyApplied, engine.review(1, Decision.REMOVE))
        assertEquals(1, engine.usedToday())
        assertEquals(1, store.tables.queue.size)
    }

    @Test
    fun `rapid concurrent commits of one photo charge once`() = runTest {
        val engine = engine()
        val results = (1..10).map { async { engine.review(7, Decision.REMOVE) } }.awaitAll()
        assertEquals(1, results.count { it is CommitResult.Committed })
        assertEquals(1, engine.usedToday())
        assertEquals(1, store.tables.queue.size)
    }

    @Test
    fun `cancelled gestures do not count`() = runTest {
        val engine = engine()
        val config = SwipeConfig(flingVelocityPxPerSec = 1500f)
        val outcome = SwipeDecider.decide(offsetX = -80f, velocityX = -200f, cardWidth = 1000f, config = config)
        assertEquals(SwipeOutcome.CANCEL, outcome)
        // A cancelled gesture never reaches the engine; nothing is charged.
        assertEquals(0, engine.usedToday())
    }

    @Test
    fun `undo restores the previous decision without refunding and recommit is free`() = runTest {
        val engine = engine()
        engine.review(1, Decision.REMOVE)
        assertEquals(1, store.tables.queue.size)

        val undo = engine.undo(session)
        assertIs<UndoResult.Undone>(undo)
        assertEquals(0, store.tables.queue.size)
        assertNull(store.tables.decisions[meta(1).key])
        assertEquals(1, engine.usedToday(), "undo does not refund")

        val again = engine.review(1, Decision.KEEP)
        assertEquals(CommitResult.Committed(charged = false, usedToday = 1), again)
        assertEquals(1, engine.usedToday(), "recommitting the same photo the same day is free")
    }

    @Test
    fun `recommit after undo is allowed even when the allowance is exhausted`() = runTest {
        val engine = engine()
        for (i in 1..50) engine.review(i.toLong())
        engine.undo(session)
        assertTrue(engine.canDecide(meta(50).key))
        assertIs<CommitResult.Committed>(engine.review(50, Decision.REMOVE))
        assertEquals(CommitResult.Blocked(50), engine.review(51))
    }

    @Test
    fun `undo in revisit mode restores the earlier queued state`() = runTest {
        val engine = engine()
        engine.commit(1, meta(3), Decision.REMOVE)
        engine.commit(2, meta(3), Decision.KEEP) // revisit session changes its mind
        assertTrue(store.tables.queue.isEmpty())
        engine.undo(2)
        assertEquals(ReviewState.QUEUED, store.tables.decisions[meta(3).key]?.state)
        assertEquals(1, store.tables.queue.size)
    }

    @Test
    fun `date rollover resets the allowance but preserves pending photos`() = runTest {
        val engine = engine()
        for (i in 1..50) engine.review(i.toLong(), Decision.REMOVE)
        assertIs<CommitResult.Blocked>(engine.review(51))

        clock.set(LocalDateTime.of(2026, 10, 1, 0, 0, 1))
        assertEquals(0, engine.usedToday())
        assertEquals(50, store.tables.queue.size)
        assertIs<CommitResult.Committed>(engine.review(51))
    }

    @Test
    fun `a photo charged yesterday is charged again today`() = runTest {
        val engine = engine()
        engine.review(1)
        clock.set(LocalDateTime.of(2026, 10, 1, 9, 0))
        engine.undo(session)
        assertEquals(CommitResult.Committed(charged = true, usedToday = 1), engine.review(1))
    }

    @Test
    fun `local day follows the time zone`() = runTest {
        // 23:30 in London is already the next day in Tokyo.
        clock.set(LocalDateTime.of(2026, 9, 30, 23, 30))
        val engine = engine()
        engine.review(1)
        val londonDay = clock.today()
        clock.zoneId = ZoneId.of("Asia/Tokyo")
        assertEquals(londonDay.plusDays(1), clock.today())
        assertEquals(0, engine.usedToday())
    }

    @Test
    fun `app restart does not reset usage`() = runTest {
        val first = engine()
        for (i in 1..30) first.review(i.toLong())
        val restarted = engine() // new process, same durable store
        assertEquals(30, restarted.usedToday())
        for (i in 31..50) assertIs<CommitResult.Committed>(restarted.review(i.toLong()))
        assertIs<CommitResult.Blocked>(restarted.review(51))
    }

    @Test
    fun `a crash mid-transaction leaves no partial charge or queue item`() = runTest {
        val engine = engine()
        store.failNextTransaction = true
        assertFailsWith<IllegalStateException> { engine.review(1, Decision.REMOVE) }
        assertEquals(0, engine.usedToday())
        assertTrue(store.tables.queue.isEmpty())
        // The retry after restart succeeds and charges exactly once.
        assertEquals(CommitResult.Committed(true, 1), engine.review(1, Decision.REMOVE))
    }

    @Test
    fun `reset review history does not reset the daily allowance or the queue`() = runTest {
        val engine = engine()
        for (i in 1..50) engine.review(i.toLong(), if (i <= 5) Decision.REMOVE else Decision.KEEP)
        engine.resetReviewHistory()
        assertEquals(50, engine.usedToday())
        assertIs<CommitResult.Blocked>(engine.review(99))
        assertEquals(5, store.tables.queue.size)
        assertEquals(5, store.tables.decisions.size, "only queued decisions survive")
        assertTrue(store.tables.actions.isEmpty())
    }

    @Test
    fun `undo of a photo already moved to trash is refused and dropped from the stack`() = runTest {
        val engine = engine()
        engine.review(1, Decision.KEEP)
        engine.review(2, Decision.REMOVE)
        engine.applyQueueCleanup(trashed = listOf(meta(2).key))
        assertIs<UndoResult.AlreadyTrashed>(engine.undo(session))
        assertEquals(ReviewState.TRASHED, store.tables.decisions[meta(2).key]?.state)
        // Earlier actions remain undoable.
        assertIs<UndoResult.Undone>(engine.undo(session))
        assertEquals(UndoResult.NothingToUndo, engine.undo(session))
    }

    @Test
    fun `keep instead from the queue is not charged`() = runTest {
        val engine = engine()
        engine.review(1, Decision.REMOVE)
        assertEquals(1, engine.keepInsteadOfRemove(listOf(meta(1).key)))
        assertEquals(1, engine.usedToday())
        assertTrue(store.tables.queue.isEmpty())
        assertEquals(ReviewState.KEPT, store.tables.decisions[meta(1).key]?.state)
    }

    @Test
    fun `externally deleted and changed photos leave the queue without crashing`() = runTest {
        val engine = engine()
        engine.review(1, Decision.REMOVE)
        engine.review(2, Decision.REMOVE)
        engine.review(3, Decision.REMOVE)
        val cleanup = engine.applyQueueCleanup(
            trashed = emptyList(),
            missing = listOf(meta(1).key, "unknown:key"),
            changed = listOf(meta(2).key),
        )
        assertEquals(1, cleanup.missing)
        assertEquals(1, cleanup.changed)
        assertEquals(setOf(meta(3).key), store.tables.queue.keys)
    }
}
