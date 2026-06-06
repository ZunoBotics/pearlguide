package com.okello.robot.head.camera

import android.graphics.Bitmap
import android.graphics.Color

// Block-matching stereo depth estimator (Sum of Absolute Differences).
// Expects left/right bitmaps from cameras 50 and 51 (Quest 3 world-facing passthrough).
// Output: colorized disparity map + nearest obstacle distance in cm.
//
// Calibration note: baselineMm and focalPx are estimates. For metric accuracy,
// run camera calibration with a checkerboard and update these values.
object StereoDepthEstimator {

    private const val BLOCK_SIZE = 7
    private const val HALF_BLOCK = BLOCK_SIZE / 2
    private const val MAX_DISPARITY = 64
    private const val WORK_W = 160
    private const val WORK_H = 120

    // Quest 3 stereo camera baseline ~65 mm; focal at WORK_W resolution ~80 px (90° FOV estimate)
    private const val DEFAULT_BASELINE_MM = 65f
    private const val DEFAULT_FOCAL_PX = 80f

    data class DepthResult(
        val depthMap: Bitmap,
        val nearestCm: Float?   // null if no valid disparity found
    )

    fun compute(
        leftBmp: Bitmap,
        rightBmp: Bitmap,
        baselineMm: Float = DEFAULT_BASELINE_MM,
        focalPx: Float = DEFAULT_FOCAL_PX
    ): DepthResult {
        val left = toGray(Bitmap.createScaledBitmap(leftBmp, WORK_W, WORK_H, true))
        val right = toGray(Bitmap.createScaledBitmap(rightBmp, WORK_W, WORK_H, true))

        val disparity = IntArray(WORK_W * WORK_H)
        var maxDisp = 0

        for (y in HALF_BLOCK until WORK_H - HALF_BLOCK) {
            for (x in HALF_BLOCK until WORK_W - HALF_BLOCK) {
                val searchMax = minOf(x - HALF_BLOCK, MAX_DISPARITY)
                var bestDisp = 0
                var bestSad = Int.MAX_VALUE

                for (d in 0 until searchMax) {
                    var sad = 0
                    for (by in -HALF_BLOCK..HALF_BLOCK) {
                        val rowL = (y + by) * WORK_W
                        val rowR = rowL
                        for (bx in -HALF_BLOCK..HALF_BLOCK) {
                            sad += kotlin.math.abs(left[rowL + x + bx] - right[rowR + x + bx - d])
                        }
                    }
                    if (sad < bestSad) {
                        bestSad = sad
                        bestDisp = d
                    }
                }

                disparity[y * WORK_W + x] = bestDisp
                if (bestDisp > maxDisp) maxDisp = bestDisp
            }
        }

        // Nearest obstacle = pixel with max disparity
        var nearestCm: Float? = null
        if (maxDisp > 0) {
            nearestCm = (baselineMm * focalPx / maxDisp) / 10f
        }

        // Jet colormap: 0 disparity = blue (far), max disparity = red (near)
        val scale = if (maxDisp > 0) 255f / maxDisp else 1f
        val colors = IntArray(WORK_W * WORK_H) { jetColor((disparity[it] * scale).toInt().coerceIn(0, 255)) }
        val map = Bitmap.createBitmap(colors, WORK_W, WORK_H, Bitmap.Config.ARGB_8888)

        return DepthResult(map, nearestCm)
    }

    private fun toGray(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        return IntArray(px.size) {
            val c = px[it]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
    }

    private fun jetColor(v: Int): Int {
        val r = when {
            v < 96  -> 0
            v < 160 -> (v - 96) * 255 / 64
            else    -> 255
        }
        val g = when {
            v < 32  -> 0
            v < 96  -> (v - 32) * 255 / 64
            v < 160 -> 255
            v < 224 -> (224 - v) * 255 / 64
            else    -> 0
        }
        val b = when {
            v < 32  -> 255
            v < 96  -> (96 - v) * 255 / 64
            else    -> 0
        }
        return Color.argb(200, r, g, b)   // slight transparency so overlay isn't opaque
    }
}
