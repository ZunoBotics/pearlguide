package com.okello.robot.head.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.okello.robot.head.mqtt.EnrolledPerson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

private const val TAG = "FaceRecognizer"
private const val FACE_CROP_SIZE = 96
private const val MATCH_THRESHOLD = 0.70   // cosine similarity threshold

data class FaceMatch(val name: String, val roleTag: String, val isVip: Boolean, val similarity: Float)

class FaceRecognizer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build()
    )

    // Each entry: (person, histogram feature vector of their reference photo)
    private val enrolled = mutableListOf<Pair<EnrolledPerson, FloatArray>>()

    fun enroll(people: List<EnrolledPerson>) {
        enrolled.clear()
        for (p in people) {
            if (p.photoBase64.isBlank()) continue
            val bmp = decodeBase64Bitmap(p.photoBase64) ?: continue
            val feat = extractFeatures(bmp)
            bmp.recycle()
            enrolled.add(p to feat)
        }
        Log.i(TAG, "Enrolled ${enrolled.size} faces")
    }

    suspend fun detect(frameBitmap: Bitmap): List<FaceMatch> {
        val faces = detectFaces(frameBitmap)
        if (faces.isEmpty()) return emptyList()

        val matches = mutableListOf<FaceMatch>()

        for (face in faces) {
            val box = face.boundingBox
            val x = box.left.coerceAtLeast(0)
            val y = box.top.coerceAtLeast(0)
            val w = box.width().coerceAtMost(frameBitmap.width - x)
            val h = box.height().coerceAtMost(frameBitmap.height - y)
            if (w <= 0 || h <= 0) continue

            val crop = Bitmap.createBitmap(frameBitmap, x, y, w, h)
            val feat = extractFeatures(crop)
            crop.recycle()

            if (enrolled.isEmpty()) {
                matches.add(FaceMatch("Unknown", "Guest", false, 0f))
                continue
            }
            val best = enrolled.maxByOrNull { (_, ref) -> cosineSim(feat, ref) }!!
            val sim = cosineSim(feat, best.first.let { enrolled.first { it.first == best.first }.second })
            val person = best.first
            if (sim >= MATCH_THRESHOLD) {
                matches.add(FaceMatch(person.name, person.roleTag, person.isVip, sim))
                Log.d(TAG, "Face match: ${person.name} (sim=%.2f)".format(sim))
            } else {
                matches.add(FaceMatch("Unknown", "Guest", false, sim))
            }
        }
        return matches
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private fun extractFeatures(bmp: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bmp, FACE_CROP_SIZE, FACE_CROP_SIZE, true)
        // 16-bin histogram per channel (R, G, B) = 48 features
        val hist = FloatArray(48)
        val pixels = IntArray(FACE_CROP_SIZE * FACE_CROP_SIZE)
        scaled.getPixels(pixels, 0, FACE_CROP_SIZE, 0, 0, FACE_CROP_SIZE, FACE_CROP_SIZE)
        scaled.recycle()
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            hist[r / 16]      += 1f
            hist[16 + g / 16] += 1f
            hist[32 + b / 16] += 1f
        }
        // L2 normalise
        val norm = sqrt(hist.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(48) { hist[it] / norm }
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na.toDouble() * nb.toDouble()).toFloat()
        return if (denom < 1e-6f) 0f else dot / denom
    }

    private fun decodeBase64Bitmap(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }

    fun close() { detector.close() }
}
