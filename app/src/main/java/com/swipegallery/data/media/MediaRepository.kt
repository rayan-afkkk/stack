package com.swipegallery.data.media

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.swipegallery.domain.media.MediaKeys
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.media.PhotoMeta
import com.swipegallery.domain.media.PhotoRef
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.trash.ProbeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MediaIndex {
    data object Loading : MediaIndex
    data object NoAccess : MediaIndex

    /** [photos] holds lightweight metadata only; images are decoded on demand by Coil. */
    data class Ready(val access: PhotoAccess, val photos: List<PhotoRef>, val scannedAtMillis: Long) : MediaIndex {
        val keys: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { photos.mapTo(HashSet(photos.size)) { it.key } }
        fun byKey(key: String): PhotoRef? = photos.firstOrNull { it.key == key }
    }

    data class Failed(val reason: String) : MediaIndex
}

fun PhotoMeta.contentUri(): Uri = mediaUri(volume, mediaId)
fun PhotoRef.contentUri(): Uri = mediaUri(volume, mediaId)
fun QueueItem.contentUri(): Uri = photo.contentUri()

private fun mediaUri(volume: String, id: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id)

/**
 * Reads photos through MediaStore content URIs only (no file paths). Scans run on [io] and are
 * restarted (the obsolete scan is cancelled) whenever access or the library changes.
 */
@OptIn(FlowPreview::class)
class MediaRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    private val _index = MutableStateFlow<MediaIndex>(MediaIndex.Loading)
    val index: StateFlow<MediaIndex> = _index.asStateFlow()

    private val _access = MutableStateFlow(PhotoPermissions.currentAccess(context))
    val access: StateFlow<PhotoAccess> = _access.asStateFlow()

    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun start() {
        scope.launch {
            refreshRequests.collectLatest {
                val result = withContext(io) { scan() }
                _index.value = result
            }
        }
        scope.launch {
            libraryChanges().debounce(600).collect { refresh() }
        }
        refresh()
    }

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    /** Called when the app returns to the foreground: permissions may have changed in Settings. */
    fun onForeground() {
        val now = PhotoPermissions.currentAccess(context)
        val changed = now != _access.value
        _access.value = now
        // With selected-photos access the user may have changed the selection elsewhere.
        if (changed || now == PhotoAccess.SELECTED || _index.value is MediaIndex.Failed) refresh()
    }

    private suspend fun scan(): MediaIndex {
        val access = PhotoPermissions.currentAccess(context)
        _access.value = access
        if (access == PhotoAccess.NONE) return MediaIndex.NoAccess
        return try {
            val photos = ArrayList<PhotoRef>()
            resolver.query(COLLECTION, SCAN_PROJECTION, null, null, "${MediaStore.MediaColumns._ID} DESC")?.use { c ->
                val cols = ScanColumns(c)
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    photos += cols.read(c)
                }
            }
            MediaIndex.Ready(access, photos, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            _access.value = PhotoPermissions.currentAccess(context)
            MediaIndex.NoAccess
        } catch (e: RuntimeException) {
            MediaIndex.Failed(e.javaClass.simpleName)
        }
    }

    /**
     * Current state of each queued photo, including whether it is already in the device trash.
     * Versions are compared so a file edited after it was queued is never trashed unseen.
     */
    suspend fun probe(items: Collection<QueueItem>): Map<String, ProbeState> = withContext(io) {
        val result = HashMap<String, ProbeState>(items.size)
        items.groupBy { it.photo.volume }.forEach { (volume, group) ->
            group.chunked(PROBE_CHUNK).forEach { chunk ->
                val byId = chunk.associateBy { it.photo.mediaId }
                val args = Bundle().apply {
                    // IDs are Longs, so inlining them is safe and avoids SQLite's variable limit.
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.MediaColumns._ID} IN (${byId.keys.joinToString(",")})",
                    )
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
                try {
                    resolver.query(MediaStore.Images.Media.getContentUri(volume), PROBE_PROJECTION, args, null)?.use { c ->
                        val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val trashedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                        val genAddedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED)
                        val genModCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
                        val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                        val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        while (c.moveToNext()) {
                            val item = byId[c.getLong(idCol)] ?: continue
                            // Trashing bumps GENERATION_MODIFIED, so check the trash flag first.
                            val state = if (c.getInt(trashedCol) == 1) {
                                ProbeState.TRASHED
                            } else {
                                val key = MediaKeys.of(
                                    volume = volume,
                                    mediaId = item.photo.mediaId,
                                    generationAdded = c.getLong(genAddedCol),
                                    generationModified = c.getLong(genModCol),
                                    dateModifiedSeconds = c.getLong(modCol),
                                    sizeBytes = c.positiveLongOrNull(sizeCol),
                                )
                                if (key == item.key) ProbeState.PRESENT else ProbeState.CHANGED
                            }
                            result[item.key] = state
                        }
                    }
                } catch (e: SecurityException) {
                    // Access revoked mid-way: leave these unresolved (reported as ABSENT).
                } catch (e: IllegalArgumentException) {
                    // Unknown volume (e.g. SD card removed): treated as ABSENT.
                }
            }
        }
        for (item in items) result.putIfAbsent(item.key, ProbeState.ABSENT)
        result
    }

    suspend fun isAvailable(photo: PhotoRef): Boolean {
        val item = QueueItem(photo.meta(), 0L)
        return probe(listOf(item))[photo.key] == ProbeState.PRESENT
    }

    /**
     * Builds Android's own trash confirmation (MediaStore.createTrashRequest, API 30+).
     * Never deletes permanently, and never bypasses the system dialog.
     */
    fun createTrashRequest(items: List<QueueItem>): PendingIntent =
        MediaStore.createTrashRequest(resolver, items.map { it.contentUri() }, true)

    private fun libraryChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private class ScanColumns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val volume = c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
        val genAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED)
        val genModified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
        val dateTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val dateAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val orientation = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
        val bucketId = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        val bucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

        fun read(c: Cursor): PhotoRef {
            val mediaId = c.getLong(id)
            val vol = c.getString(volume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            val sizeBytes = c.positiveLongOrNull(size)
            val w = c.positiveIntOrNull(width)
            val h = c.positiveIntOrNull(height)
            val rotated = c.isNull(orientation).not() && (c.getInt(orientation) == 90 || c.getInt(orientation) == 270)
            return PhotoRef(
                mediaId = mediaId,
                volume = vol,
                key = MediaKeys.of(
                    volume = vol,
                    mediaId = mediaId,
                    generationAdded = c.getLong(genAdded),
                    generationModified = c.getLong(genModified),
                    dateModifiedSeconds = c.getLong(dateModified),
                    sizeBytes = sizeBytes,
                ),
                dateTakenMillis = c.positiveLongOrNull(dateTaken),
                dateAddedMillis = c.positiveLongOrNull(dateAdded)?.times(1000L),
                sizeBytes = sizeBytes,
                width = if (rotated) h else w,
                height = if (rotated) w else h,
                bucketId = c.getString(bucketId),
                bucketName = c.getString(bucketName),
                mimeType = c.getString(mime),
            )
        }
    }

    companion object {
        private const val PROBE_CHUNK = 400

        private val COLLECTION: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        private val SCAN_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )

        private val PROBE_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
    }
}

private fun Cursor.positiveLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index).takeIf { it > 0L }

private fun Cursor.positiveIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index).takeIf { it > 0 }
