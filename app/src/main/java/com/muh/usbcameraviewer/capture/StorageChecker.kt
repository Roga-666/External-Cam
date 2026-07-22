package com.muh.usbcameraviewer.capture

import android.content.Context
import android.os.StatFs
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Reports free space on the volume captures are written to. Faked in tests. */
interface StorageChecker {
    /** Available bytes on the capture volume. */
    fun availableBytes(): Long
}

@Singleton
class StatFsStorageChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : StorageChecker {
        override fun availableBytes(): Long = StatFs(context.cacheDir.path).availableBytes
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds
    @Singleton
    abstract fun bindStorageChecker(impl: StatFsStorageChecker): StorageChecker
}
