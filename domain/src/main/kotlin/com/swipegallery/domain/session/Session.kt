package com.swipegallery.domain.session

import com.swipegallery.domain.media.PhotoRef
import java.time.YearMonth
import java.time.ZoneId

sealed interface SessionScope {
    /** Premium-only scopes are gated before a session starts and re-checked when it opens. */
    val requiresPremium: Boolean get() = false

    data object AllPhotos : SessionScope

    data class Month(val yearMonth: YearMonth) : SessionScope

    data class Album(val bucketId: String, val name: String) : SessionScope

    data class LargePhotos(val minBytes: Long) : SessionScope {
        override val requiresPremium: Boolean get() = true
    }
}

enum class SessionOrder {
    NEWEST,
    OLDEST,

    /** Premium: size ordering. Photos with unknown size are excluded from sized sessions. */
    LARGEST,
    ;

    val requiresPremium: Boolean get() = this == LARGEST
}

data class SessionSpec(
    val scope: SessionScope,
    val order: SessionOrder,
    /** Revisit mode: also offer photos that already have a decision. */
    val includeReviewed: Boolean = false,
) {
    val requiresPremium: Boolean get() = scope.requiresPremium || order.requiresPremium
}

/** Serialises [SessionScope] to two nullable-safe columns for storage. */
object ScopeCodec {
    fun type(scope: SessionScope): String = when (scope) {
        SessionScope.AllPhotos -> "all"
        is SessionScope.Month -> "month"
        is SessionScope.Album -> "album"
        is SessionScope.LargePhotos -> "large"
    }

    fun arg(scope: SessionScope): String? = when (scope) {
        SessionScope.AllPhotos -> null
        is SessionScope.Month -> scope.yearMonth.toString()
        is SessionScope.Album -> scope.bucketId
        is SessionScope.LargePhotos -> scope.minBytes.toString()
    }

    /** [label] carries the album name, which is display-only. Returns null for unknown data. */
    fun decode(type: String, arg: String?, label: String?): SessionScope? = runCatching {
        when (type) {
            "all" -> SessionScope.AllPhotos
            "month" -> SessionScope.Month(YearMonth.parse(requireNotNull(arg)))
            "album" -> SessionScope.Album(requireNotNull(arg), label.orEmpty())
            "large" -> SessionScope.LargePhotos(requireNotNull(arg).toLong())
            else -> null
        }
    }.getOrNull()
}

data class SessionPlan(
    /** Photos still to review in this session, in order. */
    val remaining: List<PhotoRef>,
    /** Photos matching the scope, before excluding reviewed ones. */
    val scopeTotal: Int,
    /** Photos excluded from a size-based scope because MediaStore has no size for them. */
    val unknownSizeCount: Int,
    /** Photos in scope that were skipped because they already have a decision. */
    val alreadyReviewedCount: Int,
)

data class MonthBucket(val yearMonth: YearMonth, val total: Int, val unreviewed: Int)

data class AlbumSummary(
    val bucketId: String,
    val name: String,
    val total: Int,
    val unreviewed: Int,
    /** Newest photo, used as the cover thumbnail. */
    val cover: PhotoRef,
)

object SessionPlanner {

    fun matches(photo: PhotoRef, scope: SessionScope, zone: ZoneId): Boolean = when (scope) {
        SessionScope.AllPhotos -> true
        is SessionScope.Month -> photo.yearMonth(zone) == scope.yearMonth
        is SessionScope.Album -> photo.bucketId == scope.bucketId
        is SessionScope.LargePhotos -> photo.sizeBytes != null && photo.sizeBytes >= scope.minBytes
    }

    private val newestFirst: Comparator<PhotoRef> =
        compareByDescending<PhotoRef, Long?>(nullsFirst()) { it.effectiveDateMillis }
            .thenByDescending { it.mediaId }

    private val oldestFirst: Comparator<PhotoRef> =
        compareBy<PhotoRef, Long?>(nullsLast()) { it.effectiveDateMillis }
            .thenBy { it.mediaId }

