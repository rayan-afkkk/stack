package com.swipegallery

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swipegallery.data.db.AppDatabase
import com.swipegallery.data.db.RoomReviewStore
import com.swipegallery.domain.media.PhotoMeta
import com.swipegallery.domain.review.CommitResult
import com.swipegallery.domain.review.Decision
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.review.ReviewEngine
import com.swipegallery.domain.review.UndoResult
import com.swipegallery.domain.time.AppClock
import com.swipegallery.domain.trash.ProbeState
import com.swipegallery.domain.trash.TrashPlanner
import com.swipegallery.domain.trash.TrashRun
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/** The same engine rules as the JVM tests, against the real Room schema and transactions. */
@RunWith(AndroidJUnit4::class)
class RoomReviewEngineTest {
    private lateinit var db: AppDatabase
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = object : AppClock {
        var now = LocalDateTime.of(2026, 9, 30, 21, 0).atZone(zone).toInstant().toEpochMilli()
        override fun nowMillis() = now
        override fun zone(): ZoneId = zone
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun engine(premium: Boolean = false) = ReviewEngine(RoomReviewStore(db), clock, isPremium = { premium })

    private fun meta(id: Long) = PhotoMeta("external_primary:$id:g$id.$id", id, "external_primary", 2_000_000L, 1L, 10, 10)

    @Test
    fun fiftyReviewsThenBlocked_andSurvivesANewEngine() = runBlocking {
        val first = engine()
        for (i in 1L..50L) {
            val result = first.commit(1, meta(i), if (i % 2L == 0L) Decision.KEEP else Decision.REMOVE)
            assertTrue("review $i: $result", result is CommitResult.Committed)
        }
        assertEquals(CommitResult.Blocked(50), first.commit(1, meta(51), Decision.KEEP))
        val restarted = engine()
        assertEquals(50, restarted.usedToday())
        assertEquals(CommitResult.Blocked(50), restarted.commit(1, meta(51), Decision.KEEP))
        assertEquals(25, db.reviewDao().queue().size)
    }

    @Test
    fun undoAndRecommitIsFree_andNextDayResetsButKeepsQueue() = runBlocking {
        val e = engine()
        e.commit(1, meta(1), Decision.REMOVE)
        assertTrue(e.undo(1) is UndoResult.Undone)
        assertEquals(CommitResult.Committed(charged = false, usedToday = 1), e.commit(1, meta(1), Decision.REMOVE))

        clock.now += 4 * 3_600_000L // 01:00 next local day
        assertEquals(0, e.usedToday())
        assertEquals(1, db.reviewDao().queue().size)
    }

    @Test
    fun resetHistoryKeepsLedgerAndQueue() = runBlocking {
        val e = engine()
        e.commit(1, meta(1), Decision.KEEP)
        e.commit(1, meta(2), Decision.REMOVE)
        e.resetReviewHistory()
        assertEquals(2, e.usedToday())
        assertEquals(listOf(meta(2).key), db.reviewDao().queue().map { it.mediaKey })
        assertEquals(listOf(meta(2).key), db.reviewDao().observeDecidedKeys().first())
    }

    @Test
    fun cancelledAndPartialTrashOnlyTouchConfirmedItems() = runBlocking {
        val e = engine()
        (1L..3L).forEach { e.commit(1, meta(it), Decision.REMOVE) }
        val items = db.reviewDao().queue()
            .map { QueueItem(meta(it.mediaId), it.addedAt) }
            .sortedBy { it.photo.mediaId }
        val present = items.associate { it.key to ProbeState.PRESENT }

        // Cancelled: nothing changes.
        val cancelledRun = TrashRun(TrashPlanner.prepare(items, present, fullAccess = true))
        val batch = cancelledRun.nextBatch()!!
        val cancelled = TrashPlanner.reconcile(batch, confirmed = false, probe = present)
        e.applyQueueCleanup(cancelled.trashed.map { it.key }, cancelled.missing.map { it.key }, cancelled.changed.map { it.key })
        assertEquals(3, db.reviewDao().queue().size)

        // Partial: only the confirmed item leaves the queue.
        val after = present + (items[0].key to ProbeState.TRASHED)
        val partial = TrashPlanner.reconcile(items, confirmed = true, probe = after)
        e.applyQueueCleanup(partial.trashed.map { it.key })
        assertEquals(setOf(items[1].key, items[2].key), db.reviewDao().queue().map { it.mediaKey }.toSet())
    }
}
