package com.jarvis.ai.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

class AudioLevelEngine(private val context: Context) {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    fun start(): Boolean {
        if (running.get()) return true
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // getMinBufferSize() returns an error code (< 0) on failure; the floor also keeps the
        // buffer large enough for 1024-sample reads.
        val bufferSize = (minBufferSize * 2).coerceAtLeast(MIN_BUFFER_BYTES)
        val record = createRecord(bufferSize) ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            releaseQuietly(record)
            return false
        }
        audioRecord = record
        if (runCatching { record.startRecording() }.isFailure) {
            releaseQuietly(record)
            audioRecord = null
            return false
        }
        running.set(true)
        val thread = Thread({ captureLoop(record) }, THREAD_NAME)
        captureThread = thread
        thread.start()
        return true
    }

    fun stop() {
        running.set(false)
        val record = audioRecord ?: return
        // Stop recording FIRST: record.stop() makes any pending blocking read()
        // in the capture thread return immediately, so the loop observes
        // running==false and exits instead of blocking forever on a silent mic.
        // Only then join the thread so we never release() a record a thread is
        // still blocked reading (use-after-release / IllegalStateException).
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        captureThread?.let { thread ->
            runCatching { thread.join(JOIN_TIMEOUT_MS) }
        }
        captureThread = null
        releaseQuietly(record)
        audioRecord = null
        _level.value = 0f
    }

    @SuppressLint("MissingPermission") // Permission is checked explicitly in start().
    @Suppress("DEPRECATION")
    private fun createRecord(bufferSize: Int): AudioRecord? =
        runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull()

    private fun captureLoop(record: AudioRecord) {
        val buffer = ShortArray(CHUNK_SIZE)
        var smoothed = 0f
        while (running.get()) {
            val read = runCatching { record.read(buffer, 0, CHUNK_SIZE) }.getOrDefault(0)
            if (read <= 0) continue
            var sumSquares = 0.0
            for (index in 0 until read) {
                val sample = buffer[index].toDouble()
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares / read)
            val ratio = (rms / FULL_SCALE_AMPLITUDE).coerceAtLeast(MIN_RATIO)
            val db = 20.0 * log10(ratio)
            val normalized = (((db + DB_WINDOW) / DB_WINDOW).toFloat()).coerceIn(0f, 1f)
            val coefficient = if (normalized > smoothed) ATTACK_COEFFICIENT else RELEASE_COEFFICIENT
            smoothed += (normalized - smoothed) * coefficient
            _level.value = smoothed
        }
    }

    private fun releaseQuietly(record: AudioRecord) {
        runCatching { record.release() }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val CHUNK_SIZE = 1024
        const val MIN_BUFFER_BYTES = 3200
        const val FULL_SCALE_AMPLITUDE = 32767.0
        const val MIN_RATIO = 1e-4
        const val DB_WINDOW = 60.0
        const val ATTACK_COEFFICIENT = 0.55f
        const val RELEASE_COEFFICIENT = 0.12f
        const val THREAD_NAME = "jarvis_mic"
        const val JOIN_TIMEOUT_MS = 500L
    }
}