    private val largestFirst: Comparator<PhotoRef> =
        compareByDescending<PhotoRef, Long?>(nullsFirst()) { it.sizeBytes }
            .then(newestFirst)

    fun comparator(order: SessionOrder): Comparator<PhotoRef> = when (order) {
        SessionOrder.NEWEST -> newestFirst
        SessionOrder.OLDEST -> oldestFirst
        SessionOrder.LARGEST -> largestFirst
    }

    /**
     * @param reviewedKeys photo versions with any durable decision (kept, queued, trashed).
     * @param actedKeys photo versions already decided *in this session* (always excluded).
     */
    fun plan(
        photos: List<PhotoRef>,
        spec: SessionSpec,
        zone: ZoneId,
        reviewedKeys: Set<String>,
        actedKeys: Set<String> = emptySet(),
    ): SessionPlan {
        val sizeScoped = spec.scope is SessionScope.LargePhotos || spec.order == SessionOrder.LARGEST
        var unknownSize = 0
        var alreadyReviewed = 0
        val inScope = ArrayList<PhotoRef>()
        for (photo in photos) {
            if (sizeScoped && photo.sizeBytes == null) {
                if (spec.scope is SessionScope.LargePhotos || matches(photo, spec.scope, zone)) unknownSize++
                continue
            }
            if (matches(photo, spec.scope, zone)) inScope += photo
        }
        val remaining = inScope.filter { photo ->
            when {
                photo.key in actedKeys -> false
                !spec.includeReviewed && photo.key in reviewedKeys -> {
                    alreadyReviewed++
                    false
                }

                else -> true
            }
        }.sortedWith(comparator(spec.order))
        return SessionPlan(
            remaining = remaining,
            scopeTotal = inScope.size,
            unknownSizeCount = unknownSize,
            alreadyReviewedCount = alreadyReviewed,
        )
    }

    fun unreviewedCount(photos: List<PhotoRef>, scope: SessionScope, zone: ZoneId, reviewedKeys: Set<String>): Int =
        photos.count { it.key !in reviewedKeys && matches(it, scope, zone) }

    /** Months that contain photos, newest first. Photos without any date are not listed. */
    fun months(photos: List<PhotoRef>, zone: ZoneId, reviewedKeys: Set<String>): List<MonthBucket> {
        val totals = HashMap<YearMonth, IntArray>()
        for (photo in photos) {
            val ym = photo.yearMonth(zone) ?: continue
            val counts = totals.getOrPut(ym) { IntArray(2) }
            counts[0]++
            if (photo.key !in reviewedKeys) counts[1]++
        }
        return totals.entries
            .map { (ym, c) -> MonthBucket(ym, total = c[0], unreviewed = c[1]) }
            .sortedByDescending { it.yearMonth }
    }

    /** Albums (MediaStore buckets) sorted by most recent photo. */
    fun albums(photos: List<PhotoRef>, reviewedKeys: Set<String>): List<AlbumSummary> {
        data class Acc(var name: String, var total: Int, var unreviewed: Int, var cover: PhotoRef)

        val byBucket = LinkedHashMap<String, Acc>()
        for (photo in photos) {
            val id = photo.bucketId ?: continue
            val acc = byBucket[id]
            if (acc == null) {
                byBucket[id] = Acc(
                    name = photo.bucketName.orEmpty(),
                    total = 1,
                    unreviewed = if (photo.key in reviewedKeys) 0 else 1,
                    cover = photo,
                )
            } else {
                acc.total++
                if (photo.key !in reviewedKeys) acc.unreviewed++
                if (newestFirst.compare(photo, acc.cover) < 0) acc.cover = photo
                if (acc.name.isEmpty() && !photo.bucketName.isNullOrEmpty()) acc.name = photo.bucketName
            }
        }
        return byBucket.map { (id, a) -> AlbumSummary(id, a.name, a.total, a.unreviewed, a.cover) }
            .sortedWith(compareBy<AlbumSummary, PhotoRef>(newestFirst) { it.cover })
    }
}
