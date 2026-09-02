package com.okello.robot.head.detection

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.okello.robot.head.BuildConfig
import com.okello.robot.head.mqtt.EnrolledPerson
import com.kbyai.facesdk.FaceBox
import com.kbyai.facesdk.FaceDetectionParam
import com.kbyai.facesdk.FaceSDK

private const val TAG = "FaceRecognizer"

data class FaceMatch(
    val name: String,
    val roleTag: String,
    val isVip: Boolean,
    val similarity: Float,
    val isLive: Boolean = true,
    val cx: Float = 0.5f,   // normalized face center x [0,1]
    val cy: Float = 0.5f,   // normalized face center y [0,1]
    val w: Float  = 0.25f,  // normalized face width
    val h: Float  = 0.35f   // normalized face height
)

class FaceRecognizer(assets: AssetManager) {

    var matchThreshold: Float = 0.65f

    private var sdkReady = false

    init {
        var ret = FaceSDK.setActivation(BuildConfig.KBY_LICENCE_KEY)
        if (ret == FaceSDK.SDK_SUCCESS) {
            ret = FaceSDK.init(assets)
            sdkReady = ret == FaceSDK.SDK_SUCCESS
            Log.i(TAG, if (sdkReady) "kby-ai SDK ready" else "SDK init failed: $ret")
        } else {
            Log.e(TAG, "kby-ai activation failed: $ret")
        }
    }

    // Enrolled: (person, face template ByteArray)
    private val enrolled = mutableListOf<Pair<EnrolledPerson, ByteArray>>()

    fun enroll(people: List<EnrolledPerson>) {
        if (!sdkReady) { Log.w(TAG, "enroll() skipped — SDK not ready"); return }
        Log.i(TAG, "Enrolling ${people.size} people…")
        enrolled.clear()
        val param = FaceDetectionParam().apply { check_liveness = false }
        for (p in people) {
            if (p.photoBase64.isBlank()) { Log.w(TAG, "No photo for ${p.name}, skipping"); continue }
            val bmp = decodeBase64Bitmap(p.photoBase64)
            if (bmp == null) { Log.w(TAG, "Bad base64 for ${p.name}"); continue }
            val enrollBmp = if (maxOf(bmp.width, bmp.height) < 300) {
                val s = 300f / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                    .also { bmp.recycle() }
            } else bmp
            Log.d(TAG, "Detecting face in photo for ${p.name} (${enrollBmp.width}x${enrollBmp.height})")
            val faces = FaceSDK.faceDetection(enrollBmp, param)
            if (faces.isNullOrEmpty()) {
                Log.w(TAG, "No face found in photo for ${p.name}")
                enrollBmp.recycle(); continue
            }
            val template = FaceSDK.templateExtraction(enrollBmp, faces[0])
            enrollBmp.recycle()
            enrolled.add(p to template)
            Log.i(TAG, "Enrolled: ${p.name} (${p.roleTag})")
        }
        Log.i(TAG, "Total enrolled: ${enrolled.size}")
    }

    /**
     * Preferred entry point for live camera frames. Converts NV21 bytes directly using
     * FaceSDK.yuv2Bitmap() (as per kby-ai docs) to avoid JPEG compression artefacts.
     * mode = 7 → portrait/landscape orientation used by Quest world-facing cameras.
     */
    fun detectFromNv21(nv21: ByteArray, width: Int, height: Int, rotationMode: Int = 7): List<FaceMatch> {
        if (!sdkReady) return emptyList()
        return try {
            val bmp = FaceSDK.yuv2Bitmap(nv21, width, height, rotationMode)
            if (bmp == null) { Log.w(TAG, "yuv2Bitmap returned null (mode=$rotationMode ${width}x${height})"); return emptyList() }
            Log.d(TAG, "yuv2Bitmap OK: ${bmp.width}x${bmp.height}")
            val result = detect(bmp)
            bmp.recycle()
            result
        } catch (t: Throwable) {
            Log.e(TAG, "detectFromNv21 native error: ${t.message}")
            emptyList()
        }
    }

    /** Fallback: run detection on an already-decoded Bitmap. */
    fun detect(frameBitmap: Bitmap): List<FaceMatch> {
        if (!sdkReady) return emptyList()

        val param = FaceDetectionParam().apply {
            check_liveness = false
        }
        val faces = try {
            FaceSDK.faceDetection(frameBitmap, param) ?: return emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "faceDetection native error: ${t.message}")
            return emptyList()
        }
        if (faces.isEmpty()) return emptyList()

        val bw = frameBitmap.width.toFloat()
        val bh = frameBitmap.height.toFloat()

        val matches = mutableListOf<FaceMatch>()
        for (face in faces) {
            // Normalize bounding box to [0,1]
            val cx = ((face.x1 + face.x2) / 2f) / bw
            val cy = ((face.y1 + face.y2) / 2f) / bh
            val fw = (face.x2 - face.x1).toFloat() / bw
            val fh = (face.y2 - face.y1).toFloat() / bh

            val template = try {
                FaceSDK.templateExtraction(frameBitmap, face)
            } catch (t: Throwable) {
                Log.e(TAG, "templateExtraction native error: ${t.message}")
                continue
            }

            if (enrolled.isEmpty()) {
                matches.add(FaceMatch("Unknown", "Guest", false, 0f, true, cx, cy, fw, fh))
                continue
            }

            val (bestPerson, bestSim) = enrolled
                .map { (p, ref) -> p to FaceSDK.similarityCalculation(template, ref) }
                .maxByOrNull { it.second }!!

            Log.i(TAG, "Best: ${bestPerson.name} sim=${"%.3f".format(bestSim)} threshold=$matchThreshold")
            if (bestSim >= matchThreshold) {
                matches.add(FaceMatch(bestPerson.name, bestPerson.roleTag, bestPerson.isVip, bestSim, true, cx, cy, fw, fh))
            } else {
                matches.add(FaceMatch("Unknown", "Guest", false, bestSim, true, cx, cy, fw, fh))
            }
        }
        return matches
    }

    private fun decodeBase64Bitmap(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }

    fun close() { /* SDK has no explicit teardown */ }
}
