package com.muh.usbcameraviewer.camera

import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeCameraController : CameraController {

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    override val events = _events

    val openCalls = mutableListOf<Pair<Any, Resolution?>>()
    val resolutionCalls = mutableListOf<Resolution>()
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

    override fun startMonitoring() { monitoring = true }
    override fun stopMonitoring() { monitoring = false }
    override fun requestPermission() { permissionRequests++ }
    override fun open(preview: Any, preferred: Resolution?) { openCalls += preview to preferred }
    override fun close() { closed = true }
    override fun setResolution(resolution: Resolution) { resolutionCalls += resolution }
    override fun setRotation(degrees: Int) { rotationCalls += degrees }
    override fun setMirror(mirrored: Boolean) { mirrorCalls += mirrored }

    override suspend fun capturePhoto(target: File): Result<File> = photoResult(target)

    override suspend fun startRecording(target: File, withAudio: Boolean, bitRate: Int): Result<Unit> {
        recordingStarts += Triple(target, withAudio, bitRate)
        return startRecordingResult
    }

    override suspend fun stopRecording(): Result<File> {
        stopRecordingCalls++
        return stopRecordingResult ?: Result.success(recordingStarts.last().first.apply { writeText("mp4") })
    }
}
