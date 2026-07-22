package com.muh.usbcameraviewer.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface MediaRepository {
    /** All app captures, newest first. */
    suspend fun loadItems(): List<MediaItem>

    /** True if the row was deleted. */
    suspend fun delete(item: MediaItem): Boolean
}

private const val ALBUM = "USB Camera Viewer"

@Singleton
class MediaStoreMediaRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MediaRepository {
        override suspend fun loadItems(): List<MediaItem> =
            withContext(Dispatchers.IO) {
                val photos = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
                val videos = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
                (photos + videos).sortedByDescending { it.dateAddedEpochSeconds }
            }

        private fun query(
            collection: Uri,
            isVideo: Boolean,
        ): List<MediaItem> {
            val projection =
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                )
            val (selection, args) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("%$ALBUM%")
                } else {
                    @Suppress("DEPRECATION")
                    "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/$ALBUM/%")
                }
            val items = mutableListOf<MediaItem>()
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    items +=
                        MediaItem(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString(),
                            isVideo = isVideo,
                            displayName = cursor.getString(nameCol),
                            dateAddedEpochSeconds = cursor.getLong(dateCol),
                        )
                }
            }
            return items
        }

        override suspend fun delete(item: MediaItem): Boolean =
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(Uri.parse(item.uri), null, null) > 0 }
                    .getOrDefault(false)
            }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class GalleryModule {
    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaStoreMediaRepository): MediaRepository
}
