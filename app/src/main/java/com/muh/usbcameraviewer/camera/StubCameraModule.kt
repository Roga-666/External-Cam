package com.muh.usbcameraviewer.camera

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Temporary binding so Hilt compiles before the real controller exists.
 * Task 6 deletes this file and creates CameraModule.kt binding AusbcCameraController.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StubCameraModule {
    @Binds
    @Singleton
    abstract fun bindCameraController(impl: StubCameraController): CameraController
}

/**
 * Stub implementation until AUSBC integration is available.
 */
class StubCameraController : CameraController {
    override val events = kotlinx.coroutines.flow.MutableSharedFlow<CameraEvent>()

    @javax.inject.Inject
    constructor()

    override fun startMonitoring() {}
    override fun stopMonitoring() {}
    override fun requestPermission() {}
    override fun open(preview: Any, preferred: Resolution?) {}
    override fun close() {}
    override fun setResolution(resolution: Resolution) {}
    override fun setRotation(degrees: Int) {}
    override fun setMirror(mirrored: Boolean) {}

    override suspend fun capturePhoto(target: java.io.File): Result<java.io.File> =
        Result.failure(Exception("Not implemented"))

    override suspend fun startRecording(
        target: java.io.File,
        withAudio: Boolean,
        bitRate: Int,
    ): Result<Unit> = Result.failure(Exception("Not implemented"))

    override suspend fun stopRecording(): Result<java.io.File> =
        Result.failure(Exception("Not implemented"))
}
