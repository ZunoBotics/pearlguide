package com.okello.robot.head.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioOutputManager"

// Gemini Live outputs 24kHz mono PCM-16
private const val OUTPUT_SAMPLE_RATE = 24000
private const val OUTPUT_CHANNELS = AudioFormat.CHANNEL_OUT_MONO
private const val OUTPUT_FORMAT = AudioFormat.ENCODING_PCM_16BIT

class AudioOutputManager(
    // Called with each PCM chunk before playback so lip-sync can extract phonemes
    private val onPcmBeforePlay: ((ByteArray) -> Unit)? = null
) {
    private var audioTrack: AudioTrack? = null
    private val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private var playJob: kotlinx.coroutines.Job? = null

    val isPlaying: Boolean get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun start() {
        val bufferSize = maxOf(
            AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, OUTPUT_CHANNELS, OUTPUT_FORMAT),
            OUTPUT_SAMPLE_RATE * 2   // 1 second buffer
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(OUTPUT_CHANNELS)
                    .setEncoding(OUTPUT_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        playJob = CoroutineScope(Dispatchers.IO).launch {
            for (chunk in queue) {
                if (!isActive) break
                onPcmBeforePlay?.invoke(chunk)
                audioTrack?.write(chunk, 0, chunk.size)
            }
        }

        Log.i(TAG, "AudioOutput ready at ${OUTPUT_SAMPLE_RATE}Hz")
    }

    fun enqueue(base64Pcm: String) {
        val bytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
        queue.trySend(bytes)
    }

    // Barge-in: Gemini interrupted itself — flush the queue
    fun flush() {
        while (queue.tryReceive().isSuccess) { /* drain */ }
        audioTrack?.flush()
        Log.d(TAG, "Audio queue flushed (barge-in)")
    }

    fun stop() {
        playJob?.cancel()
        queue.close()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        Log.i(TAG, "AudioOutput stopped")
    }
}
