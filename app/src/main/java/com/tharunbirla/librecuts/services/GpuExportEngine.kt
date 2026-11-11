// The Media3 Transformer APIs are marked @UnstableApi. The opt-in MUST
// use androidx.annotation.OptIn (markerClass = ...), NOT kotlin.OptIn:
// lint's UnsafeOptInUsageError detector only reads the "markerClass"
// attribute, so kotlin.OptIn (whose attribute is "value") is silently
// ignored — verified by decompiling the detector (CI #23 class-level
// kotlin.OptIn and CI #24 file-level kotlin.OptIn both left all 44
// errors in place). Applied on the class (the detector walks up to the
// enclosing class from every usage in this file).
package com.tharunbirla.librecuts.services

import androidx.annotation.OptIn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.VideoEncoderSettings
import com.tharunbirla.librecuts.utils.ShapedTextRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crop as the editor stores it: an aspect-ratio preset ("16:9", "9:16",
 * "1:1", "Custom", …) plus the custom cut rectangle in frame fractions
 * (top-left origin, same as the FFmpeg crop filter). Resolved to concrete
 * fractions inside the engine, so the aspect presets match the FFmpeg
 * output exactly.
 */
data class GpuCropSpec(
    val aspectRatio: String,
    val xFraction: Float,
    val yFraction: Float,
    val wFraction: Float,
    val hFraction: Float
)

/**
 * Everything the GPU export needs. Built by the ViewModel from the project
 * (see [com.tharunbirla.librecuts.viewmodels.VideoEditingViewModel.buildGpuExportParams]);
 * the Activity fills in [outputPath] (the service temp file) before
 * starting [ExportService].
 */
data class GpuExportParams(
    val sourcePath: String,
    val outputPath: String,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val speed: Float = 1f,
    /** Short-edge target, same values as the export resolution setting (0 = Original). */
    val targetRes: Int = 0,
    val targetFps: Int = 30,
    val crop: GpuCropSpec? = null,
    /** Canvas ratio composition (Phase 2), or null for the plain path. */
    val canvas: GpuCanvasSpec? = null,
    /** Text / subtitle / image overlays (Phase 3), in project order. */
    val texts: List<GpuTextSpec> = emptyList(),
    val subtitles: List<GpuSubtitleSpec> = emptyList(),
    val images: List<GpuImageSpec> = emptyList(),
    /** Mirror the video (MirrorMain). */
    val mirror: Boolean = false,
    /** Gson JSON of [GpuOverlaySpecs] — the Intent transport. The service parses it back into the lists above; the engine never reads this field. */
    val overlaysJson: String? = null
)

/**
 * Canvas ratio composition (Phase 2): the output canvas aspect
 * (width/height, from CanvasRatio.ratioValue), the background blur
 * strength preset (0 = black background — no CanvasBackground op, or a
 * black colour), and the pinch-zoom scale (CanvasRatio.effectiveScale).
 * The engine derives the canvas size, the sharp-frame scale and the
 * Gaussian sigma from these plus the probed source size.
 */
data class GpuCanvasSpec(
    val aspectRatio: Float,
    val blurRadius: Int,
    val scale: Float = 1f
)

// ── Overlay specs (Phase 3) ────────────────────────────────────────────
// One spec per op, built by the ViewModel and carried to the service as
// a single Gson JSON string (see GpuOverlaySpecs). All fields have
// defaults so Gson can materialise them; the ViewModel always sets
// every field explicitly. Positions are FRACTIONS of the draw frame
// (the canvas, or the uncropped source — see buildOverlays), so they
// survive the final resolution scale unchanged.

/** One AddText op. The bitmap is pre-rendered by the engine with ShapedTextRenderer (the same call the FFmpeg path uses), so Myanmar shaping, borders and alignment match the FFmpeg export exactly. */
data class GpuTextSpec(
    val text: String = "",
    val fontSize: Int = 0,
    val colorHex: String = "#FFFFFF",
    val fontPath: String? = null,
    val borderThickness: Int = 0,
    val borderColorHex: String = "#000000",
    val align: String = "center",
    val lineSpacing: Float = 0f,
    /** Frame point (0..1, top-left origin) where the anchor sits. */
    val relX: Float = 0.5f,
    val relY: Float = 0.5f,
    /** NDC (-1..1) point of the text box pinned at (relX, relY). */
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val opacity: Float = 1f
)

