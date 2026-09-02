package com.okello.robot.head.detection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        textSize = 36f
        isAntiAlias = true
        isFakeBoldText = true
    }

    // Colours per class type
    private fun colorFor(label: String) = when {
        label.startsWith("face:") -> Color.CYAN
        label == "person"         -> Color.GREEN
        label in setOf("car","bicycle","motorcycle","bus","truck") -> Color.YELLOW
        else -> Color.RED
    }

    private var detections: List<YoloResult> = emptyList()

    fun update(results: List<YoloResult>) {
        detections = results
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (det in detections) {
            val color = colorFor(det.label)
            val left   = (det.cx - det.w / 2) * w
            val top    = (det.cy - det.h / 2) * h
            val right  = (det.cx + det.w / 2) * w
            val bottom = (det.cy + det.h / 2) * h
            val rect = RectF(left, top, right, bottom)

            boxPaint.color = color
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            val label = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val textW = textPaint.measureText(label)
            val textH = textPaint.textSize
            val tagTop = if (top > textH + 8) top - textH - 8 else bottom

            fillPaint.color = (color and 0xFFFFFF) or 0xCC000000.toInt()
            canvas.drawRoundRect(
                RectF(left, tagTop, left + textW + 16, tagTop + textH + 8),
                6f, 6f, fillPaint
            )
            textPaint.color = Color.WHITE
            canvas.drawText(label, left + 8, tagTop + textH, textPaint)
        }
    }
}
