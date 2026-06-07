package com.okello.robot.head.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "YoloDetector"
private const val MODEL_FILE = "yolov8n_float32.tflite"
private const val INPUT_SIZE = 320
private const val CONFIDENCE_THRESHOLD = 0.35f
private const val IOU_THRESHOLD = 0.45f

data class YoloResult(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val cx: Float, val cy: Float, val w: Float, val h: Float   // normalised [0,1]
)

// COCO 80 class labels
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

// Classes that become "obstacle" alerts with a distance estimate
val OBSTACLE_CLASSES = setOf("person","chair","couch","bed","dining table","bottle","cup",
    "backpack","suitcase","dog","cat","bicycle","motorcycle","car")

class YoloDetector(context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    init {
        loadModel(context)
    }

    private fun loadModel(context: Context) {
        // Prefer model pushed to files dir (via adb / Pi export)
        val filesModel = File(context.filesDir, MODEL_FILE)
        val buffer: MappedByteBuffer? = when {
            filesModel.exists() -> mapFile(filesModel)
            else -> tryLoadFromAssets(context)
        }
        if (buffer == null) {
            Log.w(TAG, "YOLO model not found — detection disabled. " +
                "Push yolov8n_float32.tflite to ${filesModel.absolutePath}")
            return
        }
        try {
            gpuDelegate = GpuDelegate()
            val opts = Interpreter.Options().addDelegate(gpuDelegate!!)
            interpreter = Interpreter(buffer, opts)
            Log.i(TAG, "YOLO loaded (GPU delegate)")
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate failed, falling back to CPU: ${e.message}")
            gpuDelegate?.close(); gpuDelegate = null
            interpreter = Interpreter(buffer)
            Log.i(TAG, "YOLO loaded (CPU)")
        }
    }

    private fun mapFile(file: File): MappedByteBuffer =
        FileInputStream(file).channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())

    private fun tryLoadFromAssets(context: Context): MappedByteBuffer? = try {
        val afd = context.assets.openFd(MODEL_FILE)
        FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
    } catch (_: Exception) { null }

    fun detect(bitmap: Bitmap): List<YoloResult> {
        val interp = interpreter ?: return emptyList()

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((px shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((px and 0xFF) / 255f)
        }
        inputBuffer.rewind()
        scaled.recycle()

        // YOLOv8n output: [1, 84, 2100]  (cx,cy,w,h + 80 class scores)
        val rawOutput = Array(1) { Array(84) { FloatArray(2100) } }
        try {
            interp.run(inputBuffer, rawOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            return emptyList()
        }

        val results = mutableListOf<YoloResult>()
        val data = rawOutput[0]
        for (col in 0 until 2100) {
            val cx = data[0][col]
            val cy = data[1][col]
            val w  = data[2][col]
            val h  = data[3][col]
            var maxConf = 0f; var maxCls = 0
            for (c in 0 until 80) {
                val s = data[4 + c][col]
                if (s > maxConf) { maxConf = s; maxCls = c }
            }
            if (maxConf >= CONFIDENCE_THRESHOLD) {
                results.add(YoloResult(maxCls, COCO_LABELS.getOrElse(maxCls) { "unknown" },
                    maxConf, cx / INPUT_SIZE, cy / INPUT_SIZE, w / INPUT_SIZE, h / INPUT_SIZE))
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
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
    }
}
