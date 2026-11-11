package com.tharunbirla.librecuts.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout

/**
 * Draggable editor for an [com.tharunbirla.librecuts.models.EditOperation.AddBlurBox].
 *
 * Cloned from [DraggableImageOverlayView]'s drag / pinch-scale / snap /
 * selection-border patterns, minus the media loading, chroma key and mask
 * code (a blur box has no image). The box itself is a translucent rounded
 * rect — a stand-in for the export blur, since a true live blur would need
 * RenderEffect (API 31+, minSdk is 26).
 */
class DraggableBlurBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Public callbacks ──────────────────────────────────────────────────────
    var onBlurBoxCommitted: ((relativeX: Float, relativeY: Float, relativeWidth: Float, relativeHeight: Float, blurRadius: Int) -> Unit)? = null
    var onPositionChanged: ((relativeX: Float, relativeY: Float) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var isEditingActive = false
    private var relativeX = 0.5f  // center default
    private var relativeY = 0.5f
    private var relativeWidth = 0.3f
    private var relativeHeight = 0.3f
    private var blurRadius = 10

    private var videoWidth = 0
    private var videoHeight = 0

    // ── Snap & Drag tracking ──────────────────────────────────────────────────
    var isSnappingEnabled = true
    private var showVerticalGuideline = false
    private var showHorizontalGuideline = false
    private var isDragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CEC0EC")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    // ── UI components ─────────────────────────────────────────────────────────
    private val boxView: View = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.parseColor("#66FFFFFF"))
            setStroke(3, Color.parseColor("#CCFFFFFF"))
        }
    }

    // ── Selection border paint ────────────────────────────────────────────────
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val selectionRect = RectF()

    private val cornerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(5f, 0f, 1f, Color.parseColor("#99000000"))
    }

    private val cornerPath = android.graphics.Path()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            relativeWidth = (relativeWidth * scaleFactor).coerceIn(0.05f, 1.0f)
            relativeHeight = (relativeHeight * scaleFactor).coerceIn(0.05f, 1.0f)
            updateBoxViewSizeAndPosition()
            return true
        }
    })

    init {
        visibility = GONE
        val boxViewParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(boxView, boxViewParams)
        setWillNotDraw(false)
    }

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        post {
            updateBoxViewSizeAndPosition()
        }
    }

    private fun getVideoRect(): RectF {
        val rect = RectF()
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            return rect
        }

        val containerRatio = width.toFloat() / height
        val videoRatio = videoWidth.toFloat() / videoHeight

        if (videoRatio > containerRatio) {
            val h = width / videoRatio
            val top = (height - h) / 2f
            rect.set(0f, top, width.toFloat(), top + h)
        } else {
            val w = height * videoRatio
            val left = (width - w) / 2f
            rect.set(left, 0f, left + w, height.toFloat())
        }
        return rect
    }

    private fun updateBoxViewSizeAndPosition() {
        if (width <= 0 || height <= 0) return
        val videoRect = getVideoRect()

        val widthOnScreen = relativeWidth * videoRect.width()
        val heightOnScreen = relativeHeight * videoRect.height()

        boxView.layoutParams = LayoutParams(widthOnScreen.toInt(), heightOnScreen.toInt())

        val targetCenterX = videoRect.left + (relativeX * videoRect.width())
        val targetCenterY = videoRect.top + (relativeY * videoRect.height())

        boxView.x = targetCenterX - widthOnScreen / 2f
        boxView.y = targetCenterY - heightOnScreen / 2f

        invalidate()
    }

    private fun updateRelativePosition() {
        if (width <= 0 || height <= 0) return
        val videoRect = getVideoRect()

        val centerX = boxView.x + boxView.width / 2f
        val centerY = boxView.y + boxView.height / 2f

        relativeX = if (videoRect.width() > 0) ((centerX - videoRect.left) / videoRect.width()).coerceIn(0f, 1f) else 0.5f
        relativeY = if (videoRect.height() > 0) ((centerY - videoRect.top) / videoRect.height()).coerceIn(0f, 1f) else 0.5f
        onPositionChanged?.invoke(relativeX, relativeY)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun activate() {
        isEditingActive = true
        relativeX = 0.5f
        relativeY = 0.5f
        relativeWidth = 0.3f
        relativeHeight = 0.3f
        blurRadius = 10
        visibility = VISIBLE
        post {
            updateBoxViewSizeAndPosition()
        }
    }

    fun activateForEdit(op: com.tharunbirla.librecuts.models.EditOperation.AddBlurBox) {
        isEditingActive = true
        relativeX = op.relativeX
        relativeY = op.relativeY
        relativeWidth = op.relativeWidth
        relativeHeight = op.relativeHeight
        blurRadius = op.blurRadius
        visibility = VISIBLE
        post {
            updateBoxViewSizeAndPosition()
        }
    }

    fun deactivate() {
        isEditingActive = false
        visibility = GONE
    }

    fun setBlurRadius(radius: Int) {
        blurRadius = radius.coerceIn(2, 20)
        invalidate()
    }

    fun commitBlurBox() {
        updateRelativePosition()
        onBlurBoxCommitted?.invoke(relativeX, relativeY, relativeWidth, relativeHeight, blurRadius)
        deactivate()
    }

    fun getRelativeX(): Float = relativeX
    fun getRelativeY(): Float = relativeY
    fun getRelativeWidth(): Float = relativeWidth
    fun getRelativeHeight(): Float = relativeHeight
    fun getBlurRadius(): Int = blurRadius

    fun setProperties(rx: Float, ry: Float, rw: Float, rh: Float, radius: Int) {
        relativeX = rx
        relativeY = ry
        relativeWidth = rw
        relativeHeight = rh
        blurRadius = radius.coerceIn(2, 20)
        updateBoxViewSizeAndPosition()
    }

    // ── Touch handling for drag and scale ───────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEditingActive) return false

        scaleDetector.onTouchEvent(ev)

        if (scaleDetector.isInProgress || ev.pointerCount > 1) {
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchX = ev.x
                val touchY = ev.y
                if (touchX in boxView.left.toFloat()..boxView.right.toFloat() &&
                    touchY in boxView.top.toFloat()..boxView.bottom.toFloat()
                ) {
                    isDragging = true
                    dragOffsetX = touchX - boxView.x
                    dragOffsetY = touchY - boxView.y
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditingActive) return false

        scaleDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            isDragging = false
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragOffsetX = event.x - boxView.x
                dragOffsetY = event.y - boxView.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val videoRect = getVideoRect()
                    val centerX = event.x - dragOffsetX + boxView.width / 2f
                    val centerY = event.y - dragOffsetY + boxView.height / 2f

                    val constrainedCenterX = centerX.coerceIn(videoRect.left, videoRect.right)
                    val constrainedCenterY = centerY.coerceIn(videoRect.top, videoRect.bottom)

                    val videoCenterX = videoRect.centerX()
                    val videoCenterY = videoRect.centerY()
                    val threshold = 16f

                    var snappedX = constrainedCenterX
                    var snappedY = constrainedCenterY

                    if (isSnappingEnabled) {
                        if (Math.abs(constrainedCenterX - videoCenterX) < threshold) {
                            snappedX = videoCenterX
                            showVerticalGuideline = true
                        } else {
                            showVerticalGuideline = false
                        }
                        if (Math.abs(constrainedCenterY - videoCenterY) < threshold) {
                            snappedY = videoCenterY
                            showHorizontalGuideline = true
                        } else {
                            showHorizontalGuideline = false
                        }
                    } else {
                        showVerticalGuideline = false
                        showHorizontalGuideline = false
                    }

                    boxView.x = snappedX - boxView.width / 2f
                    boxView.y = snappedY - boxView.height / 2f
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    showVerticalGuideline = false
                    showHorizontalGuideline = false
                    updateRelativePosition()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (isEditingActive) {
            val videoRect = getVideoRect()
            if (showVerticalGuideline) {
                canvas.drawLine(videoRect.centerX(), videoRect.top, videoRect.centerX(), videoRect.bottom, guidelinePaint)
            }
            if (showHorizontalGuideline) {
                canvas.drawLine(videoRect.left, videoRect.centerY(), videoRect.right, videoRect.centerY(), guidelinePaint)
            }

            selectionRect.set(
                boxView.x - 4f,
                boxView.y - 4f,
                boxView.x + boxView.width + 4f,
                boxView.y + boxView.height + 4f
            )
            canvas.drawRect(selectionRect, selectionPaint)

            // 4 modern curved corner bracket handles touching the border
            val bracketLength = 22f
            val cornerRadius = 8f

            // Top-Left Corner
            cornerPath.reset()
            cornerPath.moveTo(selectionRect.left + bracketLength, selectionRect.top)
            cornerPath.lineTo(selectionRect.left + cornerRadius, selectionRect.top)
            cornerPath.quadTo(selectionRect.left, selectionRect.top, selectionRect.left, selectionRect.top + cornerRadius)
            cornerPath.lineTo(selectionRect.left, selectionRect.top + bracketLength)
            canvas.drawPath(cornerPath, cornerHandlePaint)

            // Top-Right Corner
            cornerPath.reset()
            cornerPath.moveTo(selectionRect.right - bracketLength, selectionRect.top)
            cornerPath.lineTo(selectionRect.right - cornerRadius, selectionRect.top)
            cornerPath.quadTo(selectionRect.right, selectionRect.top, selectionRect.right, selectionRect.top + cornerRadius)
            cornerPath.lineTo(selectionRect.right, selectionRect.top + bracketLength)
            canvas.drawPath(cornerPath, cornerHandlePaint)

            // Bottom-Left Corner
            cornerPath.reset()
            cornerPath.moveTo(selectionRect.left + bracketLength, selectionRect.bottom)
            cornerPath.lineTo(selectionRect.left + cornerRadius, selectionRect.bottom)
            cornerPath.quadTo(selectionRect.left, selectionRect.bottom, selectionRect.left, selectionRect.bottom - cornerRadius)
            cornerPath.lineTo(selectionRect.left, selectionRect.bottom - bracketLength)
            canvas.drawPath(cornerPath, cornerHandlePaint)

            // Bottom-Right Corner
            cornerPath.reset()
            cornerPath.moveTo(selectionRect.right - bracketLength, selectionRect.bottom)
            cornerPath.lineTo(selectionRect.right - cornerRadius, selectionRect.bottom)
            cornerPath.quadTo(selectionRect.right, selectionRect.bottom, selectionRect.right, selectionRect.bottom - cornerRadius)
            cornerPath.lineTo(selectionRect.right, selectionRect.bottom - bracketLength)
            canvas.drawPath(cornerPath, cornerHandlePaint)

            // "Blur" label centred on the box
            val labelY = boxView.y + boxView.height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText("Blur", boxView.x + boxView.width / 2f, labelY, labelPaint)
        }
    }
}
