package com.swipegallery.domain.media

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** How much of the photo library the user has shared with the app. */
enum class PhotoAccess {
    /** All photos (READ_MEDIA_IMAGES, or READ_EXTERNAL_STORAGE on Android 11–12). */
    FULL,

    /** Android 14+: only photos the user picked (READ_MEDIA_VISUAL_USER_SELECTED). */
    SELECTED,

    /** No photo access. */
    NONE,
}

/**
 * Identity of one *version* of a MediaStore photo.
 *
 * MediaStore row IDs can be reused after a database rebuild, and a file can be edited in
 * place. Combining the volume, the row ID and MediaStore's generation counters (API 30+)
 * means a stale decision never attaches to a different or changed file. When generations
 * are unavailable (0), modification time and size are used instead.
 */
object MediaKeys {
    fun of(
        volume: String,
        mediaId: Long,
        generationAdded: Long,
        generationModified: Long,
        dateModifiedSeconds: Long,
        sizeBytes: Long?,
    ): String = if (generationAdded > 0L) {
        "$volume:$mediaId:g$generationAdded.$generationModified"
    } else {
        "$volume:$mediaId:t$dateModifiedSeconds.${sizeBytes ?: -1}"
    }
}

/** Minimal metadata persisted alongside decisions and queue items. */
data class PhotoMeta(
    val key: String,
    val mediaId: Long,
    val volume: String,
    val sizeBytes: Long?,
    val dateMillis: Long?,
    val width: Int?,
    val height: Int?,
)

/** A lightweight row from the media index. Never holds pixels. */
data class PhotoRef(
    val mediaId: Long,
    val volume: String,
    val key: String,
    /** DATE_TAKEN, when the file carries a capture date. */
    val dateTakenMillis: Long?,
    /** DATE_ADDED converted to millis. */
    val dateAddedMillis: Long?,
    /** Null when MediaStore reports no usable size. */
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val bucketId: String?,
    val bucketName: String?,
    val mimeType: String?,
) {
    val effectiveDateMillis: Long? get() = dateTakenMillis ?: dateAddedMillis
    val hasCaptureDate: Boolean get() = dateTakenMillis != null

    fun yearMonth(zone: ZoneId): YearMonth? = effectiveDateMillis?.let {
        YearMonth.from(Instant.ofEpochMilli(it).atZone(zone))
    }

    fun meta(): PhotoMeta = PhotoMeta(
        key = key,
        mediaId = mediaId,
        volume = volume,
        sizeBytes = sizeBytes,
        dateMillis = effectiveDateMillis,
        width = width,
        height = height,
    )
}
