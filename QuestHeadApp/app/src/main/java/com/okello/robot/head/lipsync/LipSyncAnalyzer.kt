package com.okello.robot.head.lipsync

import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "LipSyncAnalyzer"

// PCM amplitude → jaw angle mapping (degrees)
// Architecture spec: silence=0, AH=35, EE=20, OO=25, MM=5, FF=10
// Without full phoneme recognition, we use energy-based approximation:
// low energy = closed, high energy = wide open (AH).
// This is "good enough" for continuous mouth movement.

data class JawCommand(val angleDegs: Int, val durationMs: Int)

class LipSyncAnalyzer(
    private val onJawCommand: (JawCommand) -> Unit
) {
    // PCM-16 at 24kHz (Gemini output rate)
    private val sampleRate = 24000

    fun analyze(pcmBytes: ByteArray) {
        val samples = pcmBytes.size / 2
        if (samples == 0) return

        // Chunk into ~30ms windows (one animation frame at 30fps)
        val windowSamples = (sampleRate * 0.030).toInt()
        var offset = 0

        while (offset + windowSamples * 2 <= pcmBytes.size) {
            val rms = rms(pcmBytes, offset, windowSamples)
            val angle = rmsToAngle(rms)
            val durationMs = (windowSamples * 1000) / sampleRate

            onJawCommand(JawCommand(angle, durationMs))
            offset += windowSamples * 2
        }
    }

    private fun rms(pcm: ByteArray, byteOffset: Int, numSamples: Int): Float {
        var sum = 0.0
        for (i in 0 until numSamples) {
            val lo = pcm[byteOffset + i * 2].toInt() and 0xFF
            val hi = pcm[byteOffset + i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample * sample.toDouble()
        }
        return sqrt(sum / numSamples).toFloat()
    }

    private fun rmsToAngle(rms: Float): Int {
        return when {
            rms < 200f -> 0     // silence
            rms < 800f -> 5     // very quiet / MM
            rms < 2000f -> 10   // soft / FF
            rms < 5000f -> 20   // medium / EE
            rms < 10000f -> 25  // medium-high / OO
            else -> 35          // loud / AH
        }
    }

    fun buildMqttPayload(cmd: JawCommand): String =
        JSONObject()
            .put("servo", "jaw")
            .put("angle", cmd.angleDegs)
            .put("duration_ms", cmd.durationMs)
            .toString()
}
