package com.tharunbirla.librecuts.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.models.TextPosition
import com.tharunbirla.librecuts.models.VideoEditingUiState
import com.tharunbirla.librecuts.models.VideoProject
import com.tharunbirla.librecuts.utils.ShapedTextRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

// ExportQuality enum removed in favor of individual parameters

/** "Original" export resolution: keep the source resolution, no final scale. */
const val EXPORT_RESOLUTION_ORIGINAL = 0

class VideoEditingViewModel : ViewModel() {

    private companion object {
        const val TAG = "VideoEditingViewModel"
        const val MAX_UNDO_STACK_SIZE = 50
    }


    private val _project = MutableStateFlow<VideoProject?>(null)
    private val _uiState = MutableStateFlow(VideoEditingUiState())
    private val _undoStack = MutableStateFlow<List<HistoryState>>(emptyList())
    private val _redoStack = MutableStateFlow<List<HistoryState>>(emptyList())
    private val _exportResolution = MutableStateFlow(1080)
    private val _exportFps = MutableStateFlow(30)
    private val _exportAudioOnly = MutableStateFlow(false)
    private val _selectedOperationId = MutableStateFlow<String?>(null)

    private val _hasUnsavedEdits = MutableStateFlow(false)

    val project: StateFlow<VideoProject?> = _project.asStateFlow()
    val uiState: StateFlow<VideoEditingUiState> = _uiState.asStateFlow()
    val selectedOperationId: StateFlow<String?> = _selectedOperationId.asStateFlow()
    val undoStack: StateFlow<List<HistoryState>> = _undoStack.asStateFlow()
    val redoStack: StateFlow<List<HistoryState>> = _redoStack.asStateFlow()
    val exportResolution: StateFlow<Int> = _exportResolution.asStateFlow()
    val exportFps: StateFlow<Int> = _exportFps.asStateFlow()
    val exportAudioOnly: StateFlow<Boolean> = _exportAudioOnly.asStateFlow()
    val hasUnsavedEdits: StateFlow<Boolean> = _hasUnsavedEdits.asStateFlow()

    fun markProjectSaved() {
        _hasUnsavedEdits.value = false
    }

    fun markHasUnsavedEdits() {
        _hasUnsavedEdits.value = true
    }

    fun setExportSettings(resolution: Int, fps: Int, audioOnly: Boolean) {
        _exportResolution.value = resolution
        _exportFps.value = fps
        _exportAudioOnly.value = audioOnly
    }

    val operations: StateFlow<List<EditOperation>>
        get() = MutableStateFlow(project.value?.operations ?: emptyList()).asStateFlow()

    fun initializeProject(sourceUri: Uri, sourceName: String) {
        _project.value = VideoProject(sourceUri = sourceUri, sourceName = sourceName)
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _hasUnsavedEdits.value = false
        updateUiState { it.copy(canUndo = false, canRedo = false) }
    }

    fun loadProject(project: VideoProject) {
        _project.value = project
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _hasUnsavedEdits.value = false
        updateUiState { it.copy(canUndo = false, canRedo = false, pendingOperationCount = project.getOperationCount()) }
    }


    data class HistoryState(
        val project: VideoProject,
        val commandDescription: String
    )

    fun executeCommand(command: com.tharunbirla.librecuts.commands.EditCommand) {
        val current = _project.value ?: return
        
        val historyState = HistoryState(current, command.description)
        val stack = _undoStack.value + historyState
        _undoStack.value = if (stack.size > MAX_UNDO_STACK_SIZE) stack.takeLast(MAX_UNDO_STACK_SIZE) else stack
        
        _redoStack.value = emptyList()
        
        val newProject = command.execute(current)
        _project.value = newProject
        
        updateUiState { state ->
            state.copy(
                pendingOperationCount = newProject.getOperationCount(),
                canUndo = true,
                canRedo = false
            )
        }
        markHasUnsavedEdits()
    }

    fun undo() {
        val current = _project.value ?: return
        val stack = _undoStack.value
        if (stack.isEmpty()) return
        
        val previousState = stack.last()
        _undoStack.value = stack.dropLast(1)
        
        // Push current to redo stack.
        val redoState = HistoryState(current, previousState.commandDescription)
        val rStack = _redoStack.value + redoState
        _redoStack.value = if (rStack.size > MAX_UNDO_STACK_SIZE) rStack.takeLast(MAX_UNDO_STACK_SIZE) else rStack
        
        _project.value = previousState.project
        
        updateUiState { state ->
            state.copy(
                pendingOperationCount = previousState.project.getOperationCount(),
                canUndo = _undoStack.value.isNotEmpty(),
                canRedo = true
            )
        }
        markHasUnsavedEdits()
    }

    fun selectOperation(id: String?) {
        _selectedOperationId.value = id
    }

    fun redo() {
        val current = _project.value ?: return
        val stack = _redoStack.value
        if (stack.isEmpty()) return
        
        val nextState = stack.last()
        _redoStack.value = stack.dropLast(1)
        
        // Push current to undo stack.
        val undoState = HistoryState(current, nextState.commandDescription)
        val uStack = _undoStack.value + undoState
        _undoStack.value = if (uStack.size > MAX_UNDO_STACK_SIZE) uStack.takeLast(MAX_UNDO_STACK_SIZE) else uStack
        
        _project.value = nextState.project
        
        updateUiState { state ->
            state.copy(
                pendingOperationCount = nextState.project.getOperationCount(),
                canUndo = true,
                canRedo = _redoStack.value.isNotEmpty()
            )
        }
        markHasUnsavedEdits()
    }
    fun setPreviewOperationIndex(operationIndex: Int) {
        updateUiState { it.copy(currentPreviewOperationIndex = operationIndex) }
    }

    // ── Private filter-building helpers ─────────────────────────────────────

    /**
     * Orientation-aware final scale for use inside -filter_complex.
     * The chosen preset (720/1080/1440/2160) always applies to the video's
     * LONG edge: landscape -> target height, portrait/square -> target width,
     * so 1080p portrait exports 1080x1920, not 607x1080. `if(gt(iw,ih),...)`
     * reads the actual frame size at that stage, so it composes with Crop /
     * CanvasBackground scaling instead of fighting it. Single quotes survive
     * inside filter_complex (drawtext already relies on that).
     * Returns null for Original (no final scale at all).
     */
    private fun exportResolutionScaleFilter(): String? {
        val res = _exportResolution.value
        if (res <= 0) return null
        return "scale='if(gt(iw,ih),-2,$res)':'if(gt(iw,ih),$res,-2)'"
    }

    /**
     * Same scale, but as a concrete filter for -vf (used when there are no
     * video filters at all). No quoting/expressions are safe there, so the
     * orientation is decided in Kotlin from the known source size — exact,
     * because with no filters the frame IS the source frame.
     */
    private fun exportResolutionScaleFilterForVf(sourceWidth: Int, sourceHeight: Int): String? {
        val res = _exportResolution.value
        if (res <= 0) return null
        return if (sourceHeight >= sourceWidth) "scale=$res:-2" else "scale=-2:$res"
    }

    /** Bitrate matched to the output resolution; for Original, to the source height. */
    private fun exportBitrate(sourceHeight: Int): String {
        val res = _exportResolution.value
        if (res > 0) {
            return when (res) {
                2160 -> "30M"
                1440 -> "16M"
                1080 -> "8M"
                720 -> "5M"
                480 -> "2M"
                else -> "1M"
            }
        }
        return when {
            sourceHeight >= 2160 -> "30M"
            sourceHeight >= 1440 -> "16M"
            sourceHeight >= 1080 -> "8M"
            sourceHeight >= 720 -> "5M"
            sourceHeight >= 480 -> "2M"
            else -> "1M"
        }
    }

