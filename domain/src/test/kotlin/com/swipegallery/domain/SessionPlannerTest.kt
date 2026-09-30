package com.swipegallery.domain

import com.swipegallery.domain.session.ScopeCodec
import com.swipegallery.domain.session.SessionOrder
import com.swipegallery.domain.session.SessionPlanner
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.domain.session.SessionSpec
import com.swipegallery.domain.time.nextResetMillis
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPlannerTest {
    private val zone = ZoneId.of("Europe/London")
    private fun millis(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000

    private val photos = listOf(
        photo(1, millis(2026, 9, 1), size = 12_000_000, bucket = "camera"),
        photo(2, millis(2026, 9, 20), size = 800_000, bucket = "screenshots"),
        photo(3, millis(2026, 8, 5), size = null, bucket = "camera"),
        photo(4, millis(2025, 12, 31), size = 6_000_000, bucket = "whatsapp"),
        photo(5, dateMillis = null, size = 3_000_000, bucket = "camera"),
    )

    @Test
    fun `default sessions exclude reviewed photos and revisit includes them`() {
        val reviewed = setOf(photos[1].key)
        val spec = SessionSpec(SessionScope.AllPhotos, SessionOrder.NEWEST)
        val plan = SessionPlanner.plan(photos, spec, zone, reviewed)
        assertEquals(listOf(1L, 3L, 4L, 5L), plan.remaining.map { it.mediaId })
        assertEquals(1, plan.alreadyReviewedCount)

        val revisit = SessionPlanner.plan(photos, spec.copy(includeReviewed = true), zone, reviewed, actedKeys = setOf(photos[0].key))
        assertEquals(listOf(2L, 3L, 4L, 5L), revisit.remaining.map { it.mediaId })
    }

    @Test
    fun `oldest first keeps undated photos last`() {
        val plan = SessionPlanner.plan(photos, SessionSpec(SessionScope.AllPhotos, SessionOrder.OLDEST), zone, emptySet())
        assertEquals(listOf(4L, 3L, 1L, 2L, 5L), plan.remaining.map { it.mediaId })
    }

    @Test
    fun `month scope uses the local calendar`() {
        val plan = SessionPlanner.plan(photos, SessionSpec(SessionScope.Month(YearMonth.of(2026, 9)), SessionOrder.NEWEST), zone, emptySet())
        assertEquals(listOf(2L, 1L), plan.remaining.map { it.mediaId })
        val months = SessionPlanner.months(photos, zone, setOf(photos[0].key))
        assertEquals(YearMonth.of(2026, 9), months.first().yearMonth)
        assertEquals(2, months.first().total)
        assertEquals(1, months.first().unreviewed)
    }

    @Test
    fun `large photos filter handles unknown sizes honestly`() {
        val spec = SessionSpec(SessionScope.LargePhotos(minBytes = 5_000_000), SessionOrder.LARGEST)
        assertTrue(spec.requiresPremium)
        val plan = SessionPlanner.plan(photos, spec, zone, emptySet())
        assertEquals(listOf(1L, 4L), plan.remaining.map { it.mediaId })
        assertEquals(1, plan.unknownSizeCount)
    }

    @Test
    fun `album scope and album summaries`() {
        val plan = SessionPlanner.plan(photos, SessionSpec(SessionScope.Album("camera", "Camera"), SessionOrder.NEWEST), zone, emptySet())
        assertEquals(listOf(1L, 3L, 5L), plan.remaining.map { it.mediaId })
        assertFalse(SessionSpec(SessionScope.Album("camera", "Camera"), SessionOrder.NEWEST).requiresPremium)

        val albums = SessionPlanner.albums(photos, reviewedKeys = setOf(photos[0].key))
        val camera = albums.first { it.bucketId == "camera" }
        assertEquals(3, camera.total)
        assertEquals(2, camera.unreviewed)
        assertEquals(1L, camera.cover.mediaId)
        assertEquals("screenshots", albums.first().bucketId, "albums sorted by newest photo")
    }

    @Test
    fun `scope codec round trips`() {
        val scopes = listOf(
            SessionScope.AllPhotos,
            SessionScope.Month(YearMonth.of(2026, 2)),
            SessionScope.Album("-1739773001", "Camera"),
            SessionScope.LargePhotos(5_000_000),
        )
        for (s in scopes) {
            assertEquals(s, ScopeCodec.decode(ScopeCodec.type(s), ScopeCodec.arg(s), (s as? SessionScope.Album)?.name))
        }
        assertEquals(null, ScopeCodec.decode("month", "garbage", null))
    }

    @Test
    fun `next reset is the next local midnight across DST`() {
        // Clocks go back in London on 25 Oct 2026: that day has 25 hours.
        val clock = FakeClock.at(LocalDateTime.of(2026, 10, 25, 12, 0), zone)
        val expected = LocalDate.of(2026, 10, 26).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, clock.nextResetMillis())
    }
}
