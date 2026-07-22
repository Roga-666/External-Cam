package com.muh.usbcameraviewer.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CaptureStore {
    /** Scratch file in app cache the camera writes into before publishing. */
    fun tempFile(displayName: String): File

    /** Publish to Pictures/USB Camera Viewer; returns content URI string. Deletes [src]. */
    suspend fun publishPhoto(src: File, displayName: String): String

    /** Publish to Movies/USB Camera Viewer; returns content URI string. Deletes [src]. */
    suspend fun publishVideo(src: File, displayName: String): String
}

private const val ALBUM = "USB Camera Viewer"

@Singleton
class MediaStoreCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : CaptureStore {

    override fun tempFile(displayName: String): File =
        File(context.cacheDir, displayName)

    override suspend fun publishPhoto(src: File, displayName: String): String =
        publish(
            src = src,
            displayName = displayName,
            mimeType = "image/jpeg",
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir = "${Environment.DIRECTORY_PICTURES}/$ALBUM",
        )

    override suspend fun publishVideo(src: File, displayName: String): String =
        publish(
            src = src,
            displayName = displayName,
            mimeType = "video/mp4",
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir = "${Environment.DIRECTORY_MOVIES}/$ALBUM",
        )

    private suspend fun publish(
        src: File,
        displayName: String,
        mimeType: String,
        collection: Uri,
        relativeDir: String,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        relativeDir.substringBefore('/'),
                    ),
                    ALBUM,
                ).apply { mkdirs() }
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore insert failed for $displayName")
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: error("openOutputStream failed for $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        src.delete()
        uri.toString()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureModule {
    @Binds
    @Singleton
    abstract fun bindCaptureStore(impl: MediaStoreCaptureStore): CaptureStore
}
