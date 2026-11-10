package com.muh.usbcameraviewer.camera

import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

class FakeCameraController : CameraController {
    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    override val events = _events

    val openCalls = mutableListOf<Pair<Any, Resolution?>>()
    val resolutionCalls = mutableListOf<Resolution>()
    val yuyvCalls = mutableListOf<Resolution>()
    var yuyvSupported = false
    val rotationCalls = mutableListOf<Int>()
    val mirrorCalls = mutableListOf<Boolean>()
    val recordingStarts = mutableListOf<Triple<File, Boolean, Int>>()
    var monitoring = false
    var permissionRequests = 0
    var closed = false
    var stopRecordingCalls = 0

    var photoResult: (File) -> Result<File> = { Result.success(it.apply { writeText("jpeg") }) }
    var startRecordingResult: Result<Unit> = Result.success(Unit)
    var stopRecordingResult: Result<File>? = null // null = succeed with last started file

    fun emit(event: CameraEvent) {
        check(_events.tryEmit(event)) { "event buffer full" }
    }

    override fun startMonitoring() {
        monitoring = true
    }

    override fun stopMonitoring() {
        monitoring = false
    }

    override fun requestPermission() {
        permissionRequests++
    }

    override fun open(
        preview: Any,
        preferred: Resolution?,
    ) {
        openCalls += preview to preferred
    }

    private var finalizedRecordings = 0

    override fun close() {
        closed = true
        // Mirror the real controller's contract: tearing down mid-record finalizes the
        // recording and reports it back as a RecordingFinalized event.
        if (recordingStarts.size > stopRecordingCalls + finalizedRecordings) {
            finalizedRecordings++
            _events.tryEmit(
                CameraEvent.RecordingFinalized(recordingStarts.last().first.apply { writeText("mp4") }),
            )
        }
    }

    override suspend fun setResolution(resolution: Resolution) {
        resolutionCalls += resolution
    }

    override suspend fun retryWithYuyv(resolution: Resolution): Boolean {
        yuyvCalls += resolution
        if (yuyvSupported) previewLive = true
        return yuyvSupported
    }

    override fun setRotation(degrees: Int) {
        rotationCalls += degrees
    }

    override fun setMirror(mirrored: Boolean) {
        mirrorCalls += mirrored
    }

    val brightnessCalls = mutableListOf<Int>()
    val contrastCalls = mutableListOf<Int>()
    var fakeBrightness: Int? = null
    var fakeContrast: Int? = null

    override fun setBrightness(percent: Int) {
        brightnessCalls += percent
        fakeBrightness = percent
    }

    override fun setContrast(percent: Int) {
        contrastCalls += percent
        fakeContrast = percent
    }

    override fun getBrightness(): Int? = fakeBrightness

    override fun getContrast(): Int? = fakeContrast

    val zoomCalls = mutableListOf<Triple<Float, Float, Float>>()

    override fun setZoom(
        zoom: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        zoomCalls += Triple(zoom, offsetX, offsetY)
    }

    override suspend fun capturePhoto(target: File): Result<File> = photoResult(target)

    override suspend fun startRecording(
        target: File,
        withAudio: Boolean,
        bitRate: Int,
    ): Result<Unit> {
        recordingStarts += Triple(target, withAudio, bitRate)
        return startRecordingResult
    }

    override suspend fun stopRecording(): Result<File> {
        stopRecordingCalls++
        return stopRecordingResult ?: Result.success(recordingStarts.last().first.apply { writeText("mp4") })
    }

    var previewLive = true

    override suspend fun isPreviewLive(): Boolean = previewLive
}