/** One subtitle cue, pre-wrapped lines joined with "\n". */
data class GpuSubtitleCue(
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val text: String = ""
)

/** One AddSubtitles op. The font size, margins and anchors are computed by the engine from these raw values with the same formulas the FFmpeg path uses (density-scaled, refHeight = source display height). */
data class GpuSubtitleSpec(
    val cues: List<GpuSubtitleCue> = emptyList(),
    val fontSize: Int = 22,
    val colorHex: String = "#FFFFFF",
    val fontPath: String? = null,
    val positionName: String = "BOTTOM_CENTER",
    val hasCustomPosition: Boolean = false,
    val relX: Float = 0.5f,
    val relY: Float = 0.5f,
    val align: String = "center"
)

/** One AddImageOverlay op (static image only — GIF/video overlays stay on FFmpeg). */
data class GpuImageSpec(
    val uri: String = "",
    /** Centre of the image box, fractions of the draw frame. */
    val relX: Float = 0.5f,
    val relY: Float = 0.5f,
    /** Displayed size, fractions of the draw frame. */
    val relW: Float = 1f,
    val relH: Float = 1f,
    val rotationDeg: Float = 0f,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val opacity: Float = 1f
)

/** The whole overlay set, serialised to one JSON string for the Intent. */
data class GpuOverlaySpecs(
    val texts: List<GpuTextSpec> = emptyList(),
    val subtitles: List<GpuSubtitleSpec> = emptyList(),
    val images: List<GpuImageSpec> = emptyList(),
    val mirror: Boolean = false
)

/**
 * GPU export pipeline (Phase 1) — Media3 Transformer: hardware decode
 * (MediaCodec) + OpenGL effects + hardware encode. This is the same
 * architecture CapCut uses; FFmpeg's filters are CPU-bound, which is why
 * plain exports (trim / speed / crop / resize / fps) were slow even though
 * the encoder was already hardware.
 *
 * Phase 1 scope: single video clip, plain ops only (Trim, SpeedMain
 * without a reverse proxy, Crop). Anything else (text, blur boxes,
 * subtitles, audio tracks, multi-clip merges) stays on the FFmpeg
 * path — the router in the ViewModel decides.
 *
 * Phase 2 (canvas ratio): a centred CanvasRatio op with a blur or
 * black background also runs on the GPU — GaussianBlurWithFrameOverlaid
 * (blurred frame + sharp frame scaled to the fitted size) followed by
 * a Presentation that crops/scales to the exact canvas size. Colour /
 * image backgrounds, a dragged (off-centre) canvas, and the remaining
 * ops still fall back to FFmpeg.
 *
 * Phase 3 (overlays): AddText, AddSubtitles and AddImageOverlay also
 * run on the GPU — text/cues are pre-rendered to bitmaps with
 * ShapedTextRenderer (the same call the FFmpeg path uses, so shaping,
 * borders and alignment match) and composited with OverlayEffect.
 * MirrorMain flips via ScaleAndRotateTransformation. Timed overlays
 * (start/end set, or any subtitles) need no trim and 1x speed — the
 * overlay timestamps are in the source timeline, so a trim or a
 * speed change would shift them; always-on overlays are fine with
 * either. Chain order mirrors the FFmpeg graph: overlays are drawn
 * on the draw frame — after the canvas Presentation, but BEFORE a
 * crop (their coordinates are relative to the uncropped source).
 * Still on FFmpeg: blur boxes, reverse, merges/transitions, masks,
 * background audio, colour filters, Adjust, mute.
 *
 * Progress is polled with [Transformer.getProgress]; the service maps it
 * onto the same onProgress / notification / broadcast wiring the FFmpeg
 * path uses, so the export screen, notification and done screen are
 * unchanged.
 */
@OptIn(markerClass = [UnstableApi::class])
class GpuExportEngine(private val context: Context) {

    sealed interface Result {
        data class Success(val outputPath: String) : Result
        data class Failure(val error: String) : Result
        object Cancelled : Result
    }

    private val TAG = "GpuExportEngine"

    @Volatile
    private var transformer: Transformer? = null
    private val cancelled = AtomicBoolean(false)

