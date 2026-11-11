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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.VideoEncoderSettings
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
    val canvas: GpuCanvasSpec? = null
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
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        val canvas = params.canvas
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
