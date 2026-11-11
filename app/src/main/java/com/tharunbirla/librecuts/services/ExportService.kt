package com.tharunbirla.librecuts.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tharunbirla.librecuts.MainActivity
import com.tharunbirla.librecuts.R
import com.tharunbirla.librecuts.VideoEditingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class ExportService : Service() {

    private val TAG = "ExportService"
    private val CHANNEL_ID = "ExportChannel"
    private val NOTIFICATION_ID = 1001

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var ffmpegEngine: FFmpegRenderEngine
    private lateinit var gpuEngine: GpuExportEngine
    /** Set by the Cancel action (a separate onStartCommand call) so the
     * watchdog and the GPU→FFmpeg fallback can tell a user cancel apart
     * from a stall. Reset at the start of every export. */
    private val userCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        const val ACTION_EXPORT_PROGRESS = "com.tharunbirla.librecuts.ACTION_EXPORT_PROGRESS"
        const val ACTION_EXPORT_SUCCESS = "com.tharunbirla.librecuts.ACTION_EXPORT_SUCCESS"
        const val ACTION_EXPORT_FAILURE = "com.tharunbirla.librecuts.ACTION_EXPORT_FAILURE"
        /** Sent by the editor's Cancel button: stops whichever engine is running. */
        const val ACTION_CANCEL_EXPORT = "com.tharunbirla.librecuts.ACTION_CANCEL_EXPORT"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_ENCODED_SECS = "extra_encoded_secs"
        const val EXTRA_SAVED_URI = "extra_saved_uri"
        const val EXTRA_ERROR = "extra_error"

        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TEMP_OUTPUT_PATH = "extra_temp_output_path"
        const val EXTRA_CONCAT_FILE_PATH = "extra_concat_file_path"
        const val EXTRA_TOTAL_DURATION_SECS = "extra_total_duration_secs"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"

        /** GPU fast path (Media3 Transformer) — set by the editor when the router picks it. */
        const val EXTRA_USE_GPU = "extra_use_gpu"
        const val EXTRA_GPU_SOURCE_PATH = "extra_gpu_source_path"
        const val EXTRA_GPU_TRIM_START_MS = "extra_gpu_trim_start_ms"
        const val EXTRA_GPU_TRIM_END_MS = "extra_gpu_trim_end_ms"
        const val EXTRA_GPU_SPEED = "extra_gpu_speed"
        const val EXTRA_GPU_TARGET_RES = "extra_gpu_target_res"
        const val EXTRA_GPU_TARGET_FPS = "extra_gpu_target_fps"
        const val EXTRA_GPU_CROP_ASPECT = "extra_gpu_crop_aspect"
        const val EXTRA_GPU_CROP_X = "extra_gpu_crop_x"
        const val EXTRA_GPU_CROP_Y = "extra_gpu_crop_y"
        const val EXTRA_GPU_CROP_W = "extra_gpu_crop_w"
        const val EXTRA_GPU_CROP_H = "extra_gpu_crop_h"
        const val EXTRA_GPU_CANVAS_ASPECT = "extra_gpu_canvas_aspect"
        const val EXTRA_GPU_CANVAS_BLUR = "extra_gpu_canvas_blur"
        const val EXTRA_GPU_CANVAS_SCALE = "extra_gpu_canvas_scale"
        const val EXTRA_GPU_OVERLAYS = "extra_gpu_overlays"
        /** FFmpeg command the service falls back to if the GPU export
         * fails or stalls (same temp output — the command starts with
         * -y, so a partial GPU file is overwritten safely). */
        const val EXTRA_FALLBACK_COMMAND = "extra_fallback_command"
    }

    /** One outcome shape for both engines, so the save / notify / broadcast tail is shared. */
    private sealed interface ExportOutcome {
        data class Success(val outputPath: String) : ExportOutcome
        data class Failure(val error: String) : ExportOutcome
        object Cancelled : ExportOutcome
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ffmpegEngine = FFmpegRenderEngine(this)
        gpuEngine = GpuExportEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Cancel action: the editor's Cancel button sends this when the
        // GPU engine is running (FFmpegKit.cancel is global, so it covers
        // the FFmpeg path on its own; the Transformer needs a direct call).
        if (intent.action == ACTION_CANCEL_EXPORT) {
            userCancelled.set(true)
            if (::gpuEngine.isInitialized) gpuEngine.cancel()
            serviceScope.launch { ffmpegEngine.cancelAllSessions() }
            return START_NOT_STICKY
        }

        userCancelled.set(false)
        val useGpu = intent.getBooleanExtra(EXTRA_USE_GPU, false)
        val tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH) ?: return START_NOT_STICKY
        val concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)
        val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)
        val fallbackCommand = intent.getStringExtra(EXTRA_FALLBACK_COMMAND)

        // FFmpeg path still needs the command; the GPU path does not use
        // it ("" is a placeholder that is never read — using null here
        // would make the whole val String? and break the exportFinal call
        // below, because the ?: only applies to the else branch).
        val command = if (useGpu) "" else intent.getStringExtra(EXTRA_COMMAND)
            ?: return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification(0, "Exporting..."))

        serviceScope.launch {
            // Watchdog: an export that reports no progress at all within
            // 90 seconds is wedged — it happened on-device (GPU export
            // screen stuck at 0.0% for 4 minutes, toast shown, no error,
            // no finish, no notification progress). Without this the user
            // is left staring at 0% with no way out. On firing, the
            // running engine is cancelled and the outcome is reported as
            // a stall (not a user cancel), so the editor shows a real
            // error and the user can retry instead of waiting forever.
            val startedAtMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val sawAdvance = java.util.concurrent.atomic.AtomicBoolean(false)
            val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = serviceScope.launch {
                while (isActive) {
                    kotlinx.coroutines.delay(10_000)
                    // Keep watching (no return after firing): the fallback
                    // gets its own 90s window via the resets below, and a
                    // fallback stall must be caught too.
                    if (!sawAdvance.get() && !userCancelled.get() &&
                        System.currentTimeMillis() - startedAtMs.get() > 90_000
                    ) {
                        stalled.set(true)
                        try { gpuEngine.cancel() } catch (_: Exception) { }
                        serviceScope.launch { ffmpegEngine.cancelAllSessions() }
                    }
                }
            }
            try {
                // A progress callback counts as "advancing" only once it
                // reports real movement (a wedged engine can keep calling
                // back at a flat 0%).
                val onProgress: (Int, Double) -> Unit = { progress, encodedSecs ->
                    if (progress > 0 || encodedSecs > 0.0) sawAdvance.set(true)
                    updateNotification(progress, "Exporting...", encodedSecs, durationSecs)
                    broadcastProgress(progress, encodedSecs)
                }
                var outcome: ExportOutcome = if (useGpu) {
                    val gpuParams = buildGpuParams(intent, tempOutputPath)
                    when (val r = gpuEngine.export(gpuParams, onProgress)) {
                        is GpuExportEngine.Result.Success -> ExportOutcome.Success(r.outputPath)
                        is GpuExportEngine.Result.Failure -> ExportOutcome.Failure(r.error)
                        GpuExportEngine.Result.Cancelled -> ExportOutcome.Cancelled
                    }
                } else {
                    when (val r = ffmpegEngine.exportFinal(
                        ffmpegCommand = command,
                        totalDurationSecs = durationSecs,
                        onProgress = onProgress
                    )) {
                        is FFmpegRenderEngine.RenderResult.Success -> ExportOutcome.Success(r.outputPath)
                        is FFmpegRenderEngine.RenderResult.Failure -> ExportOutcome.Failure(r.error)
                        is FFmpegRenderEngine.RenderResult.Cancelled -> ExportOutcome.Cancelled
                    }
                }

                // GPU → FFmpeg fallback (once): the Transformer has wedged
                // on-device with no error and no progress, and a fast GPU
                // failure may also be something FFmpeg handles fine. The
                // fallback re-runs on the same temp output (the command
                // starts with -y, so a partial GPU file is overwritten).
                // A user cancel never falls back.
                if (useGpu && fallbackCommand != null && !userCancelled.get() &&
                    (outcome is ExportOutcome.Failure ||
                        (outcome is ExportOutcome.Cancelled && stalled.get()))
                ) {
                    Log.w(TAG, "GPU export failed/stalled; falling back to FFmpeg")
                    updateNotification(0, "Falling back to standard export...", 0.0, durationSecs)
                    // Fresh 90s watchdog window for the fallback run.
                    sawAdvance.set(false)
                    stalled.set(false)
                    startedAtMs.set(System.currentTimeMillis())
                    outcome = when (val r = ffmpegEngine.exportFinal(
                        ffmpegCommand = fallbackCommand,
                        totalDurationSecs = durationSecs,
                        onProgress = onProgress
                    )) {
                        is FFmpegRenderEngine.RenderResult.Success -> ExportOutcome.Success(r.outputPath)
                        is FFmpegRenderEngine.RenderResult.Failure -> ExportOutcome.Failure(r.error)
                        is FFmpegRenderEngine.RenderResult.Cancelled -> ExportOutcome.Cancelled
                    }
                }

                val tempFile = File(tempOutputPath)
                val concatFile = concatFilePath?.let { File(it) }

                when (outcome) {
                    is ExportOutcome.Success -> {
                        val savedUri = saveVideoToGallery(tempFile, isAudioOnly)
                        if (savedUri != null) {
                            showCompletionNotification("Export Complete", "Video saved to gallery", savedUri)
                            broadcastSuccess(savedUri.toString())
                            Log.d(TAG, "Export successful: $savedUri")
                        } else {
                            showCompletionNotification("Export Failed", "Failed to save video to gallery", null)
                            broadcastFailure("Failed to save video to gallery")
                        }
                    }
                    is ExportOutcome.Failure -> {
                        showCompletionNotification("Export Failed", "Error during rendering", null)
                        broadcastFailure(outcome.error)
                        Log.e(TAG, "Export failed: ${outcome.error}")
                    }
                    is ExportOutcome.Cancelled -> {
                        // A watchdog kill is a stall, not a user cancel —
                        // report it as a failure so the editor shows the
                        // error instead of silently returning.
                        if (stalled.get()) {
                            showCompletionNotification("Export Failed", "Export stalled with no progress", null)
                            broadcastFailure("Export stalled (no progress in 90 seconds). Please try again.")
                            Log.e(TAG, "Export stalled: no progress in 90s")
                        } else {
                            showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                            broadcastFailure("Export cancelled")
                        }
                    }
                }

                // Cleanup temp files
                if (tempFile.exists()) tempFile.delete()
                if (concatFile?.exists() == true) concatFile.delete()

            } catch (e: Exception) {
                Log.e(TAG, "Export exception", e)
                showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                broadcastFailure(e.message ?: "Unknown error")
            } finally {
                watchdog.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun buildGpuParams(intent: Intent, tempOutputPath: String): GpuExportParams {
        val cropAspect = intent.getStringExtra(EXTRA_GPU_CROP_ASPECT)
        val canvasAspect = intent.getFloatExtra(EXTRA_GPU_CANVAS_ASPECT, -1f)
        // Phase 3 overlays travel as one Gson JSON string (texts,
        // subtitles, images, mirror). A bad payload falls back to no
        // overlays rather than failing the export.
        val overlaySpecs = intent.getStringExtra(EXTRA_GPU_OVERLAYS)?.let { json ->
            try {
                com.google.gson.Gson().fromJson(json, GpuOverlaySpecs::class.java)
            } catch (_: Exception) {
                null
            }
        } ?: GpuOverlaySpecs()
        return GpuExportParams(
            sourcePath = intent.getStringExtra(EXTRA_GPU_SOURCE_PATH) ?: "",
            outputPath = tempOutputPath,
            trimStartMs = intent.getLongExtra(EXTRA_GPU_TRIM_START_MS, 0L),
            trimEndMs = intent.getLongExtra(EXTRA_GPU_TRIM_END_MS, 0L),
            speed = intent.getFloatExtra(EXTRA_GPU_SPEED, 1f),
            targetRes = intent.getIntExtra(EXTRA_GPU_TARGET_RES, 0),
            targetFps = intent.getIntExtra(EXTRA_GPU_TARGET_FPS, 30),
            crop = cropAspect?.let {
                GpuCropSpec(
                    aspectRatio = it,
                    xFraction = intent.getFloatExtra(EXTRA_GPU_CROP_X, 0f),
                    yFraction = intent.getFloatExtra(EXTRA_GPU_CROP_Y, 0f),
                    wFraction = intent.getFloatExtra(EXTRA_GPU_CROP_W, 1f),
                    hFraction = intent.getFloatExtra(EXTRA_GPU_CROP_H, 1f)
                )
            },
            canvas = if (canvasAspect > 0f) {
                GpuCanvasSpec(
                    aspectRatio = canvasAspect,
                    blurRadius = intent.getIntExtra(EXTRA_GPU_CANVAS_BLUR, 0),
                    scale = intent.getFloatExtra(EXTRA_GPU_CANVAS_SCALE, 1f)
                )
            } else null,
            texts = overlaySpecs.texts,
            subtitles = overlaySpecs.subtitles,
            images = overlaySpecs.images,
            mirror = overlaySpecs.mirror
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Int, title: String, encodedSecs: Double = 0.0, totalSecs: Double = 0.0): Notification {
        // Tapping the notification goes to the editor's Export UI (the
        // full-screen progress screen), not Home: CLEAR_TOP brings the
        // running editor to the front; if the editor was closed, a fresh
        // one opens and the progress broadcasts re-show the export
        // screen over the empty editor (updateExportProgress sets
        // isExporting). Cancel still works there — it cancels the
        // service's sessions globally.
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // While progress is still 0% (1% of a 20-minute video is 12 seconds
        // of encoding), show the encoded time so the export doesn't look stuck.
        val contentText = when {
            progress > 0 && totalSecs > 0 -> "$progress% • ${formatTime(encodedSecs)} / ${formatTime(totalSecs)}"
            encodedSecs > 0 && totalSecs > 0 -> "Encoding… ${formatTime(encodedSecs)} / ${formatTime(totalSecs)}"
            progress > 0 -> "$progress%"
            else -> "Starting..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_save_24)
            .setProgress(100, progress, progress == 0 && encodedSecs <= 0.0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int, title: String, encodedSecs: Double = 0.0, totalSecs: Double = 0.0) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(progress, title, encodedSecs, totalSecs))
    }

    private fun formatTime(secs: Double): String {
        val total = secs.toLong().coerceAtLeast(0L)
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun showCompletionNotification(title: String, message: String, videoUri: Uri?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (videoUri != null) {
                setDataAndType(videoUri, if (message.contains("Audio", true)) "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setClass(this@ExportService, MainActivity::class.java)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_check_24)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun broadcastProgress(progress: Int, encodedSecs: Double = 0.0) {
        val intent = Intent(ACTION_EXPORT_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_ENCODED_SECS, encodedSecs)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSuccess(savedUri: String) {
        val intent = Intent(ACTION_EXPORT_SUCCESS).apply {
            putExtra(EXTRA_SAVED_URI, savedUri)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFailure(error: String) {
        val intent = Intent(ACTION_EXPORT_FAILURE).apply {
            putExtra(EXTRA_ERROR, error)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun saveVideoToGallery(videoFile: File, isAudioOnly: Boolean): Uri? {
        val mimeType = if (isAudioOnly) "audio/mpeg" else "video/mp4"
        val ext = if (isAudioOnly) ".mp3" else ".mp4"
        val prefix = if (isAudioOnly) "AIO_VD_Editor_Audio_" else "AIO_VD_Editor_"
        
        val sharedPreferences = getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customUriString = sharedPreferences.getString(prefKey, null)
        
        if (customUriString != null) {
            try {
                val treeUri = Uri.parse(customUriString)
                val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                val newFileUri = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    parentUri,
                    mimeType,
                    "${prefix}${System.currentTimeMillis()}$ext"
                )
                if (newFileUri != null) {
                    contentResolver.openOutputStream(newFileUri)?.use { output ->
                        videoFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    return newFileUri
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to custom directory: ${e.message}, falling back to default", e)
            }
        }

        return try {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}${System.currentTimeMillis()}$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (isAudioOnly) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AIO VD Editor")
                } else {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AIO VD Editor")
                }
            }
            val collectionUri = if (isAudioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(collectionUri, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { output ->
                    videoFile.inputStream().use { input -> input.copyTo(output) }
                }
                it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to default gallery: ${e.message}", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