    /**
     * Runs the GPU export. [onProgress] gets (percent 0–100, encoded
     * output seconds) — the same shape as the FFmpeg engine's callback.
     */
    suspend fun export(
        params: GpuExportParams,
        onProgress: (progress: Int, encodedSecs: Double) -> Unit
    ): Result {
        cancelled.set(false)

        // ── Probe the source (dimensions, rotation, duration) ──────────
        val retriever = MediaMetadataRetriever()
        val srcW: Int
        val srcH: Int
        val srcDurationMs: Long
        val rotation: Int
        try {
            retriever.setDataSource(params.sourcePath)
            srcW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            srcH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            srcDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to probe source", e)
            return Result.Failure("Could not read the source video: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
        if (srcW <= 0 || srcH <= 0) {
            return Result.Failure("Could not read the source video size")
        }
        // Rotation metadata (phone clips are often 90/270): the pipeline
        // rotates the frames, so use the displayed dimensions here.
        val dispW = if (rotation == 90 || rotation == 270) srcH else srcW
        val dispH = if (rotation == 90 || rotation == 270) srcW else srcH

        // ── Resolve the crop to concrete fractions ─────────────────────
        val cropFrac = params.crop?.let { resolveCropFractions(it, dispW, dispH) }

        // ── Output duration (for progress → encoded seconds) ───────────
        val trimEnd = if (params.trimEndMs > 0) params.trimEndMs else srcDurationMs
        val trimmedMs = (trimEnd - params.trimStartMs).coerceAtLeast(0L)
        val speed = if (params.speed > 0f) params.speed else 1f
        val outputDurationSecs = trimmedMs / 1000.0 / speed

        // ── Build the MediaItem (trim) ─────────────────────────────────
        val clippingBuilder = MediaItem.ClippingConfiguration.Builder()
        var hasClip = false
        if (params.trimStartMs > 0) {
            clippingBuilder.setStartPositionMs(params.trimStartMs)
            hasClip = true
        }
        if (params.trimEndMs > 0 && params.trimEndMs < srcDurationMs) {
            clippingBuilder.setEndPositionMs(params.trimEndMs)
            hasClip = true
        }
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.fromFile(File(params.sourcePath)))
        if (hasClip) mediaItemBuilder.setClippingConfiguration(clippingBuilder.build())
        val mediaItem = mediaItemBuilder.build()

        // ── Build the video effects ───────────────────────────────────
        // Chain order mirrors the FFmpeg graph:
        //   1. Mirror (flips the source; everything after sees the flip).
        //   2. Overlays — but WHERE depends on the path. With a canvas
        //      they go AFTER the canvas Presentation (drawn on the
        //      canvas, like FFmpeg). Without one they go FIRST, before
        //      the crop (their coordinates are relative to the
        //      uncropped source, like FFmpeg's "overlays first").
        //   3. Canvas (blur + Presentation) or Crop + Presentation.
        // The overlay scale is always a fraction of the DRAW frame
        // (canvas at Original size, or the source), so it is the same
        // formula in both positions — the downstream Presentation
        // scales overlays and video together.
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        val canvas = params.canvas
        if (params.mirror) {
            videoEffects.add(
                ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
            )
        }
        // The draw frame: canvas at Original size, else the source.
        val (drawW, drawH) = if (canvas != null) {
            canvasOutputSize(dispW, dispH, canvas.aspectRatio, 0)
        } else {
            dispW to dispH
        }
        val overlays = buildOverlays(params, drawW, drawH, dispH)
        if (canvas == null && overlays.isNotEmpty()) {
            videoEffects.add(OverlayEffect(overlays))
        }
        // Canvas ratio (Phase 2): the composition is two built-in
        // effects. GaussianBlurWithFrameOverlaid blurs the whole frame
        // and overlays the sharp frame scaled to the fitted size
        // (uniform scale — the fit preserves aspect, so X == Y); the
        // Presentation then crops/scales to the exact canvas size.
        // Because the Presentation crops the CENTRE, the blurred
        // background ends up being the cover-cropped frame blurred —
        // exactly what the FFmpeg canvas does (its blur bg is the
        // cover crop, blurred at quarter size). The blur is skipped
        // when the fit covers the whole canvas (aspect matches): there
        // is no background visible to blur.
        // Otherwise the plain Phase 1 path: crop, then scale.
        if (canvas != null) {
            val (canvasW, canvasH) = canvasOutputSize(dispW, dispH, canvas.aspectRatio, params.targetRes)
            // Cover scale of the Presentation (max) and fit scale (min).
            val cover = maxOf(canvasW / dispW.toFloat(), canvasH / dispH.toFloat())
            val fit = minOf(canvasW / dispW.toFloat(), canvasH / dispH.toFloat())
            val sharp = (fit / cover * canvas.scale).coerceIn(0.01f, 10f)
            if (canvas.blurRadius > 0 && fit < cover) {
                // Box-blur radius R (canvas px) has variance R²/3, so
                // the matching Gaussian sigma is R/√3 ≈ 0.577R; the blur
                // runs on the input frame, so divide by the cover scale.
                val sigma = (0.577f * canvas.blurRadius / cover).coerceAtLeast(0.5f)
                videoEffects.add(GaussianBlurWithFrameOverlaid(sigma, sharp, sharp))
            }
            videoEffects.add(
                Presentation.createForWidthAndHeight(
                    canvasW, canvasH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )
            )
            if (overlays.isNotEmpty()) {
                videoEffects.add(OverlayEffect(overlays))
            }
        } else {
            cropFrac?.let { (x, y, w, h) ->
                // Crop takes normalized device coordinates (-1..1, y up);
                // our fractions are top-left origin, so flip y.
                val left = 2f * x - 1f
                val right = 2f * (x + w) - 1f
                val top = 1f - 2f * y
                val bottom = 1f - 2f * (y + h)
                if (left > -1f || right < 1f || bottom > -1f || top < 1f) {
                    videoEffects.add(Crop(left, right, bottom, top))
                }
            }
            presentationHeight(dispW, dispH, cropFrac, params.targetRes)?.let { height ->
                videoEffects.add(Presentation.createForHeight(height))
            }
        }

        // ── Build the EditedMediaItem (fps cap, speed) ────────────────
        val editedBuilder = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            // setFrameRate is a MAX: it only drops frames, never invents
            // them — the router guarantees the target fps is not above the
            // effective source fps, so this always honours the setting.
            .setFrameRate(params.targetFps)
        if (speed != 1f) {
            // setSpeed (1.9+) retimes BOTH audio and video, so they stay
            // in sync — unlike the old SpeedChangeEffect (video only).
            val provider = object : SpeedProvider {
                override fun getSpeed(presentationTimeUs: Long): Float = speed
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
            }
            editedBuilder.setSpeed(provider)
        }
        val editedMediaItem = editedBuilder.build()

        // ── Build the Transformer (H.264 + AAC, our bitrate table) ─────
        val bitrateBps = bitrateBps(params.targetRes, dispH)
        val deferred = CompletableDeferred<Result>()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (cancelled.get()) deferred.complete(Result.Cancelled)
                else deferred.complete(Result.Success(params.outputPath))
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                if (cancelled.get()) deferred.complete(Result.Cancelled)
                else deferred.complete(
                    Result.Failure(exportException.message ?: "GPU export failed")
                )
            }
        }
        val tf = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(bitrateBps).build()
                    )
                    .build()
            )
            .addListener(listener)
            .build()
        transformer = tf

        try {
            // Transformer must be started from a thread with a Looper;
            // the service runs on IO, so start it on Main. The actual
            // encode runs on Transformer's own threads.
            withContext(Dispatchers.Main) {
                tf.start(editedMediaItem, params.outputPath)
            }

            val progressHolder = ProgressHolder()
            // Poll the progress until the listener completes. The poller
            // is a child of this block, so it is cancelled when the
            // listener's result arrives (or the export is cancelled).
            val result = kotlinx.coroutines.coroutineScope {
                val pollJob = launch {
                    while (isActive) {
                        try {
                            if (tf.getProgress(progressHolder) ==
                                Transformer.PROGRESS_STATE_AVAILABLE
                            ) {
                                val pct = progressHolder.progress.coerceIn(0, 100)
                                onProgress(pct, pct / 100.0 * outputDurationSecs)
                            }
                        } catch (_: Exception) {
                            // getProgress can throw if the transformer is
                            // gone; the listener result is authoritative.
                        }
                        delay(250)
                    }
                }
                val r = deferred.await()
                pollJob.cancel()
                r
            }
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Our own coroutine was cancelled (service torn down):
            // stop the transformer too, then rethrow as required.
            try { tf.cancel() } catch (_: Exception) { }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GPU export exception", e)
            return if (cancelled.get()) Result.Cancelled
            else Result.Failure(e.message ?: "GPU export failed")
        } finally {
            transformer = null
        }
    }

    /** Cancels the in-flight Transformer export (called from the service). */
    fun cancel() {
        cancelled.set(true)
        try { transformer?.cancel() } catch (_: Exception) { }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * A static bitmap shown only inside [startUs, endUs] (null = unbounded).
     * Visibility is driven by the per-frame alpha — the texture stays the
     * same size, so the GL pipeline never reconfigures. The settings are
     * built once from the bitmap size and the spec's position.
     */
    private class TimedBitmapOverlay(
        private val bitmap: Bitmap,
        private val startUs: Long?,
        private val endUs: Long?,
        private val settings: StaticOverlaySettings
    ) : BitmapOverlay() {
        private val offSettings: StaticOverlaySettings = StaticOverlaySettings.Builder()
            .setAlphaScale(0f)
            .setBackgroundFrameAnchor(
                settings.backgroundFrameAnchor.first,
                settings.backgroundFrameAnchor.second
            )
            .setOverlayFrameAnchor(
                settings.overlayFrameAnchor.first,
                settings.overlayFrameAnchor.second
            )
            .setScale(settings.scale.first, settings.scale.second)
            .setRotationDegrees(settings.rotationDegrees)
            .build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val on = (startUs == null || presentationTimeUs >= startUs) &&
                (endUs == null || presentationTimeUs < endUs)
            return if (on) settings else offSettings
        }
    }

    /** Frame point (0..1, top-left origin) → NDC anchor (-1..1, y up). */
    private fun bgAnchor(relX: Float, relY: Float): Pair<Float, Float> =
        (2f * relX - 1f) to (1f - 2f * relY)

    private fun overlaySettings(
        bitmapW: Int,
        bitmapH: Int,
        drawW: Int,
        drawH: Int,
        bg: Pair<Float, Float>,
        overlayAnchor: Pair<Float, Float>,
        rotationDeg: Float = 0f,
        alpha: Float = 1f
    ): StaticOverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(bg.first, bg.second)
        .setOverlayFrameAnchor(overlayAnchor.first, overlayAnchor.second)
        // Scale is a fraction of the draw frame — the downstream
        // Presentation scales overlay and video together, so the
        // fraction is the same on the output frame.
        .setScale(bitmapW / drawW.toFloat(), bitmapH / drawH.toFloat())
        .setRotationDegrees(rotationDeg)
        .setAlphaScale(alpha.coerceIn(0f, 1f))
        .build()

    /**
     * Builds the OverlayEffect overlays for the text / subtitle / image
     * specs, in project order. Text and cues are pre-rendered with
     * ShapedTextRenderer — the exact call the FFmpeg path uses, so the
     * shaping, border, alignment and font match that export.
     *
     * [drawW]/[drawH] is the frame the overlays are positioned on
     * (canvas at Original size, or the source); [srcH] is the source
     * display height, which the subtitle font formula uses as its
     * reference height, mirroring the FFmpeg path.
     */
    private fun buildOverlays(
        params: GpuExportParams,
        drawW: Int,
        drawH: Int,
        srcH: Int
    ): List<androidx.media3.effect.TextureOverlay> {
        val out = mutableListOf<androidx.media3.effect.TextureOverlay>()
        for (spec in params.texts) {
            if (spec.text.isEmpty() || spec.fontSize <= 0) continue
            val rendered = try {
                ShapedTextRenderer.renderTextPng(
                    context, spec.text, spec.fontPath, spec.fontSize,
                    spec.colorHex, spec.borderThickness, spec.borderColorHex,
                    spec.align, spec.fontSize + spec.lineSpacing
                )
            } catch (e: Exception) {
                Log.w(TAG, "Text render failed: ${e.message}")
                null
            } ?: continue
            val bitmap = try {
                BitmapFactory.decodeFile(rendered.file.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Text bitmap decode failed: ${e.message}")
                null
            } ?: continue
            out.add(
                TimedBitmapOverlay(
                    bitmap,
                    spec.startMs?.let { it * 1000L },
                    spec.endMs?.let { it * 1000L },
                    overlaySettings(
                        rendered.width, rendered.height, drawW, drawH,
                        bgAnchor(spec.relX, spec.relY),
                        spec.anchorX to spec.anchorY,
                        alpha = spec.opacity
                    )
                )
            )
        }
        // Subtitles: one overlay per cue (each cue keeps its own
        // fixed-size bitmap; visibility is the per-frame alpha).
        val density = try {
            context.resources.displayMetrics.density
        } catch (_: Exception) {
            1f
        }
        val effDensity = if (density > 0.5f) density else 2.75f
        for (sub in params.subtitles) {
            val scaledFontSize =
                (sub.fontSize * effDensity * (srcH / 1080f)).toInt().coerceAtLeast(16)
            val marginY = (24f * effDensity * (srcH / 1080f)).toInt()
            for (cue in sub.cues) {
                if (cue.text.isEmpty()) continue
                val rendered = try {
                    ShapedTextRenderer.renderTextPng(
                        context, cue.text, sub.fontPath, scaledFontSize,
                        sub.colorHex, align = sub.align,
                        targetLineHeightPx = scaledFontSize * 1.25f
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Subtitle render failed: ${e.message}")
                    null
                } ?: continue
                val bitmap = try {
                    BitmapFactory.decodeFile(rendered.file.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Subtitle bitmap decode failed: ${e.message}")
                    null
                } ?: continue
                val (bg, overlayAnchor) = subtitleAnchors(sub, drawW, drawH, marginY)
                out.add(
                    TimedBitmapOverlay(
                        bitmap,
                        cue.startMs * 1000L,
                        cue.endMs * 1000L,
                        overlaySettings(
                            rendered.width, rendered.height, drawW, drawH,
                            bg, overlayAnchor
                        )
                    )
                )
            }
        }
        for (spec in params.images) {
            if (spec.uri.isEmpty()) continue
            val bitmap = try {
                context.contentResolver.openInputStream(Uri.parse(spec.uri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Image overlay load failed: ${e.message}")
                null
            } ?: continue
            // FFmpeg's rotate is clockwise, Media3's is counter-clockwise.
            out.add(
                TimedBitmapOverlay(
                    bitmap,
                    spec.startMs?.let { it * 1000L },
                    spec.endMs?.let { it * 1000L },
                    StaticOverlaySettings.Builder()
                        .setBackgroundFrameAnchor(
                            2f * spec.relX - 1f, 1f - 2f * spec.relY
                        )
                        .setOverlayFrameAnchor(0f, 0f)
                        .setScale(spec.relW, spec.relH)
                        .setRotationDegrees(-spec.rotationDeg)
                        .setAlphaScale(spec.opacity.coerceIn(0f, 1f))
                        .build()
                )
            )
        }
        return out
    }

    /**
     * Subtitle anchors, mirroring the FFmpeg PNG overlay positions
     * exactly (24px side margin, marginY bottom margin, both in draw
     * px). The background anchor is the frame point the overlay's
     * anchor sits at; the overlay anchor is the point of the cue block.
     */
    private fun subtitleAnchors(
        sub: GpuSubtitleSpec,
        drawW: Int,
        drawH: Int,
        marginY: Int
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        if (sub.hasCustomPosition) {
            return bgAnchor(sub.relX, sub.relY) to (0f to 0f)
        }
        val m24x = 2f * 24f / drawW
        val m24y = 2f * 24f / drawH
        val mBottom = 2f * marginY / drawH
        return when (sub.positionName) {
            "TOP_LEFT" -> (-1f + m24x to 1f - m24y) to (-1f to 1f)
            "TOP_CENTER", "CENTER_TOP" -> (0f to 1f - m24y) to (0f to 1f)
            "TOP_RIGHT" -> (1f - m24x to 1f - m24y) to (1f to 1f)
            "CENTER_LEFT" -> (-1f + m24x to 0f) to (-1f to 0f)
            "CENTER" -> (0f to 0f) to (0f to 0f)
            "CENTER_RIGHT" -> (1f - m24x to 0f) to (1f to 0f)
            "BOTTOM_LEFT" -> (-1f + m24x to -1f + mBottom) to (-1f to -1f)
            "BOTTOM_CENTER", "CENTER_BOTTOM" -> (0f to -1f + mBottom) to (0f to -1f)
            "BOTTOM_RIGHT" -> (1f - m24x to -1f + mBottom) to (1f to -1f)
            else -> (0f to -1f + mBottom) to (0f to -1f)
        }
    }

    /**
     * Mirrors the FFmpeg crop: custom bounds use the stored fractions;
     * aspect presets are a centre crop to that aspect (FFmpeg's crop
     * without x/y centres by default). Returns null when there is no crop.
     */
    private fun resolveCropFractions(
        crop: GpuCropSpec,
        srcW: Int,
        srcH: Int
    ): FloatArray? {
        val hasCustomBounds = crop.aspectRatio == "Custom" ||
            crop.xFraction > 0f || crop.yFraction > 0f ||
            crop.wFraction < 1f || crop.hFraction < 1f
        if (hasCustomBounds) {
            return floatArrayOf(crop.xFraction, crop.yFraction, crop.wFraction, crop.hFraction)
        }
        val (aw, ah) = when (crop.aspectRatio) {
            "16:9" -> 16f to 9f
            "9:16" -> 9f to 16f
            "1:1" -> 1f to 1f
            else -> return null
        }
        val srcAspect = srcW.toFloat() / srcH
        val targetAspect = aw / ah
        return if (srcAspect > targetAspect) {
            // Too wide: crop the width, centred.
            val wF = targetAspect / srcAspect
            floatArrayOf((1f - wF) / 2f, 0f, wF, 1f)
        } else {
            // Too tall: crop the height, centred.
            val hF = srcAspect / targetAspect
            floatArrayOf(0f, (1f - hF) / 2f, 1f, hF)
        }
    }

    /**
     * Final canvas size for a CanvasRatio op, matching the FFmpeg
     * canvas + final resolution scale exactly: the short edge is the
     * export resolution (or the source long edge for Original), and the
     * other edge follows the canvas aspect. Even-rounded for H.264.
     */
    private fun canvasOutputSize(srcW: Int, srcH: Int, ratio: Float, targetRes: Int): Pair<Int, Int> {
        val (w, h) = if (targetRes > 0) {
            val s = targetRes
            if (ratio >= 1f) Math.round(s * ratio) to s
            else s to Math.round(s / ratio)
        } else {
            val longEdge = maxOf(srcW, srcH)
            if (ratio >= 1f) longEdge to Math.round(longEdge / ratio)
            else Math.round(longEdge * ratio) to longEdge
        }
        return (w / 2 * 2).coerceAtLeast(2) to (h / 2 * 2).coerceAtLeast(2)
    }

    /**
     * Output height for Presentation.createForHeight, mirroring the
     * FFmpeg scale filter: the resolution value is the SHORT edge
     * (landscape → height = res, portrait → width = res). The crop is
     * applied first, so the orientation is decided on the cropped frame.
     * Returns null for Original (no scaling at all).
     */
    private fun presentationHeight(
        srcW: Int,
        srcH: Int,
        cropFrac: FloatArray?,
        targetRes: Int
    ): Int? {
        if (targetRes <= 0) return null
        val cw = srcW * (cropFrac?.get(2) ?: 1f)
        val ch = srcH * (cropFrac?.get(3) ?: 1f)
        val h = if (cw >= ch) targetRes.toFloat() else targetRes * ch / cw
        // H.264 needs even dimensions.
        return (h.toInt() / 2 * 2).coerceAtLeast(2)
    }

    /**
     * Same bitrate table as the FFmpeg path (VideoEditingViewModel
     * exportBitrate): for Original, the source height decides.
     */
    private fun bitrateBps(targetRes: Int, srcH: Int): Int {
        val res = if (targetRes > 0) targetRes else srcH
        return when (res) {
            2160 -> 30_000_000
            1440 -> 16_000_000
            1080 -> 8_000_000
            720 -> 5_000_000
            480 -> 2_000_000
            else -> 1_000_000
        }
    }
}
