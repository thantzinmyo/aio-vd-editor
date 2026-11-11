package com.tharunbirla.librecuts.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * CapCut-style export progress: the progress is a white border that traces
 * the perimeter of the video preview — clockwise from the top-left corner:
 * top edge left→right, right edge top→bottom, bottom edge right→left,
 * left edge bottom→top. The unfilled remainder is a dark-grey (#333333)
 * track of the same thickness.
 *
 * Measured from CapCut's export screen (screen recording, 1440x3200):
 * the preview is 604x1050 px (42% of screen width, 9:16); the border is
 * ~12 px thick (~3 dp); the track is #333333 and the fill is pure white;
 * at 27.6% the white covers the whole top edge plus the top ~1/4 of the
 * right edge, and at 89.8% it covers top + right + bottom + the bottom
 * ~9/10 of the left edge — confirming the clockwise order above.
 * Square caps, no rounding.
 */
class ExportPerimeterProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0..1, clockwise from the top-left corner. */
    var progress: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (next != field) {
                field = next
                invalidate()
            }
        }

    private val strokeWidthPx: Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics
        )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.BUTT
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.BUTT
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = strokeWidthPx / 2f
        val rect = RectF(half, half, width - half, height - half)
        // The unfilled track: the whole rectangle outline.
        canvas.drawRect(rect, trackPaint)
        // The filled portion: a path along the perimeter, clockwise
        // from the top-left corner.
        val perimeter = 2f * (rect.width() + rect.height())
        var remaining = progress * perimeter
        if (remaining <= 0f) return
        val path = Path()
        val topLen = minOf(remaining, rect.width())
        path.moveTo(rect.left, rect.top)
        path.lineTo(rect.left + topLen, rect.top)
        remaining -= topLen
        if (remaining > 0f) {
            val rightLen = minOf(remaining, rect.height())
            path.moveTo(rect.right, rect.top)
            path.lineTo(rect.right, rect.top + rightLen)
            remaining -= rightLen
        }
        if (remaining > 0f) {
            val bottomLen = minOf(remaining, rect.width())
            path.moveTo(rect.right, rect.bottom)
            path.lineTo(rect.right - bottomLen, rect.bottom)
            remaining -= bottomLen
        }
        if (remaining > 0f) {
            val leftLen = minOf(remaining, rect.height())
            path.moveTo(rect.left, rect.bottom)
            path.lineTo(rect.left, rect.bottom - leftLen)
        }
        canvas.drawPath(path, fillPaint)
    }
}
