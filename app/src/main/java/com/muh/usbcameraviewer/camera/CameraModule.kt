package com.muh.usbcameraviewer.camera

import android.content.Context
import android.view.View
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PreviewFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {
    @Binds
    @Singleton
    abstract fun bindCameraController(impl: AusbcCameraController): CameraController

    companion object {
        @Provides
        @Singleton
        @PreviewFactory
        fun providePreviewFactory(): (Context) -> View = { AspectRatioTextureView(it) }
    }
}
