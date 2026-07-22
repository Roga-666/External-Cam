package com.muh.usbcameraviewer.camera

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the preview into an MP4 with MediaCodec + MediaMuxer, optionally with microphone audio.
 *
 * AUSBC 3.2.7's own recorder writes through a file API gated behind WRITE_EXTERNAL_STORAGE, which
 * no longer exists on Android 13+, and its Java frame callback does not fire under OpenGL preview
 * rendering. So we pull frames straight off the preview [Bitmap] via [frameProvider], draw each
 * onto the video encoder's input Surface, and mux the encoded stream ourselves. Audio, when
 * enabled, is captured on its own thread. The muxer starts only once every expected track has its
 * format, and all writes to it are synchronized (MediaMuxer is not thread-safe).
 */
class FrameVideoRecorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val withAudio: Boolean,
    private val frameProvider: () -> Bitmap?,
) {
    private val running = AtomicBoolean(false)

    private lateinit var muxer: MediaMuxer
    private val muxerLock = Any()
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    private lateinit var videoCodec: MediaCodec
    private lateinit var inputSurface: Surface
    private val dstRect = Rect(0, 0, width, height)

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun start() {
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoCodec = createVideoCodec()
        inputSurface = videoCodec.createInputSurface()
        videoCodec.start()
        running.set(true)
        videoThread = Thread({ videoLoop() }, "ucv-video-enc").apply { start() }
        if (withAudio) {
            audioThread = Thread({ audioLoop() }, "ucv-audio-enc").apply { start() }
        }
    }

    /** Stop encoding and finalize the file. Returns the output file, or null on failure. */
    fun stop(): File? {
        if (!running.compareAndSet(true, false)) return null
        return runCatching {
            videoThread?.join(THREAD_JOIN_MS)
            audioThread?.join(THREAD_JOIN_MS)
            synchronized(muxerLock) {
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            runCatching { videoCodec.stop() }
            runCatching { videoCodec.release() }
            runCatching { inputSurface.release() }
            outputFile
        }.getOrElse {
            Log.e(TAG, "stop failed", it)
            null
        }
    }

    private fun createVideoCodec(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        val frameIntervalMs = MILLIS_PER_SEC / FRAME_RATE
        while (running.get()) {
            val started = System.currentTimeMillis()
            val bitmap = frameProvider()
            if (bitmap != null) {
                drawFrame(bitmap)
                bitmap.recycle()
            }
            drainVideo(info, endOfStream = false)
            val elapsed = System.currentTimeMillis() - started
            val sleep = frameIntervalMs - elapsed
            if (sleep > 0) runCatching { Thread.sleep(sleep) }
        }
        runCatching { videoCodec.signalEndOfInputStream() }
        drainVideo(info, endOfStream = true)
    }

    private fun drawFrame(bitmap: Bitmap) {
        val canvas = inputSurface.lockCanvas(null) ?: return
        try {
            canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dstRect, null)
        } finally {
            inputSurface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drainVideo(
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
    ) {
        while (true) {
            val outIndex = videoCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer.addTrack(videoCodec.outputFormat)
                        maybeStartMuxer()
                    }
                outIndex < 0 -> if (!endOfStream) return
                else -> {
                    val encoded = videoCodec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) writeSample(videoTrackIndex, encoded, info)
                    videoCodec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    @Suppress("LongMethod") // one linear encode loop reads clearer than split across helpers
    private fun audioLoop() {
        val minBuf =
            AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, AUDIO_READ_BYTES)
        val record =
            try {
                @Suppress("MissingPermission")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "no audio permission; recording video-only", e)
                markAudioDisabled()
                return
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord init failed; recording video-only")
            record.release()
            markAudioDisabled()
            return
        }

        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
        audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec.start()
        record.startRecording()

        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(AUDIO_READ_BYTES)
        while (running.get()) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) continue
            val inIndex = audioCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIndex >= 0) {
                val buffer = audioCodec.getInputBuffer(inIndex)!!
                buffer.clear()
                buffer.put(pcm, 0, read)
                // Absolute nanoTime base to match the video encoder's Surface-input timestamps,
                // so the muxer computes a correct duration instead of a huge A/V-skewed one.
                val pts = System.nanoTime() / MICROS_DIVISOR
                audioCodec.queueInputBuffer(inIndex, 0, read, pts, 0)
            }
            drainAudio(audioCodec, info)
        }
        val inIndex = audioCodec.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
        if (inIndex >= 0) {
            val pts = System.nanoTime() / MICROS_DIVISOR
            audioCodec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainAudio(audioCodec, info)

        runCatching { record.stop() }
        runCatching { record.release() }
        runCatching { audioCodec.stop() }
        runCatching { audioCodec.release() }
    }

    private fun drainAudio(
        audioCodec: MediaCodec,
        info: MediaCodec.BufferInfo,
    ) {
        while (true) {
            val outIndex = audioCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer.addTrack(audioCodec.outputFormat)
                        maybeStartMuxer()
                    }
                outIndex < 0 -> return
                else -> {
                    val encoded = audioCodec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) writeSample(audioTrackIndex, encoded, info)
                    audioCodec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Audio couldn't start: drop to a single expected track so the muxer can still start. */
    private fun markAudioDisabled() {
        synchronized(muxerLock) {
            audioTrackIndex = DISABLED_TRACK
            maybeStartMuxer()
        }
    }

    private fun maybeStartMuxer() {
        // Caller holds muxerLock.
        if (muxerStarted) return
        val haveVideo = videoTrackIndex >= 0
        val haveAudio = !withAudio || audioTrackIndex >= 0 || audioTrackIndex == DISABLED_TRACK
        if (haveVideo && haveAudio) {
            muxer.start()
            muxerStarted = true
        }
    }

    private fun writeSample(
        track: Int,
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        if (track < 0) return
        synchronized(muxerLock) {
            if (!muxerStarted) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer.writeSampleData(track, buffer, info)
        }
    }

    private companion object {
        const val TAG = "FrameVideoRecorder"
        const val FRAME_RATE = 24
        const val I_FRAME_INTERVAL_SEC = 1
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val END_OF_STREAM_TIMEOUT_US = 100_000L
        const val THREAD_JOIN_MS = 3_000L
        const val MICROS_DIVISOR = 1_000L
        const val MILLIS_PER_SEC = 1_000L
        const val DISABLED_TRACK = -2
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_BIT_RATE = 128_000
        const val AUDIO_READ_BYTES = 4_096
    }
}
