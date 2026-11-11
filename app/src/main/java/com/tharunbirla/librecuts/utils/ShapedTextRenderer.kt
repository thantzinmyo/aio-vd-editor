package com.tharunbirla.librecuts.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Renders text to a transparent PNG with Android's text stack (HarfBuzz
 * shaping via StaticLayout) so complex scripts render correctly in export.
 *
 * WHY THIS EXISTS: FFmpeg's drawtext filter renders glyphs with FreeType
 * only — it performs NO complex-text shaping. For Myanmar that breaks
 * stacked conjuncts: the virama (U+1039) is drawn as a visible "+" cross
 * below the baseline instead of forming the conjunct (verified 2026-11-11
 * with host FFmpeg 8.1.2 + Noto Sans Myanmar: "မင်္ဂလာပါ" rendered with a
 * stray "+" under "င", while the same string shaped by HarfBuzz is clean).
 * The live preview does NOT have this problem (Android Canvas shapes), so
 * without this fallback preview and export would disagree.
 *
 * Strategy: any text containing Myanmar codepoints is pre-rendered here to
 * a PNG and composited with the normal `overlay` filter instead of
 * drawtext. Latin-only text keeps the drawtext path (unchanged).
 *
 * Known limitation: Noto Sans Myanmar has NO Latin glyphs, so a mixed
 * "Hello မြန်မာ" string renders its Latin part as tofu boxes in the PNG —
 * exactly what the live preview shows for the same font choice, so preview
 * and export stay consistent. Advise users to keep Latin and Myanmar in
 * separate text overlays (or pick a Myanmar font that ships Latin glyphs,
 * e.g. Pyidaungsu).
 */
object ShapedTextRenderer {
    private const val TAG = "ShapedTextRenderer"

    /** Myanmar Unicode block + Myanmar Extended-A. */
    fun containsMyanmar(text: String): Boolean =
        text.any { c -> (c in '\u1000'..'\u109F') || (c in '\uAA60'..'\uAA7F') }

    /** True when [text] must go through the PNG path instead of drawtext. */
    fun needsShapedRendering(text: String): Boolean = containsMyanmar(text)

    data class RenderedText(val file: File, val width: Int, val height: Int)

    /**
     * Render [text] (may contain '\n' for multiple lines) to a transparent
     * PNG in the cache dir, reusing a cached file when the same parameters
     * were rendered before.
     *
     * @param fontSizePx  text size in video pixels (same units as
     *                    drawtext fontsize for AddText; already scaled for
     *                    subtitles by the caller).
     * @param targetLineHeightPx desired distance between baselines, in
     *                    pixels (0 = natural font line height). The layout
     *                    adds spacing only when the target exceeds the
     *                    font's natural line height, so the PNG's total
     *                    height matches the caller's block-height maths
     *                    (e.g. subtitles: fontSize * 1.25 per line).
     * @param align       "left" / "right", anything else = center.
     * @return the PNG file + its pixel size, or null on failure.
     */
    fun renderTextPng(
        context: Context,
        text: String,
        fontPath: String?,
        fontSizePx: Int,
        colorHex: String,
        borderThicknessPx: Int = 0,
        borderColorHex: String = "#000000",
        align: String = "center",
        targetLineHeightPx: Float = 0f
    ): RenderedText? {
        if (text.isEmpty() || fontSizePx <= 0) return null
        return try {
            val cacheKey = cacheKey(text, fontPath, fontSizePx, colorHex, borderThicknessPx, borderColorHex, align, targetLineHeightPx)
            val dir = File(context.cacheDir, "shaped_text").apply { if (!exists()) mkdirs() }
            val outFile = File(dir, "$cacheKey.png")
            // Reuse a previous render (same text/font/size/colour).
            if (outFile.exists() && outFile.length() > 0) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(outFile.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    return RenderedText(outFile, opts.outWidth, opts.outHeight)
                }
            }

            val typeface = resolveTypeface(context, fontPath)
            val fgColor = parseColor(colorHex, Color.WHITE)
            val strokeColor = parseColor(borderColorHex, Color.BLACK)
            val pad = borderThicknessPx + 4

            val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = fontSizePx.toFloat()
                color = fgColor
            }
            val strokePaint = if (borderThicknessPx > 0) {
                TextPaint(fillPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = borderThicknessPx.toFloat()
                    strokeJoin = Paint.Join.ROUND
                    color = strokeColor
                }
            } else null

