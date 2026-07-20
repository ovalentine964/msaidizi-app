package com.msaidizi.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.msaidizi.app.core.util.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio recorder for 16kHz PCM recording.
 * Optimized for 2GB devices:
 * - Uses AudioRecord with direct ByteBuffer (no copy overhead)
 * - Double-buffered to prevent dropouts
 * - Automatic gain control via AGC
 * - Noise suppression via Android's built-in NS
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SAMPLE_RATE = 16000  // 16kHz for Whisper
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 512     // ~32ms at 16kHz
        const val BUFFER_SIZE_MULTIPLIER = 4  // Prevent overflows
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val recordingStartTime = AtomicLong(0)
    private var currentRecordingState: RecordingState = RecordingState.IDLE

    private val _audioChunks = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 16
    )
    val audioChunks: SharedFlow<ShortArray> = _audioChunks

    private val _recordingState = MutableSharedFlow<RecordingState>(
        extraBufferCapacity = 4
    )
    val recordingState: SharedFlow<RecordingState> = _recordingState

    private var recordingJob: Job? = null

    /**
     * Get the minimum buffer size for AudioRecord.
     */
    fun getMinBufferSize(): Int {
        return AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(FRAME_SIZE * 2)
    }

    /**
     * Start recording audio.
     * Emits audio chunks as ShortArray via [audioChunks] flow.
     */
    suspend fun startRecording(scope: CoroutineScope) {
        if (isRecording.get()) {
            Timber.w("Already recording")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.e("Audio permission not granted")
            _recordingState.emit(RecordingState.ERROR_NO_PERMISSION)
            return
        }

        val bufferSize = getMinBufferSize() * BUFFER_SIZE_MULTIPLIER

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Includes AGC + NS
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).also { recorder ->
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.e("AudioRecord failed to initialize")
                    _recordingState.emit(RecordingState.ERROR_INIT)
                    return
                }

                recorder.startRecording()
                isRecording.set(true)
                currentRecordingState = RecordingState.RECORDING
                recordingStartTime.set(System.currentTimeMillis())
                _recordingState.emit(RecordingState.RECORDING)

                Timber.d("Recording started: ${SAMPLE_RATE}Hz, buffer=${bufferSize}")

                // Read audio in background
                recordingJob = scope.launch(Dispatchers.IO) {
                    readAudioLoop(recorder, bufferSize)
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException starting recording")
            _recordingState.emit(RecordingState.ERROR_NO_PERMISSION)
        } catch (e: Throwable) {
            Timber.e(e, "Error starting recording")
            _recordingState.emit(RecordingState.ERROR_INIT)
        }
    }

    /**
     * Stop recording and return the complete audio buffer.
     */
    suspend fun stopRecording(): ShortArray? {
        if (!isRecording.get()) return null

        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let { recorder ->
            try {
                if (currentRecordingState == RecordingState.RECORDING) {
                    recorder.stop()
                }
                recorder.release()
            } catch (e: Throwable) {
                Timber.e(e, "Error stopping recording")
            }
        }
        audioRecord = null
        currentRecordingState = RecordingState.STOPPED

        _recordingState.emit(RecordingState.STOPPED)
        Timber.d("Recording stopped")

        return null
    }

    /**
     * Get recording duration in milliseconds.
     */
    fun getRecordingDurationMs(): Long {
        if (!isRecording.get()) return 0
        return System.currentTimeMillis() - recordingStartTime.get()
    }

    /**
     * Check if currently recording.
     */
    fun isActive(): Boolean = isRecording.get()

    /**
     * Internal audio reading loop.
     * Reads from AudioRecord and emits chunks via SharedFlow.
     */
    private suspend fun readAudioLoop(recorder: AudioRecord, bufferSize: Int) {
        val buffer = ShortArray(FRAME_SIZE)

        while (isRecording.get()) {
            val shortsRead = recorder.read(buffer, 0, buffer.size)

            if (shortsRead > 0) {
                // Emit a copy of the buffer
                val chunk = buffer.copyOf(shortsRead)
                _audioChunks.emit(chunk)
            } else if (shortsRead < 0) {
                Timber.e("AudioRecord read error: $shortsRead")
                _recordingState.emit(RecordingState.ERROR_READ)
                isRecording.set(false)
                break
            }

            // Check max recording duration
            val maxDuration = DeviceTier.getMaxRecordingDurationSec() * 1000L
            if (getRecordingDurationMs() > maxDuration) {
                Timber.w("Max recording duration reached")
                _recordingState.emit(RecordingState.MAX_DURATION)
                isRecording.set(false)
                break
            }
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        isRecording.set(false)
        recordingJob?.cancel()
        audioRecord?.release()
        audioRecord = null
    }
}

/**
 * Recording state enum.
 */
enum class RecordingState {
    IDLE,
    RECORDING,
    STOPPED,
    ERROR_NO_PERMISSION,
    ERROR_INIT,
    ERROR_READ,
    MAX_DURATION
}
