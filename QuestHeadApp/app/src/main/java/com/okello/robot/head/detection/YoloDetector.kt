package com.okello.robot.head.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

private const val TAG = "YoloDetector"
private const val MODEL_FILE = "yolov8n.onnx"
private const val INPUT_SIZE = 320
private const val CONFIDENCE_THRESHOLD = 0.35f
private const val IOU_THRESHOLD = 0.45f

data class YoloResult(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val cx: Float, val cy: Float, val w: Float, val h: Float   // normalised [0,1]
)

private val COCO_LABELS = listOf(
    "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
    "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
    "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
    "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
    "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
    "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
    "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
    "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
    "remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator",
    "book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
)

val OBSTACLE_CLASSES = setOf("person","chair","couch","bed","dining table","bottle","cup",
    "backpack","suitcase","dog","cat","bicycle","motorcycle","car")

class YoloDetector(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    init {
        loadModel(context)
    }

    private fun loadModel(context: Context) {
        val modelFile = File(context.filesDir, MODEL_FILE)
        val modelBytes: ByteArray? = when {
            modelFile.exists() -> modelFile.readBytes()
            else -> try {
                context.assets.open(MODEL_FILE).readBytes()
            } catch (_: Exception) { null }
        }
        if (modelBytes == null) {
            Log.w(TAG, "YOLO model not found — push $MODEL_FILE to ${modelFile.absolutePath}")
            return
        }
        try {
            val opts = OrtSession.SessionOptions()
            session = env.createSession(modelBytes, opts)
            Log.i(TAG, "YOLO loaded via ONNX Runtime (${modelBytes.size / 1024}KB)")
        } catch (e: Exception) {
            Log.e(TAG, "ONNX session create failed: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap): List<YoloResult> {
        val sess = session ?: return emptyList()

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        // ONNX Runtime expects NCHW float32 input [1, 3, 320, 320]
        val floatBuf = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        scaled.recycle()

        // Write channels in order: R plane, G plane, B plane
        for (c in 0 until 3) {
            for (px in pixels) {
                val v = when (c) {
                    0 -> (px shr 16) and 0xFF
                    1 -> (px shr 8)  and 0xFF
                    else -> px       and 0xFF
                }
                floatBuf.put(v / 255f)
            }
        }
        floatBuf.rewind()

        val inputTensor = OnnxTensor.createTensor(
            env, floatBuf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val inputName = sess.inputNames.iterator().next()

        val output = try {
            sess.run(mapOf(inputName to inputTensor)).use { result ->
                // YOLOv8n output: [1, 84, 2100]
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<FloatArray>>)[0]  // [84][2100]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            inputTensor.close()
            return emptyList()
        }
        inputTensor.close()

        val results = mutableListOf<YoloResult>()
        for (col in 0 until 2100) {
            val cx = output[0][col]
            val cy = output[1][col]
            val w  = output[2][col]
            val h  = output[3][col]
            var maxConf = 0f; var maxCls = 0
            for (c in 0 until 80) {
                val s = output[4 + c][col]
                if (s > maxConf) { maxConf = s; maxCls = c }
            }
            if (maxConf >= CONFIDENCE_THRESHOLD) {
                results.add(YoloResult(
                    classId = maxCls,
                    label = COCO_LABELS.getOrElse(maxCls) { "unknown" },
                    confidence = maxConf,
                    cx = cx / INPUT_SIZE, cy = cy / INPUT_SIZE,
                    w  = w  / INPUT_SIZE, h  = h  / INPUT_SIZE
                ))
            }
        }
        return nms(results)
    }

    private fun nms(boxes: List<YoloResult>): List<YoloResult> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<YoloResult>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { iou(best, it) > IOU_THRESHOLD }
        }
        return keep
    }

    private fun iou(a: YoloResult, b: YoloResult): Float {
        val ax1 = a.cx - a.w / 2; val ax2 = a.cx + a.w / 2
        val ay1 = a.cy - a.h / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val bx2 = b.cx + b.w / 2
        val by1 = b.cy - b.h / 2; val by2 = b.cy + b.h / 2
        val ix = maxOf(0f, minOf(ax2, bx2) - maxOf(ax1, bx1))
        val iy = maxOf(0f, minOf(ay2, by2) - maxOf(ay1, by1))
        val inter = ix * iy
        val union = a.w * a.h + b.w * b.h - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        session?.close(); session = null
    }
}