    /** Build a crop filter expression for an aspect ratio. */
    private fun buildCropFilterExpr(op: EditOperation.Crop): String? {
        val hasCustomBounds = op.aspectRatio == "Custom" || op.xFraction > 0f || op.yFraction > 0f || op.wFraction < 1f || op.hFraction < 1f
        if (hasCustomBounds) {
            val w = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.wFraction)
            val h = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.hFraction)
            val x = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.xFraction)
            val y = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.yFraction)
            return "crop=w=$w:h=$h:x='min($x\\,iw-($w))':y='min($y\\,ih-($h))',setsar=1"
        }

        return when (op.aspectRatio) {
            "16:9" -> "crop='trunc(min(iw\\,ih*16/9)/2)*2':'trunc(min(ih\\,iw*9/16)/2)*2',setsar=1"
            "9:16" -> "crop='trunc(min(iw\\,ih*9/16)/2)*2':'trunc(min(ih\\,iw*16/9)/2)*2',setsar=1"
            "1:1"  -> "crop='trunc(min(iw\\,ih)/2)*2':'trunc(min(iw\\,ih)/2)*2',setsar=1"
            else   -> null
        }
    }

    /** Build a drawtext filter expression for a text operation. */
    private fun buildDrawtextExpr(op: EditOperation.AddText, fontFilePath: String?): String {
        val escapedText = op.text
            .replace("\\", "\\\\")
            .replace("'", "\\\\'")
            .replace(":", "\\:")

        val fontToUse = op.fontPath ?: fontFilePath
        val fontPart = if (!fontToUse.isNullOrBlank()) {
            val escapedFont = fontToUse
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
            "fontfile='$escapedFont':"
        } else {
            Log.w(TAG, "No fontFilePath — text overlay may not render on Android.")
            ""
        }

        // Use WYSIWYG fractional coordinates if available, else fall back to enum position
        val positionPart = if (op.positionKeyframes.isNotEmpty()) {
            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L)
            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L)
            "x='(w*($xExpr))-(tw/2)':y='(h*($yExpr))-(th/2)'"
        } else if (op.hasCustomPosition()) {
            "x='(w*${op.relativeX})-(tw/2)':y='(h*${op.relativeY})-(th/2)'"
        } else {
            op.position.ffmpegParam
        }

        val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
        
        val alphaPart = if (op.opacityKeyframes.isNotEmpty()) {
            val alphaExpr = buildFFmpegInterpolationExpr(op.opacityKeyframes, useValueY = false, defaultValue = op.opacity, startTimeMs = op.startTimeMs ?: 0L)
            ":alpha='$alphaExpr'"
        } else if (op.opacity < 1.0f) {
            ":alpha='${op.opacity}'"
        } else {
            ""
        }
        val borderPart = if (op.borderThickness > 0) ":borderw=${op.borderThickness}:bordercolor='${formatColorForFFmpeg(op.borderColor)}'" else ""
        val alignPart = if (op.textAlign.isNotEmpty()) ":text_align=${op.textAlign}" else ""
        val lineSpacingPart = if (op.lineSpacing != 0f) ":line_spacing=${op.lineSpacing.toInt()}" else ""
        
        return "drawtext=${fontPart}text='$escapedText':fontcolor='${formatColorForFFmpeg(op.color)}':fontsize=${op.fontSize}:$positionPart$enablePart$alphaPart$borderPart$alignPart$lineSpacingPart"
    }

    // ── Shaped-text (Myanmar) export ──────────────────────────────────────
    // drawtext cannot shape complex scripts (verified: the virama in
    // "မင်္ဂလာပါ" renders as a stray "+" cross). Text containing Myanmar
    // codepoints is therefore pre-rendered to a transparent PNG with
    // Android's shaping stack and composited with `overlay` instead.
    // Everything still goes through the normal operation model, so undo/
    // redo, project save/load and the timeline need no special handling.

    /** One pre-rendered text image registered as an FFmpeg input. */
    data class ShapedTextInput(val inputIndex: Int, val key: String, val width: Int, val height: Int)

    private fun shapedTextKey(op: EditOperation.AddText) = "text:${op.id}"
    private fun shapedTextKey(op: EditOperation.AddSubtitles, cueIndex: Int) = "sub:${op.id}:$cueIndex"

    /**
     * Render PNGs for every Myanmar text/subtitle op in [operations].
     * Returns (key, rendered PNG) pairs — the caller registers each file as
     * an FFmpeg `-loop 1 -i` input and passes the indices to
     * buildVideoFilterStages. Synchronous disk I/O: callers run inside a
     * coroutine (export / preview render jobs).
     */
    private fun collectShapedTextImages(
        operations: List<EditOperation>,
        fontFilePath: String?,
        density: Float,
        sourceVideoHeight: Int,
        context: Context?
    ): List<Pair<String, ShapedTextRenderer.RenderedText>> {
        if (context == null) return emptyList()
        val out = mutableListOf<Pair<String, ShapedTextRenderer.RenderedText>>()
        val effectiveDensity = if (density > 0.5f) density else 2.75f
        val refHeight = if (sourceVideoHeight > 0) sourceVideoHeight.toFloat() else 1080f
        for (op in operations) {
            when (op) {
                is EditOperation.AddText -> {
                    if (!ShapedTextRenderer.needsShapedRendering(op.text)) continue
                    val rendered = ShapedTextRenderer.renderTextPng(
                        context = context,
                        text = op.text,
                        fontPath = op.fontPath,
                        fontSizePx = op.fontSize,
                        colorHex = op.color,
                        borderThicknessPx = op.borderThickness,
                        borderColorHex = op.borderColor,
                        align = op.textAlign,
                        targetLineHeightPx = op.fontSize + op.lineSpacing
                    ) ?: continue
                    out.add(shapedTextKey(op) to rendered)
                }
                is EditOperation.AddSubtitles -> {
                    val scaledFontSize = (op.fontSize * effectiveDensity * (refHeight / 1080f)).toInt().coerceAtLeast(16)
                    val align = when (op.position) {
                        TextPosition.TOP_LEFT, TextPosition.CENTER_LEFT, TextPosition.BOTTOM_LEFT -> "left"
                        TextPosition.TOP_RIGHT, TextPosition.CENTER_RIGHT, TextPosition.BOTTOM_RIGHT -> "right"
                        else -> "center"
                    }
                    for ((cueIdx, cue) in op.cues.withIndex()) {
                        if (!ShapedTextRenderer.needsShapedRendering(cue.text)) continue
                        val lines = getWrappedSubtitleLines(cue.text, op.fontSize, effectiveDensity)
                        val rendered = ShapedTextRenderer.renderTextPng(
                            context = context,
                            text = lines.joinToString("\n"),
                            fontPath = op.fontPath,
                            fontSizePx = scaledFontSize,
                            colorHex = "#FFFFFF",
                            align = align,
                            targetLineHeightPx = scaledFontSize * 1.25f
                        ) ?: continue
                        out.add(shapedTextKey(op, cueIdx) to rendered)
                    }
                }
                else -> {}
            }
        }
        return out
    }

    /**
     * Translate a drawtext position expression (vars w,h,tw,th,text_w,
     * text_h) to the equivalent overlay expression (vars W,H,w,h).
     */
    private fun translateDrawtextPositionToOverlay(ffmpegParam: String): String {
        return ffmpegParam
            .replace("text_w", "\u0001").replace("text_h", "\u0002")
            .replace("tw", "\u0001").replace("th", "\u0002")
            .replace("w", "W").replace("h", "H")
            .replace("\u0001", "w").replace("\u0002", "h")
    }

    /** Overlay x/y for a shaped-text PNG of an AddText op (drawtext-equivalent placement). */
    private fun shapedTextOverlayPosition(op: EditOperation.AddText): String {
        return if (op.positionKeyframes.isNotEmpty()) {
            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L)
            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L)
            "x='(W*($xExpr))-(w/2)':y='(H*($yExpr))-(h/2)'"
        } else if (op.hasCustomPosition()) {
            "x='(W*${op.relativeX})-(w/2)':y='(H*${op.relativeY})-(h/2)'"
        } else {
            translateDrawtextPositionToOverlay(op.position.ffmpegParam)
        }
    }

    private fun buildFFmpegMaskAlphaExpr(
        mc: EditOperation.MaskConfig,
        cx: String,
        cy: String,
        mw: String,
        mh: String,
        cosA: String,
        sinA: String,
        startTimeMs: Long = 0L,
        timeVar: String = "T"
    ): String {
        val dx = "(X - ($cx))"
        val dy = "(Y - ($cy))"
        val rx = "($dx * $cosA + $dy * $sinA)"
        val ry = "(-$dx * $sinA + $dy * $cosA)"

        val featherExpr = if (mc.featherKeyframes.isNotEmpty()) {
            buildFFmpegInterpolationExpr(mc.featherKeyframes, false, mc.feather, startTimeMs, timeVar)
        } else {
            mc.feather.toString()
        }
        val fPx = "(max(0.1\\, ($featherExpr)*0.5))"

        val shapeExpr = when (mc.shape) {
            EditOperation.MaskShape.RECTANGLE ->
                "clip(255 * ($mw/2 + $fPx - abs($rx)) / (2 * $fPx)\\, 0\\, 255) * clip(255 * ($mh/2 + $fPx - abs($ry)) / (2 * $fPx)\\, 0\\, 255) / 255"
            EditOperation.MaskShape.ELLIPSE ->
                "clip(255 * (1 + ($fPx/($mw/2)) - sqrt(pow($rx/($mw/2)\\, 2) + pow($ry/($mh/2)\\, 2))) / (2 * ($fPx/($mw/2)))\\, 0\\, 255)"
            EditOperation.MaskShape.SPLIT ->
                "clip(255 * ($ry + $fPx) / (2 * $fPx)\\, 0\\, 255)"
            EditOperation.MaskShape.SHUTTER ->
                "clip(255 * ($mh/2 + $fPx - abs($ry)) / (2 * $fPx)\\, 0\\, 255)"
            EditOperation.MaskShape.HEART ->
                "clip(255 * (1 + ($fPx/($mw/2)) - sqrt(pow($rx/($mw/2)\\, 2) + pow(($ry + abs($rx)*0.5)/($mh/2)\\, 2))) / (2 * ($fPx/($mw/2)))\\, 0\\, 255)"
            EditOperation.MaskShape.STAR ->
                "clip(255 * (1 + ($fPx/($mw/2)) - (pow(abs($rx)/($mw/2)\\, 0.7) + pow(abs($ry)/($mh/2)\\, 0.7))) / (2 * ($fPx/($mw/2)))\\, 0\\, 255)"
            else -> "255"
        }

        return if (mc.isInverted) "(255 - ($shapeExpr))" else "($shapeExpr)"
    }

    private fun buildFFmpegInterpolationExpr(
        keyframes: List<EditOperation.KeyframePoint>,
        useValueY: Boolean,
        defaultValue: Float,
        startTimeMs: Long,
        timeVar: String = "t"
    ): String {
        if (keyframes.isEmpty()) return defaultValue.toString()
        val sorted = keyframes.sortedBy { it.timeMs }
        val startSec = startTimeMs / 1000.0
        val tRel = "($timeVar-$startSec)"
        
        if (sorted.size == 1) {
            val v = if (useValueY) sorted[0].valueY else sorted[0].valueX
            return v.toString()
        }
        
        var expr = ""
        val lastVal = if (useValueY) sorted.last().valueY else sorted.last().valueX
        expr = lastVal.toString()
        
        for (i in sorted.size - 2 downTo 0) {
            val k1 = sorted[i]
            val k2 = sorted[i + 1]
            val t1 = k1.timeMs / 1000.0
            val t2 = k2.timeMs / 1000.0
            val v1 = if (useValueY) k1.valueY else k1.valueX
            val v2 = if (useValueY) k2.valueY else k2.valueX
            
            val diffVal = v2 - v1
            val diffTime = t2 - t1
            val segmentExpr = if (diffTime > 0) {
                "$v1 + ($diffVal) * ($tRel - $t1) / ($diffTime)"
            } else {
                v1.toString()
            }
            
            expr = "if(lt($tRel\\, $t2)\\, $segmentExpr\\, $expr)"
        }
        
        val firstVal = if (useValueY) sorted.first().valueY else sorted.first().valueX
        val firstTime = sorted.first().timeMs / 1000.0
        expr = "if(lt($tRel\\, $firstTime)\\, $firstVal\\, $expr)"
        
        return expr
    }

    private fun formatColorForFFmpeg(colorHex: String): String {
        if (!colorHex.startsWith("#")) return colorHex
        return when (colorHex.length) {
            9 -> { // #AARRGGBB -> 0xRRGGBBAA
                val aa = colorHex.substring(1, 3)
                val rrggbb = colorHex.substring(3)
                "0x$rrggbb$aa"
            }
            7 -> { // #RRGGBB -> 0xRRGGBB
                "0x${colorHex.substring(1)}"
            }
            else -> colorHex
        }
    }

    private fun buildEnableExpr(startTimeMs: Long?, endTimeMs: Long?): String {
        if (startTimeMs == null && endTimeMs == null) return ""
        val startSec = maxOf(0L, startTimeMs ?: 0L) / 1000.0
        return if (endTimeMs != null) {
            val endSec = maxOf(0L, endTimeMs) / 1000.0
            ":enable='between(t\\,$startSec\\,$endSec)'"
        } else {
            ":enable='gte(t\\,$startSec)'"
        }
    }

    private fun getFFmpegFilterForAdjust(op: EditOperation.Adjust): String? {
        val filters = mutableListOf<String>()

        // 1. eq filter (brightness, contrast, saturation)
        if (op.brightness != 0 || op.contrast != 0 || op.saturation != 0) {
            val b = op.brightness / 100.0
            val c = 1.0 + (op.contrast / 100.0)
            val s = 1.0 + (op.saturation / 100.0)
            filters.add("eq=brightness=$b:contrast=$c:saturation=$s")
        }

        // 2. exposure
        if (op.exposure != 0) {
            val ev = (op.exposure / 100.0) * 3.0
            filters.add("exposure=exposure=$ev")
        }

        // 3. warmth (colorbalance)
        if (op.warmth != 0) {
            val w = (op.warmth / 100.0) * 0.3
            filters.add("colorbalance=rs=$w:rm=$w:rh=$w:bs=${-w}:bm=${-w}:bh=${-w}")
        }

        // 4. shadows & highlights (emulated via standard FFmpeg 'curves' filter)
        if (op.shadow != 0 || op.highlights != 0) {
            val yShadow = (0.25 + (op.shadow / 100.0) * 0.15).coerceIn(0.01, 0.99)
            val yHighlight = (0.75 + (op.highlights / 100.0) * 0.15).coerceIn(0.01, 0.99)
            val sStr = String.format(java.util.Locale.US, "%.3f", yShadow)
            val hStr = String.format(java.util.Locale.US, "%.3f", yHighlight)
            filters.add("curves=m='0/0 0.25/$sStr 0.75/$hStr 1/1'")
        }

        // 5. sharpen (unsharp)
        if (op.sharpen != 0) {
            val amt = (op.sharpen / 100.0) * 1.5
            filters.add("unsharp=luma_amount=$amt")
        }

        // 6. vignette
        if (op.vignette != 0) {
            val angle = 1.5 - (op.vignette / 100.0) * 0.9
            filters.add("vignette=angle=$angle")
        }

        return if (filters.isNotEmpty()) filters.joinToString(",") else null
    }

    private fun getFFmpegFilterForName(name: String): String? {
        return when (name.lowercase()) {
            "vintage" -> "curves=preset=vintage"
            "warm" -> "colorchannelmixer=1.1:0:0:0:0:1.0:0:0:0:0:0.9"
            "cool" -> "colorchannelmixer=0.9:0:0:0:0:1.0:0:0:0:0:1.1"
            "contrast" -> "curves=preset=strong_contrast"
            "monochrome" -> "hue=s=0"
            "vignette" -> "vignette"
            "negative" -> "curves=preset=negative"
            "crossprocess" -> "curves=preset=cross_process"
            else -> null
        }
    }

    private fun isImagePath(path: String): Boolean =
        path.endsWith(".png", ignoreCase = true) ||
        path.endsWith(".jpg", ignoreCase = true) ||
        path.endsWith(".jpeg", ignoreCase = true) ||
        path.endsWith(".webp", ignoreCase = true)

    /**
     * The source video's frame rate, for deciding whether the byte-copy fast
     * path is valid. METADATA_KEY_CAPTURE_FRAMERATE is missing for many
     * sources (downloaded videos, screen recordings, Telegram videos), so
     * fall back to frame count / duration. Returns 0 when it cannot be
     * determined — callers must treat unknown as "do not copy" and use the
     * encode pipeline instead, never assume a rate.
     */
    private fun probeSourceFrameRate(sourceFilePath: String): Float {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceFilePath)
                val capture = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull() ?: 0f
                if (capture > 0f) {
                    capture
                } else {
                    val frames = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toLongOrNull() ?: 0L
                    val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    if (frames > 0 && durationMs > 0) frames * 1000f / durationMs else 0f
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine source frame rate: ${e.message}")
            0f
        }
    }

    /**
     * Whether the no-edit byte-copy fast path is valid for this source: a
     * real video (not a still image) whose frame rate already matches the
     * export FPS, so the copy honours the export settings. An unknown
     * frame rate returns false — the encode pipeline is the safe fallback.
     * Used by the Activity's saveAction gate AND by the -c copy return
     * inside buildConsolidatedFFmpegCommand.
     */
    fun canByteCopyExport(sourceFilePath: String): Boolean {
        if (isImagePath(sourceFilePath)) return false
        val fps = probeSourceFrameRate(sourceFilePath)
        return fps > 0f && kotlin.math.abs(fps - _exportFps.value.toFloat()) < 0.5f
    }

    /**
     * Whether the GPU fast path (Media3 Transformer) can export this
     * project — Phase 1 scope: a single video clip whose only operations
     * are plain ones the GPU pipeline supports (Trim, SpeedMain without a
     * reverse proxy, Crop). Everything else (text, blur boxes, subtitles,
     * audio tracks, multi-clip merges, audio-only, images) stays on the
     * FFmpeg path.
     *
     * Phase 2: a centred CanvasRatio op is also allowed — with a blur
     * background (CanvasBackground BLUR) or a black one (no bg op, or a
     * black colour). Colour / image backgrounds, a dragged
     * (off-centre) canvas, and the remaining ops still fall back to
     * FFmpeg. "Original" ratio is a no-op for the canvas and is
     * ignored. A CanvasRatio and a Crop op together also fall back
     * (the editor keeps them mutually exclusive, so this is defensive).
     *
     * One more gate: Transformer's setFrameRate is a MAX — it can only
     * drop frames, never invent them. If the chosen fps is above the
     * effective source fps (source fps × speed), the GPU output would
     * silently keep the source fps — FFmpeg duplicates frames instead, so
     * route there. An unknown source fps (0) is allowed through: the
     * mismatch only affects the output fps tag, not the picture, and
     * forcing screen recordings (which often report no fps) onto the slow
     * CPU path would defeat the point of the GPU fast path.
     */
    fun supportsGpuExport(sourceFilePath: String): Boolean {
        val project = _project.value ?: return false
        if (_exportAudioOnly.value) return false
        if (isImagePath(sourceFilePath)) return false
        val ops = project.operations
        if (ops.any { it is EditOperation.Merge }) return false
        var speed = 1f
        var canvasRatio: EditOperation.CanvasRatio? = null
        var canvasBg: EditOperation.CanvasBackground? = null
        for (op in ops) {
            when (op) {
                is EditOperation.Trim -> { }
                is EditOperation.SpeedMain -> {
                    if (op.proxyUri != null) return false
                    speed = op.speed
                }
                is EditOperation.Crop -> { }
                is EditOperation.CanvasRatio -> {
                    // "Original" changes nothing — not a canvas op.
                    if (op.ratioValue == null) continue
                    // The GPU overlay is centred; a dragged canvas
                    // (pinch zoom is fine — it is centred too) falls back.
                    if (op.effectiveOffsetX != 0f || op.effectiveOffsetY != 0f) return false
                    canvasRatio = op
                }
                is EditOperation.CanvasBackground -> { canvasBg = op }
                else -> return false
            }
        }
        if (canvasRatio != null && ops.any { it is EditOperation.Crop }) return false
        if (canvasRatio != null && canvasBg != null) {
            // Presentation only does a black background; a colour or
            // image background needs the FFmpeg canvas.
            val bg = canvasBg
            val ok = bg.type == EditOperation.CanvasBackground.BackgroundType.BLUR ||
                (bg.type == EditOperation.CanvasBackground.BackgroundType.COLOR &&
                    bg.colorHex.equals("#000000", ignoreCase = true))
            if (!ok) return false
        }
        val sourceFps = probeSourceFrameRate(sourceFilePath)
        if (sourceFps > 0f) {
            val effectiveFps = sourceFps * speed
            if (_exportFps.value.toFloat() > effectiveFps + 0.5f) return false
        }
        return true
    }

    /**
     * GPU export parameters for the router, or null when
     * [supportsGpuExport] is false. The Activity fills in [outputPath]
     * (the service temp file) before starting ExportService. The last
     * Trim / SpeedMain / Crop op wins, matching how the editor commits
     * them.
     */
    fun buildGpuExportParams(
        sourceFilePath: String,
        outputPath: String
    ): com.tharunbirla.librecuts.services.GpuExportParams? {
        if (!supportsGpuExport(sourceFilePath)) return null
        val ops = _project.value?.operations ?: return null
        val trim = ops.filterIsInstance<EditOperation.Trim>().lastOrNull()
        val speed = ops.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()?.speed ?: 1f
        val crop = ops.filterIsInstance<EditOperation.Crop>().lastOrNull()
        val ratio = ops.filterIsInstance<EditOperation.CanvasRatio>().lastOrNull()
            ?.takeIf { it.ratioValue != null }
        val bg = ops.filterIsInstance<EditOperation.CanvasBackground>().lastOrNull()
        return com.tharunbirla.librecuts.services.GpuExportParams(
            sourcePath = sourceFilePath,
            outputPath = outputPath,
            trimStartMs = trim?.startMs ?: 0L,
            trimEndMs = trim?.endMs ?: 0L,
            speed = speed,
            targetRes = _exportResolution.value,
            targetFps = _exportFps.value,
            crop = crop?.let {
                com.tharunbirla.librecuts.services.GpuCropSpec(
                    aspectRatio = it.aspectRatio,
                    xFraction = it.xFraction,
                    yFraction = it.yFraction,
                    wFraction = it.wFraction,
                    hFraction = it.hFraction
                )
            },
            canvas = ratio?.let {
                com.tharunbirla.librecuts.services.GpuCanvasSpec(
                    aspectRatio = it.ratioValue!!,
                    blurRadius = if (bg?.type == EditOperation.CanvasBackground.BackgroundType.BLUR) bg.blurRadius else 0,
                    scale = it.effectiveScale
                )
            }
        )
    }

    /**
     * Build the video portion of a filter_complex graph from non-merge, non-audio operations.
     *
     * @return Pair of (list of filter stages, final stream label) — e.g. (["[0:v]crop=...[v0]", "[v0]drawtext=...[v1]"], "[v1]")
     *         If no video filters needed, returns (emptyList, "[0:v]")
     */
    /**
     * CapCut-style canvas composition for a [EditOperation.CanvasRatio] op.
     *
     * The input frame is fitted (then zoomed by [EditOperation.CanvasRatio.scale]
     * and shifted by its offsets) onto a canvas of the target aspect whose
     * long edge equals the input's long edge, so a later long-edge resolution
     * scale still works. Empty canvas area is filled by the CanvasBackground
     * op (blur / image / colour) or black. Canvas sizes are concrete numbers
     * computed from [sourceWidth]/[sourceHeight] (see the note in the body).
     *
     * Runs FIRST inside [buildVideoFilterStages] so text / blur-box /
     * subtitle overlays are drawn on the canvas (their coordinates are
     * canvas-relative), and so a BLUR background is built from the raw video
     * rather than from the composited overlays.
     */
    /**
     * Blur-background filter fragment for a canvas of [w]×[h].
     *
     * The blur runs at QUARTER size and is then upscaled: a blur is a
     * blur, and the small pass is ~4x cheaper than blurring the full
     * canvas (verified on host FFmpeg: PSNR 48 dB vs the full-res
     * boxblur, visually identical). Without this, a 20-minute export
     * with a blurred canvas spends most of its time in boxblur and the
     * progress dialog sits at 0% for minutes — it looks stuck.
     * Radius is scaled down with the size (blur/4, min 2).
     */
    private fun blurBgFilter(w: Int, h: Int, blur: Int): String {
        val sw = (w / 4 / 2 * 2).coerceAtLeast(2)
        val sh = (h / 4 / 2 * 2).coerceAtLeast(2)
        val r = (blur / 4).coerceIn(2, 25)
        // Scale + crop at QUARTER size, blur there, then upscale: the
        // full-res scale+crop before the blur was pure waste (host FFmpeg:
        // 32% faster filter time than the quarter-size blur with a full-res
        // scale+crop first, PSNR 44.8 dB vs the full-res blur — a blurred
        // background is a blurred background).
        // The final upscale is bilinear: the background is already
        // blurred, so a sharper scaler buys nothing there (host FFmpeg:
        // ~10% faster filter time; the foreground keeps bicubic).
        return "format=yuv420p,scale=$sw:$sh:force_original_aspect_ratio=increase,crop=$sw:$sh,boxblur=$r,scale=$w:$h:flags=bilinear"
    }

    /**
     * Threads for the filter_complex graph. The bundled FFmpeg is 8.0,
     * and -filter_complex_threads exists since FFmpeg 5.0, so it is
     * supported here. On a multi-core phone it lets frames pipeline
     * through the graph instead of waiting on each other (host FFmpeg
     * shows no gain on this 1.5-CPU VM, so the phone gain is unverified
     * — the render engine strips the flag and retries if a device's
     * FFmpeg build ever rejects it).
     */
    private fun filterComplexThreadsArg(): String {
        // The filter graph is CPU-bound, so use the device's cores: the
        // old cap of 4 left cores idle on 8-core phones. Hardware decode
        // can't help here (the graph needs CPU frames), so thread count
        // is the cheapest speed lever we have. Still bounded at 8 —
        // beyond that, context switching eats the gain on a phone.
        val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        return " -filter_complex_threads $n"
    }

    private fun buildCanvasRatioStages(
        inputLabel: String,
        ratioOp: EditOperation.CanvasRatio,
        bgOp: EditOperation.CanvasBackground?,
        bgImageIndex: Int,
        stageIndex: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        canvasLongEdgeCap: Int = 0
    ): Pair<List<String>, String> {
        val ratio = ratioOp.ratioValue ?: return emptyList<String>() to inputLabel
        if (sourceWidth <= 0 || sourceHeight <= 0) return emptyList<String>() to inputLabel
        // Canvas sizes are CONCRETE pixel numbers computed in Kotlin from
        // the source frame size: runtime FFmpeg expressions would be
        // re-evaluated against the SCALED frame after the first scale
        // (iw/ih change), which made the blur-background crop compute
        // 3412x3412 from a 3412x1920 frame and fail (verified on host
        // FFmpeg). Concrete sizes are also what the existing
        // canvas-background code does.
        //
        // The long edge is capped at the chosen export resolution: a 4K
        // source exported at 1080p used to build a 2160x3840 canvas and
        // blur it, only for the final scale to throw the extra pixels
        // away. The output size is identical (the final scale is an
        // identity then) — only the wasted work is gone.
        val sourceLongEdge = maxOf(sourceWidth, sourceHeight)
        val longEdge = if (canvasLongEdgeCap > 0) minOf(sourceLongEdge, canvasLongEdgeCap) else sourceLongEdge
        var canvasW: Int
        var canvasH: Int
        if (ratio >= 1f) {
            canvasW = longEdge
            canvasH = Math.round(longEdge / ratio)
        } else {
            canvasH = longEdge
            canvasW = Math.round(longEdge * ratio)
        }
        if (canvasW % 2 != 0) canvasW -= 1
        if (canvasH % 2 != 0) canvasH -= 1
        if (canvasW <= 0 || canvasH <= 0) return emptyList<String>() to inputLabel

        val scale = ratioOp.effectiveScale
        val ox = String.format(java.util.Locale.US, "%.4f", ratioOp.effectiveOffsetX)
        val oy = String.format(java.util.Locale.US, "%.4f", ratioOp.effectiveOffsetY)
        val overlayX = "(W-w)/2+$ox*W"
        val overlayY = "(H-h)/2+$oy*H"

        val stages = mutableListOf<String>()
        val bgLabel = "[ratio_bg_$stageIndex]"
        val fgLabel = "[ratio_fg_$stageIndex]"
        val outLabel = "[v$stageIndex]"

        // Foreground: fit into the canvas, then pinch-zoom.
        val fgScale = "scale=$canvasW:$canvasH:force_original_aspect_ratio=decrease" +
            if (scale > 1f) {
                ",scale='trunc(iw*${String.format(java.util.Locale.US, "%.3f", scale)}/2)*2':'trunc(ih*${String.format(java.util.Locale.US, "%.3f", scale)}/2)*2'"
            } else ""

        when {
            bgOp?.type == EditOperation.CanvasBackground.BackgroundType.BLUR -> {
                val blur = bgOp.blurRadius
                stages.add("${inputLabel}split=2[ratio_bg_src_$stageIndex][ratio_fg_src_$stageIndex]")
                stages.add("[ratio_bg_src_$stageIndex]${blurBgFilter(canvasW, canvasH, blur)}$bgLabel")
                stages.add("[ratio_fg_src_$stageIndex]$fgScale$fgLabel")
            }
            bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1 -> {
                stages.add("[$bgImageIndex:v]scale=$canvasW:$canvasH:force_original_aspect_ratio=increase,crop=$canvasW:$canvasH$bgLabel")
                stages.add("${inputLabel}$fgScale$fgLabel")
            }
            else -> {
                val color = bgOp?.colorHex?.takeIf { it.startsWith("#") } ?: "black"
                stages.add("color=c=$color:s=${canvasW}x${canvasH}:r=30$bgLabel")
                stages.add("${inputLabel}$fgScale$fgLabel")
            }
        }
        // shortest=1: the colour source (and a looped image) are endless;
        // without it the export would run forever.
        stages.add("$bgLabel$fgLabel" + "overlay=$overlayX:$overlayY:shortest=1,setsar=1,format=yuv420p$outLabel")
        return stages to outLabel
    }

    private fun buildVideoFilterStages(
        operations: List<EditOperation>,
        fontFilePath: String?,
        inputLabel: String = "[0:v]",
        imageInputIndices: List<Pair<Int, EditOperation.AddImageOverlay>> = emptyList(),
        density: Float = 1.0f,
        sourceVideoHeight: Int = 1080,
        shapedTextInputs: List<ShapedTextInput> = emptyList(),
        canvasRatioOp: EditOperation.CanvasRatio? = null,
        bgOp: EditOperation.CanvasBackground? = null,
        bgImageIndex: Int = -1,
        ratioSourceWidth: Int = 0,
        ratioSourceHeight: Int = 0,
        canvasLongEdgeCap: Int = 0
    ): Pair<List<String>, String> {
        val stages = mutableListOf<String>()
        var currentLabel = inputLabel
        var stageIndex = 0

        // Canvas ratio first (see buildCanvasRatioStages): overlays are
        // drawn on the canvas, and a BLUR background comes from raw video.
        if (canvasRatioOp != null && canvasRatioOp.ratioValue != null) {
            val (ratioStages, ratioLabel) = buildCanvasRatioStages(
                currentLabel, canvasRatioOp, bgOp, bgImageIndex, stageIndex,
                sourceWidth = ratioSourceWidth, sourceHeight = ratioSourceHeight,
                canvasLongEdgeCap = canvasLongEdgeCap
            )
            stages.addAll(ratioStages)
            currentLabel = ratioLabel
            stageIndex++
        }

        // Process overlays first so their relative coordinates align with the uncropped original video, matching the UI.
        val overlayOps = operations.filterNot { it is EditOperation.Crop }
        val cropOps = operations.filterIsInstance<EditOperation.Crop>()

        for (op in overlayOps) {
            when (op) {
                is EditOperation.AddText -> {
                    // Myanmar text: drawtext cannot shape it (virama renders
                    // as a stray "+"), so composite the pre-rendered PNG.
                    // Any other text keeps the drawtext path.
                    val shaped = if (ShapedTextRenderer.needsShapedRendering(op.text)) {
                        shapedTextInputs.find { it.key == shapedTextKey(op) }
                    } else null
                    if (shaped != null) {
                        val txtLabel = "[shaped_txt_$stageIndex]"
                        val nextLabel = "[v$stageIndex]"
                        stages.add("[${shaped.inputIndex}:v]format=rgba$txtLabel")
                        var overlaySrc = txtLabel
                        if (op.opacityKeyframes.isNotEmpty()) {
                            val opacityLabel = "[shaped_op_$stageIndex]"
                            val alphaExpr = buildFFmpegInterpolationExpr(op.opacityKeyframes, useValueY = false, defaultValue = op.opacity, startTimeMs = op.startTimeMs ?: 0L, timeVar = "T")
                            stages.add("${txtLabel}format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*($alphaExpr)'$opacityLabel")
                            overlaySrc = opacityLabel
                        } else if (op.opacity < 1.0f) {
                            val opacityLabel = "[shaped_op_$stageIndex]"
                            stages.add("${txtLabel}format=rgba,colorchannelmixer=aa=${op.opacity}$opacityLabel")
                            overlaySrc = opacityLabel
                        }
                        val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
                        stages.add("$currentLabel$overlaySrc" + "overlay=${shapedTextOverlayPosition(op)}$enablePart$nextLabel")
                        currentLabel = nextLabel
                        stageIndex++
                    } else {
                        if (ShapedTextRenderer.needsShapedRendering(op.text)) {
                            Log.w(TAG, "Shaped PNG missing for text op ${op.id} — falling back to drawtext (Myanmar may render broken)")
                        }
                        val filterExpr = buildDrawtextExpr(op, fontFilePath)
                        val nextLabel = "[v$stageIndex]"
                        stages.add("$currentLabel$filterExpr$nextLabel")
                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                is EditOperation.AddImageOverlay -> {
                    val imageInputIndex = imageInputIndices.find { it.second.id == op.id }?.first
                    if (imageInputIndex != null) {
                        val radians = op.rotationAngle * Math.PI / 180.0
                        val scaledImgLabel = "[scaled_img_$stageIndex]"
                        val refVidLabel = "[ref_vid_$stageIndex]"
                        val rotatedImgLabel = "[rotated_img_$stageIndex]"
                        val nextLabel = "[v$stageIndex]"

                        val path = op.imageUri.path
                        val isGif = path != null && path.endsWith(".gif", ignoreCase = true)
                        val isVideo = path != null && (path.endsWith(".mp4", ignoreCase = true) ||
                                                       path.endsWith(".mkv", ignoreCase = true) ||
                                                       path.endsWith(".mov", ignoreCase = true) ||
                                                       path.endsWith(".3gp", ignoreCase = true))

                        if (isVideo) {
                            val startSec = (op.startTimeMs ?: 0L) / 1000.0
                            val ptsLabel = "[pts_$stageIndex]"
                            val mirrorStr = if (op.isMirrored) ",hflip" else ""
                            val speedExpr = if (op.speedKeyframes.isNotEmpty()) {
                                buildFFmpegInterpolationExpr(op.speedKeyframes, useValueY = false, defaultValue = 1.0f, startTimeMs = 0L, timeVar = "T")
                            } else {
                                "1.0"
                            }
                            val safeSpeedExpr = "if(gt($speedExpr,0.1),$speedExpr,0.1)"
                            stages.add("[$imageInputIndex:v]setpts='(PTS-STARTPTS)/($safeSpeedExpr)+${startSec}/TB',format=rgba$mirrorStr$ptsLabel")
                            stages.add("${ptsLabel}${currentLabel}scale2ref=w=iw*${op.relativeWidth}:h=ih*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        } else {
                            val rgbaImgLabel = "[rgba_img_$stageIndex]"
                            val mirrorStr = if (op.isMirrored) ",hflip" else ""
                            val hasMaskKeyframes = op.maskConfig.positionKeyframes.isNotEmpty() || op.maskConfig.sizeKeyframes.isNotEmpty() || op.maskConfig.rotationKeyframes.isNotEmpty() || op.maskConfig.featherKeyframes.isNotEmpty()
                            if (op.opacityKeyframes.isNotEmpty() || hasMaskKeyframes) {
                                // Static image: convert to video stream so geq's T variable advances
                                stages.add("[$imageInputIndex:v]format=rgba$mirrorStr,loop=loop=-1:size=1:start=0,fps=25$rgbaImgLabel")
                            } else {
                                stages.add("[$imageInputIndex:v]format=rgba$mirrorStr$rgbaImgLabel")
                            }
                            stages.add("${rgbaImgLabel}${currentLabel}scale2ref=w=iw*${op.relativeWidth}:h=ih*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        }

                        var currentOverlayLabel = scaledImgLabel
                        if (op.chromaKeyColor != null) {
                            val colorkeyLabel = "[colorkey_$stageIndex]"
                            val color = formatColorForFFmpeg(op.chromaKeyColor)
                            // colorkey=color=0xRRGGBB:similarity=0.1:blend=0.1
                            stages.add("${currentOverlayLabel}colorkey=${color}:${op.chromaKeySimilarity}:0.1${colorkeyLabel}")
                            currentOverlayLabel = colorkeyLabel
                        }

                        val overlayStartMs = op.startTimeMs ?: 0L
                        if (op.opacityKeyframes.isNotEmpty()) {
                            val opacityLabel = "[opacity_$stageIndex]"
                            val alphaExpr = buildFFmpegInterpolationExpr(op.opacityKeyframes, useValueY = false, defaultValue = op.opacity, startTimeMs = overlayStartMs, timeVar = "T")
                            stages.add("${currentOverlayLabel}format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*($alphaExpr)'${opacityLabel}")
                            currentOverlayLabel = opacityLabel
                        } else if (op.opacity < 1.0f) {
                            val opacityLabel = "[opacity_$stageIndex]"
                            stages.add("${currentOverlayLabel}format=rgba,colorchannelmixer=aa=${op.opacity}${opacityLabel}")
                            currentOverlayLabel = opacityLabel
                        }

                        if (op.maskConfig.shape != EditOperation.MaskShape.NONE) {
                            val maskLabel = "[mask_$stageIndex]"
                            val mc = op.maskConfig
                            val cx = if (mc.positionKeyframes.isNotEmpty()) "(W*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, false, mc.relativeX, overlayStartMs, "T") + "))" else "W*${mc.relativeX}"
                            val cy = if (mc.positionKeyframes.isNotEmpty()) "(H*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, true, mc.relativeY, overlayStartMs, "T") + "))" else "H*${mc.relativeY}"
                            val sizeExpr = if (mc.sizeKeyframes.isNotEmpty()) buildFFmpegInterpolationExpr(mc.sizeKeyframes, false, ((mc.relativeWidth + mc.relativeHeight) / 2f * 200f), overlayStartMs, "T") else null
                            val mw = if (sizeExpr != null) "(W*(($sizeExpr)/200))" else "W*${mc.relativeWidth}"
                            val mh = if (sizeExpr != null) "(H*(($sizeExpr)/200))" else "H*${mc.relativeHeight}"
                            val cosA: String
                            val sinA: String
                            if (mc.rotationKeyframes.isNotEmpty()) {
                                val rotExpr = buildFFmpegInterpolationExpr(mc.rotationKeyframes, false, mc.rotationAngle, overlayStartMs, "T")
                                val rad = "(($rotExpr)*PI/180)"
                                cosA = "cos($rad)"
                                sinA = "sin($rad)"
                            } else {
                                val rad = Math.toRadians(mc.rotationAngle.toDouble())
                                cosA = Math.cos(rad).toString()
                                sinA = Math.sin(rad).toString()
                            }
                            
                            val finalAlphaExpr = buildFFmpegMaskAlphaExpr(mc, cx, cy, mw, mh, cosA, sinA, overlayStartMs, "T")
                            
                            stages.add("${currentOverlayLabel}format=rgba,geq=r='r(X,Y)*($finalAlphaExpr/255)':g='g(X,Y)*($finalAlphaExpr/255)':b='b(X,Y)*($finalAlphaExpr/255)':a='alpha(X,Y)*($finalAlphaExpr/255)'${maskLabel}")
                            currentOverlayLabel = maskLabel
                        }

                        // Rotate image/video overlay (c=none preserves transparent background, ow/oh prevent cropping)
                        stages.add("${currentOverlayLabel}rotate=$radians:c=none:ow='rotw($radians)':oh='roth($radians)'${rotatedImgLabel}")
                        // Overlay image/video on the reference video
                        val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
                        val hasMaskKfs = op.maskConfig.positionKeyframes.isNotEmpty() || op.maskConfig.sizeKeyframes.isNotEmpty() || op.maskConfig.rotationKeyframes.isNotEmpty() || op.maskConfig.featherKeyframes.isNotEmpty()
                        val shortestPart = if (((isGif || isVideo) && op.isLooping) || (!isGif && !isVideo && (op.opacityKeyframes.isNotEmpty() || hasMaskKfs))) ":shortest=1" else ""
                        
                        val overlayX = if (op.positionKeyframes.isNotEmpty()) {
                            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX, startTimeMs = op.startTimeMs ?: 0L)
                            "x='(W*($xExpr))-(w/2)'"
                        } else {
                            "x='(W*${op.relativeX})-(w/2)'"
                        }
                        val overlayY = if (op.positionKeyframes.isNotEmpty()) {
                            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY, startTimeMs = op.startTimeMs ?: 0L)
                            "y='(H*($yExpr))-(h/2)'"
                        } else {
                            "y='(H*${op.relativeY})-(h/2)'"
                        }
                        
                        stages.add("${refVidLabel}${rotatedImgLabel}overlay=$overlayX:$overlayY${shortestPart}${enablePart}${nextLabel}")

                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                is EditOperation.AddBlurBox -> {
                    // Blur a rectangular region: split the frame, crop + boxblur
                    // the region from one copy, overlay it back onto the other.
                    // Geometry is even-pixel (yuv420p-safe) and clamped so the
                    // box never runs past the frame edge (crop fails otherwise).
                    val srcLabel = "[blur_src_$stageIndex]"
                    val mainLabel = "[blur_main_$stageIndex]"
                    val blurredLabel = "[blurred_$stageIndex]"
                    val nextLabel = "[v$stageIndex]"
                    val bw = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.relativeWidth)
                    val bh = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.relativeHeight)
                    val bx = String.format(java.util.Locale.US, "trunc((iw*%.4f-trunc(iw*%.4f/2))/2)*2", op.relativeX, op.relativeWidth)
                    val by = String.format(java.util.Locale.US, "trunc((ih*%.4f-trunc(ih*%.4f/2))/2)*2", op.relativeY, op.relativeHeight)
                    val ox = String.format(java.util.Locale.US, "trunc((W*%.4f-trunc(W*%.4f/2))/2)*2", op.relativeX, op.relativeWidth)
                    val oy = String.format(java.util.Locale.US, "trunc((H*%.4f-trunc(H*%.4f/2))/2)*2", op.relativeY, op.relativeHeight)
                    val ow = String.format(java.util.Locale.US, "trunc(W*%.4f/2)*2", op.relativeWidth)
                    val oh = String.format(java.util.Locale.US, "trunc(H*%.4f/2)*2", op.relativeHeight)
                    val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
                    stages.add("${currentLabel}split=2$srcLabel$mainLabel")
                    stages.add("${srcLabel}crop=w=$bw:h=$bh:x='min($bx\\,iw-($bw))':y='min($by\\,ih-($bh))',boxblur=${op.blurRadius}$blurredLabel")
                    stages.add("${mainLabel}${blurredLabel}overlay=x='min($ox\\,W-($ow))':y='min($oy\\,H-($oh))'$enablePart$nextLabel")
                    currentLabel = nextLabel
                    stageIndex++
                }
                is EditOperation.AddSubtitles -> {
                    val effectiveDensity = if (density > 0.5f) density else 2.75f
                    val refHeight = if (sourceVideoHeight > 0) sourceVideoHeight.toFloat() else 1080f
                    val scaledFontSize = (op.fontSize * effectiveDensity * (refHeight / 1080f)).toInt().coerceAtLeast(16)
                    val boxBorderW = (8f * effectiveDensity * (refHeight / 1080f)).toInt().coerceAtLeast(4)
                    val marginY = (24f * effectiveDensity * (refHeight / 1080f)).toInt()
                    val lineSpacing = (scaledFontSize * 1.25f).toInt()
                    
                    for ((cueIdx, cue) in op.cues.withIndex()) {
                        val lines = getWrappedSubtitleLines(cue.text, op.fontSize, effectiveDensity)
                        val totalBlockHeight = lines.size * lineSpacing
                        val enablePart = buildEnableExpr(cue.startTimeMs, cue.endTimeMs)
                        // Myanmar cue: composite the pre-rendered PNG instead
                        // of drawtext (drawtext cannot shape it). The blur
                        // band and the PNG block both use the PNG's real
                        // height so band and text stay aligned exactly.
                        val shapedCue = if (ShapedTextRenderer.needsShapedRendering(cue.text)) {
                            shapedTextInputs.find { it.key == shapedTextKey(op, cueIdx) }
                        } else null
                        if (shapedCue == null && ShapedTextRenderer.needsShapedRendering(cue.text)) {
                            Log.w(TAG, "Shaped PNG missing for subtitle cue $cueIdx — falling back to drawtext (Myanmar may render broken)")
                        }
                        val blockHeightForBand = shapedCue?.height ?: totalBlockHeight

                        val fontToUse = op.fontPath ?: fontFilePath
                        val fontPart = if (!fontToUse.isNullOrBlank()) {
                            val escapedFont = fontToUse
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace(":", "\\:")
                            "fontfile='$escapedFont':"
                        } else {
                            ""
                        }

                        // Blurred band behind the whole cue block (instead of
                        // the solid drawtext box): split the frame, blur a
                        // full-width strip at the cue's height, overlay it back.
                        if (op.blurBackground) {
                            val blurPad = (op.blurPaddingPx * effectiveDensity * (refHeight / 1080f)).toInt().coerceAtLeast(0)
                            val bandH = blockHeightForBand + 2 * blurPad
                            // Band top, mirroring the drawtext block position below.
                            val bandY = { hVar: String ->
                                if (op.hasCustomPosition()) "(($hVar*${op.relativeY})-${blockHeightForBand / 2}-$blurPad)"
                                else when (op.position) {
                                    TextPosition.TOP_LEFT, TextPosition.TOP_CENTER, TextPosition.CENTER_TOP, TextPosition.TOP_RIGHT -> "max(0\\,24-$blurPad)"
                                    TextPosition.CENTER_LEFT, TextPosition.CENTER, TextPosition.CENTER_RIGHT -> "(($hVar-$blockHeightForBand)/2-$blurPad)"
                                    else -> "($hVar-$marginY-$blockHeightForBand-$blurPad)"
                                }
                            }
                            val subSrcLabel = "[sub_src_$stageIndex]"
                            val subMainLabel = "[sub_main_$stageIndex]"
                            val subBlurLabel = "[sub_blur_$stageIndex]"
                            val bandLabel = "[v$stageIndex]"
                            stages.add("${currentLabel}split=2$subSrcLabel$subMainLabel")
                            stages.add("${subSrcLabel}crop=w=iw:h='trunc($bandH/2)*2':x=0:y='${bandY("h")}',boxblur=${op.blurRadius}$subBlurLabel")
                            stages.add("${subMainLabel}${subBlurLabel}overlay=x=0:y='${bandY("H")}'$enablePart$bandLabel")
                            currentLabel = bandLabel
                            stageIndex++
                        }

                        if (shapedCue != null) {
                            // Whole cue block as one PNG (all wrapped lines).
                            val pngLabel = "[shaped_sub_$stageIndex]"
                            val nextLabel = "[v$stageIndex]"
                            stages.add("[${shapedCue.inputIndex}:v]format=rgba$pngLabel")
                            val posPart = if (op.hasCustomPosition()) {
                                "x='(W*${op.relativeX})-(w/2)':y='(H*${op.relativeY})-(h/2)'"
                            } else {
                                when (op.position) {
                                    TextPosition.TOP_LEFT -> "x=24:y=24"
                                    TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> "x='(W-w)/2':y=24"
                                    TextPosition.TOP_RIGHT -> "x='W-w-24':y=24"
                                    TextPosition.CENTER_LEFT -> "x=24:y='(H-h)/2'"
                                    TextPosition.CENTER -> "x='(W-w)/2':y='(H-h)/2'"
                                    TextPosition.CENTER_RIGHT -> "x='W-w-24':y='(H-h)/2'"
                                    TextPosition.BOTTOM_LEFT -> "x=24:y='H-$marginY-h'"
                                    TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> "x='(W-w)/2':y='H-$marginY-h'"
                                    TextPosition.BOTTOM_RIGHT -> "x='W-w-24':y='H-$marginY-h'"
                                }
                            }
                            stages.add("$currentLabel${pngLabel}overlay=$posPart$enablePart$nextLabel")
                            currentLabel = nextLabel
                            stageIndex++
                        }
                        if (shapedCue == null) for ((lineIdx, lineText) in lines.withIndex()) {
                            val escapedLine = lineText
                                .replace("\\", "\\\\")
                                .replace("'", "\\\\'")
                                .replace(":", "\\:")

                            val lineOffset = lineIdx * lineSpacing

                            val posPart = if (op.hasCustomPosition()) {
                                val startY = "(h*${op.relativeY})-${totalBlockHeight / 2}"
                                "x='(w*${op.relativeX})-(tw/2)':y='$startY+$lineOffset'"
                            } else {
                                when (op.position) {
                                    TextPosition.TOP_LEFT -> "x=24:y=24+$lineOffset"
                                    TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> "x=(w-tw)/2:y=24+$lineOffset"
                                    TextPosition.TOP_RIGHT -> "x=w-tw-24:y=24+$lineOffset"
                                    TextPosition.CENTER_LEFT -> "x=24:y=(h-${totalBlockHeight})/2+$lineOffset"
                                    TextPosition.CENTER -> "x=(w-tw)/2:y=(h-${totalBlockHeight})/2+$lineOffset"
                                    TextPosition.CENTER_RIGHT -> "x=w-tw-24:y=(h-${totalBlockHeight})/2+$lineOffset"
                                    TextPosition.BOTTOM_LEFT -> "x=24:y=h-$marginY-$totalBlockHeight+$lineOffset"
                                    TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> "x=(w-tw)/2:y=h-$marginY-$totalBlockHeight+$lineOffset"
                                    TextPosition.BOTTOM_RIGHT -> "x=w-tw-24:y=h-$marginY-$totalBlockHeight+$lineOffset"
                                }
                            }

                            // No solid box when the blurred band is behind the text.
                            val boxPart = if (op.blurBackground) ":box=0" else ":box=1:boxcolor='0x00000080':boxborderw=$boxBorderW"
                            val filterExpr = "drawtext=${fontPart}text='$escapedLine':fontcolor='white':fontsize=${scaledFontSize}:${posPart}${boxPart}$enablePart"
                            
                            val nextLabel = "[v$stageIndex]"
                            stages.add("$currentLabel$filterExpr$nextLabel")
                            currentLabel = nextLabel
                            stageIndex++
                        }
                    }
                }
                else -> {}
            }
        }

        // Process crop at the end so it crops the video and all overlays together, matching the preview.
        for (op in cropOps) {
            val filterExpr = buildCropFilterExpr(op)
            if (filterExpr != null) {
                val nextLabel = "[v$stageIndex]"
                stages.add("$currentLabel$filterExpr$nextLabel")
                currentLabel = nextLabel
                stageIndex++
            }
        }

        return Pair(stages, currentLabel)
    }

    /**
     * Build the consolidated FFmpeg command for final export.
     *
     * Uses -filter_complex with explicit stream labels so each filter stage
     * receives the correct input dimensions (e.g., drawtext after crop uses
     * post-crop resolution).
     *
     * @param sourceFilePath  Absolute path to the source video.
     * @param outputFilePath  Absolute path for the exported video.
     * @param fontFilePath    Absolute path to a TTF font file (from assets via copyFontToCache).
     *                        Required for text overlays to render on Android.
     */
    fun buildConsolidatedFFmpegCommand(
        sourceFilePath: String,
        outputFilePath: String,
        fontFilePath: String? = null,
        context: Context? = null
    ): String? {
        val currentProject = _project.value ?: return null
        val density = context?.resources?.displayMetrics?.density ?: 1.0f

        var sourceVideoWidth = 1280
        var sourceVideoHeight = 720
        var sourceFrameRate = 0f
        val isMainImage = isImagePath(sourceFilePath)
        try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceFilePath)
                val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                // Frame rate for the stream-copy gate: capture framerate
                // first, frame count / duration as the fallback (see
                // probeSourceFrameRate for why both are needed).
                sourceFrameRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull() ?: 0f
                if (sourceFrameRate <= 0f) {
                    val frames = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toLongOrNull() ?: 0L
                    val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    if (frames > 0 && durationMs > 0) sourceFrameRate = frames * 1000f / durationMs
                }
                val w = wStr?.toIntOrNull() ?: 1280
                val h = hStr?.toIntOrNull() ?: 720
                val r = rStr?.toIntOrNull() ?: 0
                if (r == 90 || r == 270) {
                    sourceVideoWidth = h
                    sourceVideoHeight = w
                } else {
                    sourceVideoWidth = w
                    sourceVideoHeight = h
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting source video dimensions: ${e.message}")
        }

        // Fast path only when nothing is asked of the export: Original
        // resolution and not audio-only. Otherwise fall through so the
        // chosen resolution (and audio-only) is honoured by the pipeline.
        // Stream-copy is the fastest path, but it is valid only for video
        // sources whose frame rate already matches the selected export
        // FPS. Otherwise the requested FPS would be silently ignored.
        // Unknown source FPS deliberately falls through to the normal
        // encoder pipeline.
        val canUseStreamCopy = !isMainImage &&
            sourceFrameRate > 0f &&
            kotlin.math.abs(sourceFrameRate - _exportFps.value.toFloat()) < 0.5f
        if (currentProject.operations.isEmpty() &&
            _exportResolution.value == EXPORT_RESOLUTION_ORIGINAL &&
            !_exportAudioOnly.value &&
            canUseStreamCopy
        ) {
            return "-y -i \"$sourceFilePath\" -c copy \"$outputFilePath\""
        }

        val operations = currentProject.operations

        // Detect if source video has an audio stream
        val hasAudio = if (isMainImage) {
            false
        } else {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(sourceFilePath)
                    val hasAudioStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                    hasAudioStr == "yes"
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking audio in source file: ${e.message}")
                true // fallback to assuming it has audio
            }
        }

        // ── Collect operation types ───────────────────────────────────────────
        val mergeOp = operations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        val nonMergeOps = operations.filterNot { it is EditOperation.Merge }
        val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()
        val audioOps = operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        val audioMuted = operations.any { it is EditOperation.MuteAudio }
        val videoOps = nonMergeOps.filter { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddImageOverlay || it is EditOperation.AddBlurBox || it is EditOperation.AddSubtitles }
        val imageOps = operations.filterIsInstance<EditOperation.AddImageOverlay>()

        // ── Unified Input Indexing ────────────────────────────────────────────
        val cmd = StringBuilder("-y") // Always overwrite output — prevents silent failure on retry
        var inputIndex = 0

        /** Resolve a URI to a real filesystem path FFmpeg can read.
         *  content:// URIs are copied to cacheDir; file:// or plain paths are used directly. */
        fun resolveUriToPath(uri: android.net.Uri): String? {
            if (uri.scheme == "content" && context != null) {
                return copyContentUriToTempFile(context, uri)
            }
            return uri.path ?: uri.toString()
        }

        // 1. Source Video Input (Index 0)
        var outputDuration: Double? = null
        val reverseOp = operations.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
        val speedOp = operations.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
        val finalProxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri
        
        if (finalProxyUri != null) {
            val proxyPath = resolveUriToPath(finalProxyUri) ?: finalProxyUri.toString()
            val baseDuration = if (trimOp != null) (trimOp.endMs - trimOp.startMs) else 0L
            if (baseDuration > 0) {
                val speed = speedOp?.speed ?: 1.0f
                outputDuration = baseDuration / speed / 1000.0
                cmd.append(" -t $outputDuration -i \"$proxyPath\"")
            } else {
                cmd.append(" -i \"$proxyPath\"")
            }
        } else {
            val isMainImage = sourceFilePath.endsWith(".png", ignoreCase = true) ||
                              sourceFilePath.endsWith(".jpg", ignoreCase = true) ||
                              sourceFilePath.endsWith(".jpeg", ignoreCase = true) ||
                              sourceFilePath.endsWith(".webp", ignoreCase = true)
            if (isMainImage) {
                val duration = if (trimOp != null) (trimOp.endMs - trimOp.startMs) / 1000.0 else 5.0
                if (mergeOp == null) {
                    outputDuration = duration
                }
                cmd.append(" -loop 1 -t $duration -i \"$sourceFilePath\"")
            } else if (trimOp != null) {
                val startSecs = trimOp.startMs / 1000.0
                val duration = (trimOp.endMs - trimOp.startMs) / 1000.0
                if (mergeOp == null) {
                    outputDuration = duration
                }
                cmd.append(" -ss $startSecs -t $duration -i \"$sourceFilePath\"")
            } else {
                cmd.append(" -i \"$sourceFilePath\"")
            }
        }
        inputIndex++

        // 2. Merge Video Inputs
        val mergeVideoIndices = mutableListOf<Int>()
        if (mergeOp != null) {
            for (item in mergeOp.items) {
                if (item.proxyUri != null) {
                    val proxyPath = item.proxyUri.path ?: item.proxyUri.toString()
                    val duration = item.trimmedDurationMs / 1000.0
                    cmd.append(" -t $duration -i \"$proxyPath\"")
                } else {
                    val videoPath = resolveUriToPath(item.uri) ?: item.uri.toString()
                    val startSecs = item.trimStartMs / 1000.0
                    val duration = (item.trimEndMs - item.trimStartMs) / 1000.0
                    
                    val isImage = item.isImage || 
                                  videoPath.endsWith(".png", ignoreCase = true) || 
                                  videoPath.endsWith(".jpg", ignoreCase = true) || 
                                  videoPath.endsWith(".jpeg", ignoreCase = true) ||
                                  videoPath.endsWith(".webp", ignoreCase = true)
                    
                    if (isImage) {
                        cmd.append(" -loop 1 -t $duration -i \"$videoPath\"")
                    } else {
                        cmd.append(" -ss $startSecs -t $duration -i \"$videoPath\"")
                    }
                }
                mergeVideoIndices.add(inputIndex)
                inputIndex++
            }
        }

        // 3. Audio Inputs  (content:// URIs must be cached to a real path)
        val audioInputIndices = mutableListOf<Pair<Int, EditOperation.AddBackgroundAudio>>()
        for (audioOp in audioOps) {
            val audioPath = resolveUriToPath(audioOp.audioUri)
            if (audioPath == null) {
                Log.e(TAG, "Failed to resolve audio URI to path: ${audioOp.audioUri} — skipping track")
                continue
            }
            cmd.append(" -i \"$audioPath\"")
            audioInputIndices.add(Pair(inputIndex, audioOp))
            inputIndex++
        }

        // 4. Image Overlay Inputs  (content:// URIs must be cached to a real path)
        val imageInputIndices = mutableListOf<Pair<Int, EditOperation.AddImageOverlay>>()
        for (imageOp in imageOps) {
            val imagePath = resolveUriToPath(imageOp.imageUri)
            if (imagePath == null) {
                Log.e(TAG, "Failed to resolve image URI to path: ${imageOp.imageUri} — skipping overlay")
                continue
            }
            val isGif = imagePath.endsWith(".gif", ignoreCase = true)
            val isVideo = imagePath.endsWith(".mp4", ignoreCase = true) ||
                          imagePath.endsWith(".mkv", ignoreCase = true) ||
                          imagePath.endsWith(".mov", ignoreCase = true) ||
                          imagePath.endsWith(".3gp", ignoreCase = true)
            if ((isGif || isVideo) && imageOp.isLooping) {
                cmd.append(" -stream_loop -1 -i \"$imagePath\"")
            } else {
                cmd.append(" -i \"$imagePath\"")
            }
            imageInputIndices.add(Pair(inputIndex, imageOp))
            inputIndex++
        }

        // 4b. Shaped-text image inputs — Myanmar text pre-rendered to PNG
        // (drawtext cannot shape it). Each PNG is a looped still input.
        val shapedTextInputs = mutableListOf<ShapedTextInput>()
        val shapedImages = collectShapedTextImages(videoOps, fontFilePath, density, sourceVideoHeight, context)
        for ((key, rendered) in shapedImages) {
            cmd.append(" -loop 1 -i \"${rendered.file.absolutePath}\"")
            shapedTextInputs.add(ShapedTextInput(inputIndex, key, rendered.width, rendered.height))
            inputIndex++
        }

        // 5. Canvas Background Image Input
        val bgOp = operations.filterIsInstance<EditOperation.CanvasBackground>().lastOrNull()
        // CapCut-style canvas ratio (one op at a time; see ViewModelExt).
        val ratioOp = operations.filterIsInstance<EditOperation.CanvasRatio>().lastOrNull()
        var bgImageIndex = -1
        if (bgOp != null && bgOp.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgOp.imageUri != null) {
            val bgPath = resolveUriToPath(bgOp.imageUri)
            if (bgPath != null) {
                cmd.append(" -loop 1 -i \"$bgPath\"")
                bgImageIndex = inputIndex
                inputIndex++
            }
        }

        // ── MERGE PATH ────────────────────────────────────────────────────────
        if (mergeOp != null) {
            val inputCount = 1 + mergeOp.videoUris.size
            val filterParts = mutableListOf<String>()

            var mainWidth = 1280
            var mainHeight = 720
            var sourceDurationMs = 0L

            if (isMainImage) {
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(sourceFilePath, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        mainWidth = opts.outWidth
                        mainHeight = opts.outHeight
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting image dimensions: ${e.message}")
                }
                sourceDurationMs = 5000L
            } else {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(sourceFilePath)
                        val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        val dStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val w = wStr?.toIntOrNull() ?: 1280
                        val h = hStr?.toIntOrNull() ?: 720
                        val r = rStr?.toIntOrNull() ?: 0
                        sourceDurationMs = dStr?.toLongOrNull() ?: 0L
                        if (r == 90 || r == 270) {
                            mainWidth = h
                            mainHeight = w
                        } else {
                            mainWidth = w
                            mainHeight = h
                        }
                    } finally {
                        retriever.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting dimensions: ${e.message}")
                }
            }

            // FFmpeg requires even dimensions
            if (mainWidth % 2 != 0) mainWidth -= 1
            if (mainHeight % 2 != 0) mainHeight -= 1

            val hasAudioArray = BooleanArray(inputCount)
            val durationsArray = DoubleArray(inputCount)
            
            hasAudioArray[0] = if (isMainImage) false else hasAudio
            durationsArray[0] = if (isMainImage) {
                if (trimOp != null) (trimOp.endMs - trimOp.startMs) / 1000.0 else 5.0
            } else if (speedOp?.proxyUri != null) {
                val base = if (trimOp != null) (trimOp.endMs - trimOp.startMs) else sourceDurationMs
                (base / speedOp.speed) / 1000.0
            } else if (trimOp != null) {
                (trimOp.endMs - trimOp.startMs) / 1000.0
            } else {
                sourceDurationMs / 1000.0
            }

            for ((idx, item) in mergeOp.items.withIndex()) {
                val path = item.uri.path ?: item.uri.toString()
                hasAudioArray[idx + 1] = try {
                    val isImage = path.endsWith(".png", ignoreCase = true) || 
                                  path.endsWith(".jpg", ignoreCase = true) || 
                                  path.endsWith(".jpeg", ignoreCase = true) ||
                                  path.endsWith(".webp", ignoreCase = true)
                    if (isImage) {
                        false
                    } else {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(path)
                            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                            h == "yes"
                        } finally {
                            r.release()
                        }
                    }
                } catch (e: Exception) { false }
                durationsArray[idx + 1] = item.trimmedDurationMs / 1000.0
            }


            for (i in 0 until inputCount) {
                val colorFilterOp = operations.filterIsInstance<EditOperation.ColorFilter>().find { it.index == i }
                val lutFilterExpr = colorFilterOp?.let { getFFmpegFilterForName(it.filterName) }
                val adjustOp = operations.filterIsInstance<EditOperation.Adjust>().find { it.index == i }
                val adjustFilterExpr = adjustOp?.let { getFFmpegFilterForAdjust(it) }

                val isMirrored = if (i == 0) {
                    operations.any { it is EditOperation.MirrorMain && it.isMirrored }
                } else {
                    mergeOp?.items?.getOrNull(i - 1)?.isMirrored == true
                }
                
                val maskConfig = if (i == 0) {
                    val mainMask = operations.filterIsInstance<EditOperation.MaskMain>().lastOrNull()?.maskConfig
                    if (mainMask != null && mainMask.shape != EditOperation.MaskShape.NONE) {
                        mainMask
                    } else {
                        mergeOp?.items?.getOrNull(0)?.maskConfig ?: EditOperation.MaskConfig()
                    }
                } else {
                    mergeOp?.items?.getOrNull(i - 1)?.maskConfig ?: EditOperation.MaskConfig()
                }
                
                // 1. Prepare raw video with basic visual filters
                var preFilters = "setpts=PTS-STARTPTS"
                if (lutFilterExpr != null) preFilters += ",$lutFilterExpr"
                if (adjustFilterExpr != null) preFilters += ",$adjustFilterExpr"
                if (isMirrored) preFilters += ",hflip"
                
                if (maskConfig.shape != EditOperation.MaskShape.NONE) {
                    val mc = maskConfig
                    val cx = if (mc.positionKeyframes.isNotEmpty()) "(W*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, false, mc.relativeX, 0L, "T") + "))" else "W*${mc.relativeX}"
                    val cy = if (mc.positionKeyframes.isNotEmpty()) "(H*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, true, mc.relativeY, 0L, "T") + "))" else "H*${mc.relativeY}"
                    val sizeExpr = if (mc.sizeKeyframes.isNotEmpty()) buildFFmpegInterpolationExpr(mc.sizeKeyframes, false, ((mc.relativeWidth + mc.relativeHeight) / 2f * 200f), 0L, "T") else null
                    val mw = if (sizeExpr != null) "(W*(($sizeExpr)/200))" else "W*${mc.relativeWidth}"
                    val mh = if (sizeExpr != null) "(H*(($sizeExpr)/200))" else "H*${mc.relativeHeight}"
                    val cosA: String
                    val sinA: String
                    if (mc.rotationKeyframes.isNotEmpty()) {
                        val rotExpr = buildFFmpegInterpolationExpr(mc.rotationKeyframes, false, mc.rotationAngle, 0L, "T")
                        val rad = "(($rotExpr)*PI/180)"
                        cosA = "cos($rad)"
                        sinA = "sin($rad)"
                    } else {
                        val rad = Math.toRadians(mc.rotationAngle.toDouble())
                        cosA = Math.cos(rad).toString()
                        sinA = Math.sin(rad).toString()
                    }
                    val finalAlphaExpr = buildFFmpegMaskAlphaExpr(mc, cx, cy, mw, mh, cosA, sinA, 0L, "T")
                    preFilters += ",format=rgba,geq=r='r(X,Y)*($finalAlphaExpr/255)':g='g(X,Y)*($finalAlphaExpr/255)':b='b(X,Y)*($finalAlphaExpr/255)':a='255*($finalAlphaExpr/255)'"
                }
                
                filterParts.add("[$i:v]$preFilters[pre$i]")

                // 2. Apply background/padding
                if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.BLUR) {
                    val blur = bgOp.blurRadius
                    val bgScale = blurBgFilter(mainWidth, mainHeight, blur)
                    val fgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease"
                    filterParts.add("[pre$i]split=2[bg_orig$i][fg_orig$i]")
                    filterParts.add("[bg_orig$i]$bgScale[bg$i]")
                    filterParts.add("[fg_orig$i]$fgScale[fg$i]")
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2,setsar=1,fps=30,format=yuv420p[norm$i]")
                } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1) {
                    val bgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=increase,crop=$mainWidth:$mainHeight"
                    val fgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease"
                    filterParts.add("[$bgImageIndex:v]$bgScale[bg$i]")
                    filterParts.add("[pre$i]$fgScale[fg$i]")
                    // Use shortest=1 in case the loop goes on forever, but we specified -loop 1 which creates an infinite stream for image
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1,fps=30,format=yuv420p[norm$i]")
                } else {
                    // Default COLOR
                    val color = bgOp?.colorHex ?: "black"
                    val padColor = if (color.startsWith("#")) color else "black"
                    // Create a solid color background of mainWidth x mainHeight
                    filterParts.add("color=c=$padColor:s=${mainWidth}x${mainHeight}:r=30[bg$i]")
                    filterParts.add("[pre$i]scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease[fg$i]")
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1,fps=30,format=yuv420p[norm$i]")
                }

                val isClipMuted = operations.filterIsInstance<EditOperation.MuteClip>().find { it.index == i }?.isMuted ?: false
                if (hasAudioArray[i] && !isClipMuted) {
                    filterParts.add("[$i:a]asetpts=PTS-STARTPTS,aformat=sample_rates=44100:channel_layouts=stereo[anorm$i]")
                } else {
                    val d = durationsArray[i]
                    filterParts.add("anullsrc=r=44100:cl=stereo,atrim=duration=$d,asetpts=PTS-STARTPTS[anorm$i]")
                }
            }

            // Build a list of per-gap transition info (gap i = between clip i and clip i+1)
            // transitionOps[i] is the Transition op for gap i, or null if none.
            val transitionOps = List(inputCount - 1) { gapIdx ->
                operations.filterIsInstance<EditOperation.Transition>().find { it.index == gapIdx }
            }

            // Chain xfade/acrossfade for transitions and concat for plain gaps.
            // Represents a finalized stream ready to be used as xfade input
            data class StreamInfo(val vLabel: String, val aLabel: String, val durationSec: Double)

            val pendingClipIndices = mutableListOf(0) // start with clip 0

            var currentStream: StreamInfo? = null
            var transitionLabelCounter = 0
            var concatLabelCounter = 0

            fun flushPendingGroup(clipIndices: List<Int>): StreamInfo {
                return if (clipIndices.size == 1) {
                    val i = clipIndices[0]
                    StreamInfo("[norm$i]", "[anorm$i]", durationsArray[i])
                } else {
                    val vOut = "[ccat${concatLabelCounter}v]"
                    val aOut = "[ccat${concatLabelCounter}a]"
                    val inputs = clipIndices.joinToString("") { "[norm$it][anorm$it]" }
                    // concat then normalize timestamps for proper xfade offsets
                    filterParts.add("${inputs}concat=n=${clipIndices.size}:v=1:a=1${vOut}${aOut}")
                    filterParts.add("${vOut}settb=1/30,setpts=N[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    val totalDur = clipIndices.sumOf { durationsArray[it] }
                    concatLabelCounter++
                    // use the normalized labels for subsequent processing
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", totalDur)
                }
            }

            for (gapIdx in 0 until inputCount - 1) {
                val transOp = transitionOps[gapIdx]
                if (transOp == null || transOp.type == "none") {
                    // No transition at this gap — just accumulate the next clip
                    pendingClipIndices.add(gapIdx + 1)
                } else {
                    // Flush the pending group into a stream, if any pending clips exist.
                    // If none exist, it means the currentStream (previous transition output) is the base.
                    val baseStream = if (pendingClipIndices.isEmpty()) {
                        currentStream ?: throw IllegalStateException("Both pending clips and currentStream are empty")
                    } else {
                        val leftStream = flushPendingGroup(pendingClipIndices)
                        pendingClipIndices.clear()
                        if (currentStream == null) {
                            leftStream
                        } else {
                            // Concat previous xfade output with the newly flushed left group
                            val vOut = "[ccat${concatLabelCounter}v]"
                            val aOut = "[ccat${concatLabelCounter}a]"
                            filterParts.add("${currentStream.vLabel}${currentStream.aLabel}${leftStream.vLabel}${leftStream.aLabel}concat=n=2:v=1:a=1${vOut}${aOut}")
                            filterParts.add("${vOut}settb=1/30,setpts=N[norm${concatLabelCounter}v]")
                            filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                            concatLabelCounter++
                            StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", currentStream.durationSec + leftStream.durationSec)
                        }
                    }

                    val rawTransDuration = transOp.durationMs / 1000.0
                    val transDuration = rawTransDuration.coerceAtMost(baseStream.durationSec).coerceAtMost(durationsArray[gapIdx + 1])
                    val offset = (baseStream.durationSec - transDuration).coerceAtLeast(0.0)

                    val xfvOut = "[xfv${transitionLabelCounter}]"
                    val xfaOut = "[xfa${transitionLabelCounter}]"
                    val nextVLabel = "[norm${gapIdx + 1}]"
                    val nextALabel = "[anorm${gapIdx + 1}]"

                    val validXfadeTransitions = setOf(
                        "fade", "fadeblack", "fadewhite", "rectcrop", "circlecrop", "circleclose", "circleopen",
                        "horzclose", "horzopen", "vertclose", "vertopen", "diagbl", "diagbr", "diagtl", "diagtr",
                        "hlslice", "hrslice", "vuslice", "vdslice", "dissolve", "pixelize", "slidedown", "slideleft",
                        "slideright", "slideup", "wipedown", "wipeleft", "wiperight", "wipeup", "zoomin", "squeezeh",
                        "squeezev", "coverleft", "coverright", "coverup", "coverdown", "revealleft", "revealright",
                        "revealup", "revealdown"
                    )
                    val rawType = transOp.type.lowercase()
                    val sanitizedTransType = when (rawType) {
                        "smoothleft" -> "coverleft"
                        "smoothright" -> "coverright"
                        "distance" -> "zoomin"
                        else -> if (validXfadeTransitions.contains(rawType)) rawType else "fade"
                    }

                    filterParts.add("${baseStream.vLabel}${nextVLabel}xfade=transition=${sanitizedTransType}:duration=${transDuration}:offset=${offset}${xfvOut}")
                    filterParts.add("${baseStream.aLabel}${nextALabel}acrossfade=d=${transDuration}:c1=tri:c2=tri${xfaOut}")

                    val newDuration = offset + durationsArray[gapIdx + 1]
                    currentStream = StreamInfo(xfvOut, xfaOut, newDuration)
                    transitionLabelCounter++
                    // Next clip (gapIdx+1) has already been consumed by xfade — don't add it to pending
                }
            }

            // Flush any remaining pending clips
            val remainingStream = if (pendingClipIndices.isNotEmpty()) flushPendingGroup(pendingClipIndices) else null

            val finalStream: StreamInfo = when {
                currentStream == null && remainingStream != null -> remainingStream
                currentStream != null && remainingStream == null -> currentStream!!
                currentStream != null && remainingStream != null -> {
                    // Concat the last xfade output with remaining plain clips
                    // FFmpeg concat requires interleaved [v0][a0][v1][a1] order
                    val vOut = "[ccat${concatLabelCounter}v]"
                    val aOut = "[ccat${concatLabelCounter}a]"
                    // concat then normalize timestamps
                    filterParts.add("${currentStream.vLabel}${currentStream.aLabel}${remainingStream.vLabel}${remainingStream.aLabel}concat=n=2:v=1:a=1${vOut}${aOut}")
                    filterParts.add("${vOut}settb=1/30,setpts=N[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    concatLabelCounter++
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", currentStream.durationSec + remainingStream.durationSec)
                }
                else -> StreamInfo("[norm0]", "[anorm0]", durationsArray[0]) // fallback single clip
            }

            var currentVLabel = finalStream.vLabel
            var currentALabel = finalStream.aLabel
            val accumulatedDuration = finalStream.durationSec

            var currentVideoLabel = currentVLabel
            var currentAudioLabel = currentALabel

            outputDuration = accumulatedDuration


            val (sourceVideoStages, tempVideoLabel) = buildVideoFilterStages(
                operations = videoOps,
                fontFilePath = fontFilePath,
                inputLabel = currentVideoLabel,
                imageInputIndices = imageInputIndices,
                density = density,
                sourceVideoHeight = sourceVideoHeight,
                shapedTextInputs = shapedTextInputs,
                canvasRatioOp = ratioOp,
                bgOp = bgOp,
                bgImageIndex = bgImageIndex,
                ratioSourceWidth = sourceVideoWidth,
                ratioSourceHeight = sourceVideoHeight,
                canvasLongEdgeCap = _exportResolution.value
            )
            filterParts.addAll(sourceVideoStages)
            val finalVideoLabel = "[fmtv]"
            val mergeScaleFilter = exportResolutionScaleFilter()
            val mergeScalePart = if (mergeScaleFilter != null) "$mergeScaleFilter," else ""
            filterParts.add("${tempVideoLabel}${mergeScalePart}fps=${_exportFps.value},format=yuv420p$finalVideoLabel")

            if (audioMuted) {
                filterParts.add("${currentAudioLabel}anullsink")
            } else if (audioInputIndices.isNotEmpty()) {
                val mixInputLabels = mutableListOf<String>()
                mixInputLabels.add(currentAudioLabel)

                for ((idx, pair) in audioInputIndices.withIndex()) {
                    val (inputIdx, audioOp) = pair
                    val trackLabel = "[a_bg_$idx]"
                    val filters = mutableListOf<String>()

                    val internalStartSec = audioOp.internalStartMs / 1000.0
                    var internalEndSec = if (audioOp.internalEndMs > 0L) audioOp.internalEndMs / 1000.0 else Double.MAX_VALUE

                    if (audioOp.startTimeMs != null && audioOp.endTimeMs != null) {
                        val timelineDurationSec = (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                        val maxEndSec = internalStartSec + timelineDurationSec
                        if (maxEndSec < internalEndSec) internalEndSec = maxEndSec
                    }

                    if (internalStartSec > 0.0 || internalEndSec != Double.MAX_VALUE) {
                        if (internalEndSec != Double.MAX_VALUE) {
                            filters.add("atrim=start=$internalStartSec:end=$internalEndSec,asetpts=PTS-STARTPTS")
                        } else {
                            filters.add("atrim=start=$internalStartSec,asetpts=PTS-STARTPTS")
                        }
                    }
                    if (audioOp.fadeInDurationMs > 0L) {
                        val fadeSec = audioOp.fadeInDurationMs / 1000.0
                        filters.add("afade=t=in:st=0:d=$fadeSec")
                    }
                    if (audioOp.fadeOutDurationMs > 0L) {
                        val fadeSec = audioOp.fadeOutDurationMs / 1000.0
                        val clipDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                            (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                        } else if (audioOp.internalEndMs > 0L) {
                            (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                        } else {
                            (audioOp.originalDurationMs - audioOp.internalStartMs) / 1000.0
                        }
                        val fadeOutStart = maxOf(0.0, clipDur - fadeSec)
                        filters.add("afade=t=out:st=$fadeOutStart:d=$fadeSec")
                    }
                    var delayMs = audioOp.startTimeMs ?: 0L
                    if (delayMs < 0) delayMs = 0L
                    if (delayMs > 0) {
                        filters.add("adelay=$delayMs|$delayMs")
                    }
                    if (audioOp.volume != 1.0f) {
                        filters.add("volume=${audioOp.volume}")
                    }

                    if (filters.isNotEmpty()) {
                        filterParts.add("[$inputIdx:a]${filters.joinToString(",")}$trackLabel")
                        mixInputLabels.add(trackLabel)
                    } else {
                        mixInputLabels.add("[$inputIdx:a]")
                    }
                }

                if (!audioInputIndices.any { it.second.removeOriginalAudio }) {
                    var mainAudioRef = currentAudioLabel
                    val duckingCount = audioInputIndices.count { it.second.ducking }
                    
                    if (duckingCount > 0) {
                        val splits = (0..duckingCount).map { "[main_split_$it]" }
                        filterParts.add("${mainAudioRef}asplit=${duckingCount + 1}${splits.joinToString("")}")
                        mainAudioRef = splits[0]
                        
                        var splitIdx = 1
                        for (i in mixInputLabels.indices.drop(1)) { // skip index 0 which is currentAudioLabel
                            val opIndex = i - 1
                            val op = audioInputIndices[opIndex].second
                            if (op.ducking) {
                                val bgLabel = mixInputLabels[i]
                                val sidechainLabel = splits[splitIdx++]
                                val duckedLabel = "[ducked_$opIndex]"
                                filterParts.add("${bgLabel}aformat=sample_rates=44100:channel_layouts=stereo[bg_fmt_$opIndex]")
                                filterParts.add("${sidechainLabel}aformat=sample_rates=44100:channel_layouts=stereo[sc_fmt_$opIndex]")
                                filterParts.add("[bg_fmt_$opIndex][sc_fmt_$opIndex]sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500${duckedLabel}")
                                mixInputLabels[i] = duckedLabel
                            }
                        }
                    }
                    
                    mixInputLabels[0] = mainAudioRef
                    val allInputs = mixInputLabels.joinToString("")
                    filterParts.add("${allInputs}amix=inputs=${mixInputLabels.size}:duration=longest[outa]")
                    currentAudioLabel = "[outa]"
                } else {
                    filterParts.add("${mixInputLabels[0]}anullsink")
                    mixInputLabels.removeAt(0)
                    if (mixInputLabels.size == 1) {
                        val singleLabel = mixInputLabels[0]
                        filterParts.add("${singleLabel}aformat=sample_rates=44100:channel_layouts=stereo[outa]")
                        currentAudioLabel = "[outa]"
                    } else {
                        val allBg = mixInputLabels.joinToString("")
                        filterParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest[outa]")
                        currentAudioLabel = "[outa]"
                    }
                }
            }

            if (_exportAudioOnly.value) {
                filterParts.add("${finalVideoLabel}nullsink")
            }
            cmd.append(filterComplexThreadsArg())
            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
            
            if (_exportAudioOnly.value) {
                if (!audioMuted) {
                    cmd.append(" -map \"$currentAudioLabel\"")
                    // AIO VD Editor ffmpeg might have libmp3lame, fallback to libmp3lame or default mp3 encoder
                    cmd.append(" -c:a libmp3lame -b:a 192k")
                } else {
                    cmd.append(" -an")
                }
            } else {
                cmd.append(" -map \"$finalVideoLabel\"")
                if (!audioMuted) {
                    cmd.append(" -map \"$currentAudioLabel\"")
                } else {
                    cmd.append(" -an")
                }
                
                val bitrate = exportBitrate(sourceVideoHeight)
                val defaultEncoder = context?.getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
                    ?.getString("default_encoder", "auto") ?: "hardware"
                val videoCodec = if (defaultEncoder == "software") "-c:v libx264 -pix_fmt yuv420p" else "-c:v h264_mediacodec -pix_fmt yuv420p -b:v $bitrate"
                cmd.append(" $videoCodec")
                
                if (!audioMuted) {
                    cmd.append(" -c:a aac")
                }
            }

            if (outputDuration != null) {
                cmd.append(" -t $outputDuration")
            }
            cmd.append(" \"$outputFilePath\"")

            val finalCommand = cmd.toString()
            Log.d(TAG, "Built merge command: $finalCommand")
            return finalCommand
        }

        // ── NON-MERGE PATH ────────────────────────────────────────────────────
        val filterComplexParts = mutableListOf<String>()
        var currentInputVideoLabel = "[0:v]"

        val colorFilterOp = operations.filterIsInstance<EditOperation.ColorFilter>().find { it.index == 0 }
        val adjustOp = operations.filterIsInstance<EditOperation.Adjust>().find { it.index == 0 }

        val prepFilters = mutableListOf<String>()
        if (colorFilterOp != null) {
            val lutFilterExpr = getFFmpegFilterForName(colorFilterOp.filterName)
            if (lutFilterExpr != null) {
                prepFilters.add(lutFilterExpr)
            }
        }
        if (adjustOp != null) {
            val adjustFilterExpr = getFFmpegFilterForAdjust(adjustOp)
            if (adjustFilterExpr != null) {
                prepFilters.add(adjustFilterExpr)
            }
        }
        val mirrorMainOp = operations.filterIsInstance<EditOperation.MirrorMain>().find { it.isMirrored }
        if (mirrorMainOp != null) {
            prepFilters.add("hflip")
        }
        
        val maskMainOp = operations.filterIsInstance<EditOperation.MaskMain>().lastOrNull()
        if (maskMainOp != null && maskMainOp.maskConfig.shape != EditOperation.MaskShape.NONE) {
            val mc = maskMainOp.maskConfig
            val cx = if (mc.positionKeyframes.isNotEmpty()) "(W*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, false, mc.relativeX, 0L, "T") + "))" else "W*${mc.relativeX}"
            val cy = if (mc.positionKeyframes.isNotEmpty()) "(H*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, true, mc.relativeY, 0L, "T") + "))" else "H*${mc.relativeY}"
            val sizeExpr = if (mc.sizeKeyframes.isNotEmpty()) buildFFmpegInterpolationExpr(mc.sizeKeyframes, false, ((mc.relativeWidth + mc.relativeHeight) / 2f * 200f), 0L, "T") else null
            val mw = if (sizeExpr != null) "(W*(($sizeExpr)/200))" else "W*${mc.relativeWidth}"
            val mh = if (sizeExpr != null) "(H*(($sizeExpr)/200))" else "H*${mc.relativeHeight}"
            val cosA: String
            val sinA: String
            if (mc.rotationKeyframes.isNotEmpty()) {
                val rotExpr = buildFFmpegInterpolationExpr(mc.rotationKeyframes, false, mc.rotationAngle, 0L, "T")
                val rad = "(($rotExpr)*PI/180)"
                cosA = "cos($rad)"
                sinA = "sin($rad)"
            } else {
                val rad = Math.toRadians(mc.rotationAngle.toDouble())
                cosA = Math.cos(rad).toString()
                sinA = Math.sin(rad).toString()
            }
            val finalAlphaExpr = buildFFmpegMaskAlphaExpr(mc, cx, cy, mw, mh, cosA, sinA, 0L, "T")
            prepFilters.add("format=rgba,geq=r='r(X,Y)*($finalAlphaExpr/255)':g='g(X,Y)*($finalAlphaExpr/255)':b='b(X,Y)*($finalAlphaExpr/255)':a='255*($finalAlphaExpr/255)'")
        }

        if (prepFilters.isNotEmpty()) {
            val combinedFilters = prepFilters.joinToString(",")
            filterComplexParts.add("[0:v]${combinedFilters}[cfv]")
            currentInputVideoLabel = "[cfv]"
        }

        // ── Canvas background (single clip) ───────────────────────────────
        // The merge path applies this per clip, but the non-merge path
        // previously ignored CanvasBackground entirely, so a BLUR/IMAGE/COLOR
        // canvas never showed up in a single-clip export.
        // Skipped when a CanvasRatio op exists: the canvas stage inside
        // buildVideoFilterStages composes the background onto the ratio
        // canvas instead (applying it here too would pad twice).
        var bgApplied = false
        if (bgOp != null && ratioOp == null) {
            val bgW = if (sourceVideoWidth % 2 != 0) sourceVideoWidth - 1 else sourceVideoWidth
            val bgH = if (sourceVideoHeight % 2 != 0) sourceVideoHeight - 1 else sourceVideoHeight
            val fgScale = "scale=$bgW:$bgH:force_original_aspect_ratio=decrease"
            when (bgOp.type) {
                EditOperation.CanvasBackground.BackgroundType.BLUR -> {
                    val blur = bgOp.blurRadius
                    val bgScale = blurBgFilter(bgW, bgH, blur)
                    filterComplexParts.add("${currentInputVideoLabel}split=2[bg_orig][fg_orig]")
                    filterComplexParts.add("[bg_orig]$bgScale[bg]")
                    filterComplexParts.add("[fg_orig]$fgScale[fg]")
                    filterComplexParts.add("[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1,format=yuv420p[bgout]")
                    currentInputVideoLabel = "[bgout]"
                    bgApplied = true
                }
                EditOperation.CanvasBackground.BackgroundType.IMAGE -> {
                    if (bgImageIndex != -1) {
                        val bgScale = "scale=$bgW:$bgH:force_original_aspect_ratio=increase,crop=$bgW:$bgH"
                        filterComplexParts.add("[$bgImageIndex:v]$bgScale[bg]")
                        filterComplexParts.add("$currentInputVideoLabel$fgScale[fg]")
                        filterComplexParts.add("[bg][fg]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1,format=yuv420p[bgout]")
                        currentInputVideoLabel = "[bgout]"
                        bgApplied = true
                    }
                }
                EditOperation.CanvasBackground.BackgroundType.COLOR -> {
                    val color = bgOp.colorHex
                    val padColor = if (color.startsWith("#")) color else "black"
                    filterComplexParts.add("color=c=$padColor:s=${bgW}x${bgH}:r=30[bg]")
                    filterComplexParts.add("$currentInputVideoLabel$fgScale[fg]")
                    filterComplexParts.add("[bg][fg]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1,format=yuv420p[bgout]")
                    currentInputVideoLabel = "[bgout]"
                    bgApplied = true
                }
            }
        }

        // ── Video filter stages (crop, text, image overlays) ──
        val (videoStages, finalVideoLabel) = buildVideoFilterStages(
            operations = videoOps,
            fontFilePath = fontFilePath,
            inputLabel = currentInputVideoLabel,
            imageInputIndices = imageInputIndices,
            density = density,
            sourceVideoHeight = sourceVideoHeight,
            shapedTextInputs = shapedTextInputs,
            canvasRatioOp = ratioOp,
            bgOp = bgOp,
            bgImageIndex = bgImageIndex,
            ratioSourceWidth = sourceVideoWidth,
            ratioSourceHeight = sourceVideoHeight,
            canvasLongEdgeCap = _exportResolution.value
        )
        filterComplexParts.addAll(videoStages)

        // ── Audio filter stages (multi-track adelay + volume mixing) ──
        var finalAudioLabel: String? = null
        val mainVideoMuted = operations.filterIsInstance<EditOperation.MuteClip>().find { it.index == 0 }?.isMuted ?: false
        val effectiveAudioMuted = audioMuted || (mainVideoMuted && audioInputIndices.isEmpty())

        if (effectiveAudioMuted) {
            // No audio output — will use -an flag
        } else if (audioInputIndices.isNotEmpty()) {
            val mixInputLabels = mutableListOf<String>()

            for ((idx, pair) in audioInputIndices.withIndex()) {
                val (inputIdx, audioOp) = pair
                val trackLabel = "[a$idx]"
                val filters = mutableListOf<String>()

                val internalStartSec = audioOp.internalStartMs / 1000.0
                var internalEndSec = if (audioOp.internalEndMs > 0L) audioOp.internalEndMs / 1000.0 else Double.MAX_VALUE

                if (audioOp.startTimeMs != null && audioOp.endTimeMs != null) {
                    val timelineDurationSec = (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                    val maxEndSec = internalStartSec + timelineDurationSec
                    if (maxEndSec < internalEndSec) {
                        internalEndSec = maxEndSec
                    }
                }

                if (internalStartSec > 0.0 || internalEndSec != Double.MAX_VALUE) {
                    if (internalEndSec != Double.MAX_VALUE) {
                        filters.add("atrim=start=$internalStartSec:end=$internalEndSec,asetpts=PTS-STARTPTS")
                    } else {
                        filters.add("atrim=start=$internalStartSec,asetpts=PTS-STARTPTS")
                    }
                }
                if (audioOp.fadeInDurationMs > 0L) {
                    val fadeSec = audioOp.fadeInDurationMs / 1000.0
                    filters.add("afade=t=in:st=0:d=$fadeSec")
                }
                if (audioOp.fadeOutDurationMs > 0L) {
                    val fadeSec = audioOp.fadeOutDurationMs / 1000.0
                    val clipDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                        (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                    } else if (audioOp.internalEndMs > 0L) {
                        (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                    } else {
                        (audioOp.originalDurationMs - audioOp.internalStartMs) / 1000.0
                    }
                    val fadeOutStart = maxOf(0.0, clipDur - fadeSec)
                    filters.add("afade=t=out:st=$fadeOutStart:d=$fadeSec")
                }
                var delayMs = audioOp.startTimeMs ?: 0L
                if (delayMs < 0) delayMs = 0L
                if (delayMs > 0) {
                    filters.add("adelay=$delayMs|$delayMs")
                }
                
                if (audioOp.volume != 1.0f) {
                    filters.add("volume=${audioOp.volume}")
                }

                if (filters.isNotEmpty()) {
                    filterComplexParts.add("[$inputIdx:a]${filters.joinToString(",")}$trackLabel")
                    mixInputLabels.add(trackLabel)
                } else {
                    mixInputLabels.add("[$inputIdx:a]")
                }
            }

            // Mix original audio with all background tracks
            if (hasAudio && !mainVideoMuted && !audioInputIndices.any { it.second.removeOriginalAudio }) {
                var mainAudioRef = "[0:a]"
                val duckingCount = audioInputIndices.count { it.second.ducking }
                
                if (duckingCount > 0) {
                    val splits = (0..duckingCount).map { "[main_split_$it]" }
                    filterComplexParts.add("${mainAudioRef}asplit=${duckingCount + 1}${splits.joinToString("")}")
                    mainAudioRef = splits[0]
                    
                    var splitIdx = 1
                    for (i in mixInputLabels.indices) {
                        val op = audioInputIndices[i].second
                        if (op.ducking) {
                            val bgLabel = mixInputLabels[i]
                            val sidechainLabel = splits[splitIdx++]
                            val duckedLabel = "[ducked_$i]"
                            filterComplexParts.add("${bgLabel}aformat=sample_rates=44100:channel_layouts=stereo[bg_fmt_$i]")
                            filterComplexParts.add("${sidechainLabel}aformat=sample_rates=44100:channel_layouts=stereo[sc_fmt_$i]")
                            filterComplexParts.add("[bg_fmt_$i][sc_fmt_$i]sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500${duckedLabel}")
                            mixInputLabels[i] = duckedLabel
                        }
                    }
                }

                val allInputs = mainAudioRef + mixInputLabels.joinToString("")
                val totalInputs = 1 + mixInputLabels.size
                filterComplexParts.add("${allInputs}amix=inputs=$totalInputs:duration=longest[outa]")
                finalAudioLabel = "[outa]"
            } else {
                if (mixInputLabels.size == 1) {
                    val singleLabel = mixInputLabels[0]
                    filterComplexParts.add("${singleLabel}aformat=sample_rates=44100:channel_layouts=stereo[outa]")
                    finalAudioLabel = "[outa]"
                } else {
                    val allBg = mixInputLabels.joinToString("")
                    filterComplexParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest[outa]")
                    finalAudioLabel = "[outa]"
                }
            }
        }

        // ── Assemble the command ──
        val hasVideoFilters = videoStages.isNotEmpty() || prepFilters.isNotEmpty() || bgApplied
        val hasAudioFilters = finalAudioLabel != null

        if (hasVideoFilters || hasAudioFilters) {
            var mappedVideoLabel = finalVideoLabel
            if (hasVideoFilters) {
                val fmtLabel = "[fmtv]"
                val finalScaleFilter = exportResolutionScaleFilter()
                val finalScalePart = if (finalScaleFilter != null) "$finalScaleFilter," else ""
                filterComplexParts.add("${finalVideoLabel}${finalScalePart}fps=${_exportFps.value},format=yuv420p${fmtLabel}")
                mappedVideoLabel = fmtLabel
            }
            
            if (_exportAudioOnly.value && hasVideoFilters) {
                filterComplexParts.add("${mappedVideoLabel}nullsink")
            }
            
            cmd.append(filterComplexThreadsArg())
            cmd.append(" -filter_complex \"${filterComplexParts.joinToString(";")}\"")

            if (!_exportAudioOnly.value) {
                if (hasVideoFilters) {
                    cmd.append(" -map \"$mappedVideoLabel\"")
                } else {
                    cmd.append(" -map 0:v")
                }
            }

            if (effectiveAudioMuted) {
                cmd.append(" -an")
            } else if (hasAudioFilters) {
                cmd.append(" -map \"$finalAudioLabel\"")
            } else {
                cmd.append(" -map 0:a?")
            }
        } else {
            if (_exportAudioOnly.value) {
                cmd.append(" -vn")
                if (!effectiveAudioMuted) {
                    cmd.append(" -map 0:a?")
                }
            }
            if (effectiveAudioMuted) {
                cmd.append(" -an")
            }
        }

        // Codecs
        if (_exportAudioOnly.value) {
            if (!effectiveAudioMuted) {
                cmd.append(" -c:a libmp3lame -b:a 192k")
            }
        } else {
            val bitrate = exportBitrate(sourceVideoHeight)
            val defaultEncoder = context?.getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
                ?.getString("default_encoder", "auto") ?: "hardware"
            val videoCodec = if (defaultEncoder == "software") "-c:v libx264 -pix_fmt yuv420p" else "-c:v h264_mediacodec -pix_fmt yuv420p -b:v $bitrate"
            // If there are no video filters, we still need to apply the scale and fps output params
            if (!hasVideoFilters) {
                val vfScale = exportResolutionScaleFilterForVf(sourceVideoWidth, sourceVideoHeight)
                if (vfScale != null) {
                    cmd.append(" $videoCodec -vf $vfScale -r ${_exportFps.value}")
                } else {
                    // Original: no scale, keep the source resolution
                    cmd.append(" $videoCodec -r ${_exportFps.value}")
                }
            } else {
                cmd.append(" $videoCodec")
            }
            
            if (!effectiveAudioMuted) {
                cmd.append(" -c:a aac")
            }
        }

        if (outputDuration != null) {
            cmd.append(" -t $outputDuration")
        }
        cmd.append(" \"$outputFilePath\"")

        val finalCommand = cmd.toString()
        Log.d(TAG, "Built command: $finalCommand")
        return finalCommand
    }

    /**
     * Build an FFmpeg command for a fast 3-second preview around the playhead.
     * Uses ultrafast preset and high CRF for speed over quality.
     * Skips merge operations (preview is source-only).
     *
     * @param seekPositionMs  Current playhead position in ms.
     */
    fun buildPreviewCommand(
        sourceFilePath: String,
        previewOutputPath: String,
        seekPositionMs: Long,
        fontFilePath: String? = null,
        density: Float = 1.0f,
        context: Context? = null
    ): String? {
        val currentProject = _project.value ?: return null
        val operations = currentProject.operations
            .filterNot { it is EditOperation.Merge } // Skip merge for preview

        if (operations.none { it is EditOperation.Crop || it is EditOperation.CanvasRatio || it is EditOperation.AddText || it is EditOperation.AddBlurBox || it is EditOperation.AddSubtitles }) {
            return null // No visual operations to preview
        }

        val videoOps = operations.filter { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddBlurBox || it is EditOperation.AddSubtitles }
        val ratioOp = operations.filterIsInstance<EditOperation.CanvasRatio>().lastOrNull()
        // Source frame size, so a CanvasRatio op can compute its canvas.
        var previewSrcW = 0
        var previewSrcH = 0
        try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceFilePath)
                val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val w = wStr?.toIntOrNull() ?: 0
                val h = hStr?.toIntOrNull() ?: 0
                val r = rStr?.toIntOrNull() ?: 0
                if (r == 90 || r == 270) { previewSrcW = h; previewSrcH = w } else { previewSrcW = w; previewSrcH = h }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read source size for preview: ${e.message}")
        }
        val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()

        val cmd = StringBuilder()
        var outputDuration: Double? = null
        if (trimOp != null) {
            val durationSecs = (trimOp.endMs - trimOp.startMs) / 1000.0
            outputDuration = durationSecs
            cmd.append("-ss ${trimOp.startMs / 1000.0} -t $durationSecs -i \"$sourceFilePath\"")
        } else {
            cmd.append("-i \"$sourceFilePath\"")
        }

        // Shaped-text PNG inputs (Myanmar) — same pre-render as export, so
        // the segmented preview shows the same shaped text as the final file.
        val shapedTextInputs = mutableListOf<ShapedTextInput>()
        var shapedInputIndex = 1
        for ((key, rendered) in collectShapedTextImages(videoOps, fontFilePath, density, 1080, context)) {
            cmd.append(" -loop 1 -i \"${rendered.file.absolutePath}\"")
            shapedTextInputs.add(ShapedTextInput(shapedInputIndex, key, rendered.width, rendered.height))
            shapedInputIndex++
        }

        // Build filter_complex for video operations
        val (videoStages, finalLabel) = buildVideoFilterStages(
            operations = videoOps,
            fontFilePath = fontFilePath,
            density = density,
            sourceVideoHeight = 1080,
            shapedTextInputs = shapedTextInputs,
            canvasRatioOp = ratioOp,
            ratioSourceWidth = previewSrcW,
            ratioSourceHeight = previewSrcH,
            canvasLongEdgeCap = _exportResolution.value
        )
        if (videoStages.isNotEmpty()) {
            cmd.append(" -filter_complex \"${videoStages.joinToString(";")}\"")
            cmd.append(" -map \"$finalLabel\" -map 0:a?")
        }

        // Fast preview settings
        cmd.append(" -c:v h264_mediacodec -pix_fmt yuv420p -b:v 1500k -c:a aac")
        if (outputDuration != null) {
            cmd.append(" -t $outputDuration")
        }
        cmd.append(" -y \"$previewOutputPath\"")

        val finalCommand = cmd.toString()
        Log.d(TAG, "Built preview command: $finalCommand")
        return finalCommand
    }

    private fun copyContentUriToTempFile(context: Context, uri: Uri): String? {
        return try {
            val tempFile = File.createTempFile("temp_video", ".mp4", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("VideoEditingViewModel", "Failed to copy content URI: ${e.message}")
            null
        }
    }

    /**
     * Build the FFmpeg concat command for merging videos.
     * FIX: All paths are now quoted to prevent truncation on long package-name paths.
     */
    fun buildMergeCommand(
        context: Context,
        currentVideoPath: String,
        videoUrisToMerge: List<Uri>,
        listFilePath: String,
        outputFilePath: String
    ): String {
        val listContent = StringBuilder()
        listContent.append("file '$currentVideoPath'\n")

        for (uri in videoUrisToMerge) {
            val tempFilePath = copyContentUriToTempFile(context, uri)
            if (tempFilePath != null) {
                listContent.append("file '$tempFilePath'\n")
            } else {
                Log.e("VideoEditingViewModel", "Skipping URI due to copy failure: $uri")
            }
        }

        File(listFilePath).writeText(listContent.toString())

        // Both listFilePath and outputFilePath are quoted
        return "-f concat -safe 0 -i \"$listFilePath\" -c:v copy -c:a copy \"$outputFilePath\""
    }

    fun startExport() {
        updateUiState { it.copy(isExporting = true, exportProgress = 0, exportEncodedSecs = 0.0, errorMessage = null) }
    }

    fun updateExportProgress(progress: Int, encodedSecs: Double = 0.0) {
        // isExporting is set here too, not only in startExport(): if the
        // editor was recreated while an export runs (e.g. opened from
        // the export notification), the progress broadcast is what
        // re-shows the export screen. Harmless during a normal export —
        // startExport() already set it.
        updateUiState { it.copy(isExporting = true, exportProgress = progress.coerceIn(0, 100), exportEncodedSecs = encodedSecs.coerceAtLeast(0.0)) }
    }

    fun finishExport() {
        updateUiState { it.copy(isExporting = false, exportProgress = 100) }
    }

    fun exportError(error: String) {
        updateUiState { it.copy(isExporting = false, errorMessage = error) }
    }

    fun clearError() {
        updateUiState { it.copy(errorMessage = null) }
    }

    fun saveRecipe(projectName: String): EditRecipe? =
        _project.value?.let { EditRecipe.fromVideoProject(projectName, it) }

    fun loadRecipe(recipe: EditRecipe) {
        _project.value = recipe.toVideoProject()
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        updateUiState { it.copy(pendingOperationCount = recipe.operations.size, canUndo = false, canRedo = false) }
    }

    private fun updateUiState(updater: (VideoEditingUiState) -> VideoEditingUiState) {
        _uiState.update(updater)
        if (_undoStack.value.isNotEmpty()) {
            _hasUnsavedEdits.value = true
        }
    }

    private fun getWrappedSubtitleLines(text: String, fontSize: Int = 22, density: Float = 2.75f): List<String> {
        val effectiveDensity = if (density > 0.5f) density else 2.75f
        val avgCharWidth = 0.52f * fontSize * effectiveDensity
        val maxCharsPerLine = (918f / avgCharWidth).toInt().coerceIn(10, 60)

        val rawLines = text.split("\n")
        val resultLines = mutableListOf<String>()
        for (rawLine in rawLines) {
            val trimmedRaw = rawLine.trim()
            if (trimmedRaw.length <= maxCharsPerLine) {
                if (trimmedRaw.isNotEmpty()) resultLines.add(trimmedRaw)
            } else {
                val words = trimmedRaw.split(Regex("\\s+"))
                var currentLine = StringBuilder()
                for (word in words) {
                    if (currentLine.isEmpty()) {
                        currentLine.append(word)
                    } else {
                        if (currentLine.length + 1 + word.length <= maxCharsPerLine) {
                            currentLine.append(" ").append(word)
                        } else {
                            resultLines.add(currentLine.toString().trim())
                            currentLine = StringBuilder(word)
                        }
                    }
                }
                if (currentLine.isNotEmpty()) {
                    resultLines.add(currentLine.toString().trim())
                }
            }
        }
        return if (resultLines.isEmpty()) listOf(text) else resultLines
    }
}