            // Tight width: widest line, so the overlay's w matches drawtext's tw.
            val maxLineWidth = text.split("\n").maxOfOrNull { fillPaint.measureText(it) } ?: 0f
            val layoutWidth = (maxLineWidth.ceilToInt() + pad * 2).coerceAtLeast(2)
            val alignment = when (align.lowercase()) {
                "left" -> Layout.Alignment.ALIGN_NORMAL
                "right" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_CENTER
            }
            // Make one layout line occupy targetLineHeightPx when the caller
            // needs the PNG height to match its block-height maths (e.g.
            // subtitles: fontSize * 1.25 per line, also used for the blur
            // band). Never shrinks below the font's natural line height.
            val fm = fillPaint.fontMetricsInt
            val naturalLineHeight = (fm.descent - fm.ascent).coerceAtLeast(1)
            val spacingAdd = if (targetLineHeightPx > 0f) {
                (targetLineHeightPx - naturalLineHeight).coerceAtLeast(0f)
            } else 0f

            val fillLayout = StaticLayout.Builder.obtain(text, 0, text.length, fillPaint, layoutWidth)
                .setAlignment(alignment)
                .setLineSpacing(spacingAdd, 1f)
                .setIncludePad(false)
                .build()
            val strokeLayout = strokePaint?.let {
                StaticLayout.Builder.obtain(text, 0, text.length, it, layoutWidth)
                    .setAlignment(alignment)
                    .setLineSpacing(spacingAdd, 1f)
                    .setIncludePad(false)
                    .build()
            }

            val bmpWidth = layoutWidth
            val bmpHeight = (fillLayout.height + pad * 2).coerceAtLeast(2)
            @Suppress("DEPRECATION")
            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Stroke under fill, both shifted by the padding.
            canvas.save()
            canvas.translate(pad.toFloat(), pad.toFloat())
            strokeLayout?.draw(canvas)
            fillLayout.draw(canvas)
            canvas.restore()

            FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!bitmap.isRecycled) bitmap.recycle()
            Log.d(TAG, "Rendered shaped text (${text.length} chars, ${bmpWidth}x${bmpHeight}) -> ${outFile.name}")
            RenderedText(outFile, bmpWidth, bmpHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render shaped text: ${e.message}", e)
            null
        }
    }

    /** Absolute path of the bundled Myanmar font in cache (copies from assets if needed). */
    fun bundledMyanmarFontPath(context: Context): String? {
        return try {
            val fileName = FontManager.MYANMAR_FONT_ASSET.substringAfterLast('/')
            val file = File(context.cacheDir, fileName)
            if (!file.exists()) {
                context.assets.open(FontManager.MYANMAR_FONT_ASSET).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache Myanmar font: ${e.message}", e)
            null
        }
    }

    private fun resolveTypeface(context: Context, fontPath: String?): Typeface {
        if (!fontPath.isNullOrBlank()) {
            try {
                val file = File(fontPath)
                if (file.exists()) return Typeface.createFromFile(file)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot load typeface '$fontPath', falling back: ${e.message}")
            }
            // The default fontFilePath is a fontconfig alias ("Roboto-Regular"),
            // not a real path — ignore it here, the bundled Myanmar font is the
            // right fallback for Myanmar text.
        }
        bundledMyanmarFontPath(context)?.let { path ->
            try {
                return Typeface.createFromFile(path)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot load bundled Myanmar font: ${e.message}")
            }
        }
        return Typeface.DEFAULT
    }

    private fun parseColor(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (e: Exception) {
            fallback
        }
    }

    private fun cacheKey(
        text: String, fontPath: String?, fontSizePx: Int, colorHex: String,
        borderThicknessPx: Int, borderColorHex: String, align: String, targetLineHeightPx: Float
    ): String {
        val raw = listOf(text, fontPath ?: "", fontSizePx, colorHex, borderThicknessPx, borderColorHex, align, targetLineHeightPx).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun Float.ceilToInt(): Int = kotlin.math.ceil(this).toInt()
}
