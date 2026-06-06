package com.okello.robot.head.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioInputManager"

// Gemini Live requires 16kHz mono PCM-16
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

// ~100ms chunks — keeps latency low while giving WebSocket meaningful payloads
private const val CHUNK_MS = 100
private val CHUNK_BYTES = (SAMPLE_RATE * 2 * CHUNK_MS) / 1000  // 3200 bytes

class AudioInputManager(
    private val onChunk: (base64Pcm: String) -> Unit
) {
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    fun start() {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            CHUNK_BYTES * 4
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // hardware noise reduction
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        ).also { rec ->
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                return
            }
            rec.startRecording()
            Log.i(TAG, "Mic recording started at ${SAMPLE_RATE}Hz")
        }

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(CHUNK_BYTES)
            while (isActive) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    onChunk(encoded)
                }
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        recorder?.stop()
        recorder?.release()
        recorder = null
        Log.i(TAG, "Mic stopped")
    }
}
