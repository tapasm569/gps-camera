package com.gpscam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class StampData(
    val title: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val note: String
)

object ImageStamper {

    fun stampAndSave(
        context: Context,
        tempFile: File,
        data: StampData,
        mapThumbFile: File?,
        aspectRatio: String? = null,
        qualityMode: String = "Medium"
    ): Uri? {
        val rawBitmap = BitmapFactory.decodeFile(tempFile.absolutePath) ?: return null
        val rotated = rotateIfRequired(rawBitmap, tempFile.absolutePath)
        if (rotated != rawBitmap) rawBitmap.recycle()

        val cropped = cropToAspectRatio(rotated, aspectRatio)
        if (cropped != rotated) rotated.recycle()

        val mutable = cropped.copy(Bitmap.Config.ARGB_8888, true)
        cropped.recycle()

        drawOverlay(context, mutable, data, mapThumbFile)
        val savedUri = saveToGallery(context, mutable, qualityMode)
        mutable.recycle()
        tempFile.delete()
        return savedUri
    }

    private fun cropToAspectRatio(bitmap: Bitmap, ratio: String?): Bitmap {
        if (ratio == null) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val target = when (ratio) {
            "1:1" -> 1f
            "4:3" -> if (h >= w) 3f / 4f else 4f / 3f
            "16:9" -> if (h >= w) 9f / 16f else 16f / 9f
            else -> return bitmap
        }
        val current = w.toFloat() / h.toFloat()
        if (kotlin.math.abs(current - target) < 0.02f) return bitmap

        val newW: Int
        val newH: Int
        if (current > target) {
            newH = h
            newW = (h * target).roundToInt().coerceAtMost(w)
        } else {
            newW = w
            newH = (w / target).roundToInt().coerceAtMost(h)
        }
        val x = (w - newW) / 2
        val y = (h - newH) / 2
        return Bitmap.createBitmap(bitmap, x, y, newW, newH)
    }

    private fun rotateIfRequired(bitmap: Bitmap, path: String): Bitmap {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val angle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun drawOverlay(context: Context, bitmap: Bitmap, data: StampData, mapThumbFile: File?) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val base = minOf(w, h)

        val margin = w * 0.03f
        val gap = w * 0.02f
        val pad = base * 0.018f
        val mapWidth = w * 0.25f
        val cornerRadius = base * 0.018f

        val textBoxLeft = margin + mapWidth + gap
        val textBoxRight = w - margin
        val textWidth = (textBoxRight - textBoxLeft - pad * 2).toInt().coerceAtLeast(100)

        // Larger Header Paint
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = base * 0.041f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        // Body Paint
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = base * 0.026f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        val headerText = data.title.trim()
        val headerLayout = if (headerText.isNotBlank()) {
            StaticLayout.Builder
                .obtain(headerText, 0, headerText.length, headerPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0.98f)
                .setIncludePad(false)
                .setMaxLines(3)
                .build()
        } else null

        val timestamp = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a 'GMT' XXX", Locale.getDefault()).format(Date())
        val coords = if (data.latitude != null && data.longitude != null) {
            String.format(Locale.US, "Lat %.6f° Long %.6f°", data.latitude, data.longitude)
        } else {
            "Lat -- Long --"
        }

        val bodyText = buildString {
            appendLine(data.address.ifBlank { "Location not set" })
            appendLine(coords)
            append(timestamp)
            if (data.note.isNotBlank()) append("\n${data.note}")
        }

        val bodyLayout = StaticLayout.Builder
            .obtain(bodyText, 0, bodyText.length, bodyPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 0.98f)
            .setIncludePad(false)
            .setMaxLines(10)
            .build()

        val headerBodyGap = if (headerLayout != null) base * 0.005f else 0f
        val totalTextHeight = (headerLayout?.height ?: 0) + headerBodyGap + bodyLayout.height
        val boxHeight = maxOf(mapWidth * 0.85f, totalTextHeight + pad * 2)
        val boxTop = h - boxHeight - margin
        val boxBottom = h - margin

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 10, 14, 22)
            style = Paint.Style.FILL
        }

        // 1. Draw standalone Map Box on the left
        val mapRect = RectF(margin, boxTop, margin + mapWidth, boxBottom)
        val mapBitmap = if (mapThumbFile != null && mapThumbFile.exists()) {
            BitmapFactory.decodeFile(mapThumbFile.absolutePath)
        } else null

        if (mapBitmap != null) {
            val saveCount = canvas.save()
            val clipPath = Path().apply { addRoundRect(mapRect, cornerRadius, cornerRadius, Path.Direction.CW) }
            canvas.clipPath(clipPath)
            val src = centerCropRect(mapBitmap.width, mapBitmap.height, mapRect.width(), mapRect.height())
            canvas.drawBitmap(mapBitmap, src, mapRect, Paint(Paint.FILTER_BITMAP_FLAG))

            // Draw "Google" centered at bottom of mapRect (never clipped)
            val gTextSize = mapRect.width() * 0.21f
            val gY = mapRect.bottom - mapRect.height() * 0.09f
            val gStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 20, 20, 20)
                textSize = gTextSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                style = Paint.Style.STROKE
                strokeWidth = mapRect.width() * 0.03f
                strokeJoin = Paint.Join.ROUND
            }
            val gFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = gTextSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                style = Paint.Style.FILL
            }
            canvas.drawText("Google", mapRect.centerX(), gY, gStroke)
            canvas.drawText("Google", mapRect.centerX(), gY, gFill)

            canvas.restoreToCount(saveCount)
            mapBitmap.recycle()
        } else {
            canvas.drawRoundRect(mapRect, cornerRadius, cornerRadius, bgPaint)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(148, 163, 184)
                textSize = mapWidth * 0.16f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("MAP", mapRect.centerX(), mapRect.centerY() + labelPaint.textSize / 3f, labelPaint)
        }

        // 2. Draw App Logo + App Name Badge on top-right of the Text Box
        val appName = "GPS Camera"
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = base * 0.024f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val badgePadH = base * 0.016f
        val badgePadV = base * 0.008f
        val iconSize = base * 0.030f
        val iconGap = base * 0.010f
        val badgeTextWidth = badgeTextPaint.measureText(appName)
        val badgeWidth = badgePadH + iconSize + iconGap + badgeTextWidth + badgePadH
        val badgeHeight = iconSize + badgePadV * 2
        val badgeRect = RectF(textBoxRight - badgeWidth, boxTop - badgeHeight, textBoxRight, boxTop)
        val textBoxRect = RectF(textBoxLeft, boxTop, textBoxRight, boxBottom)

        // Merge badge + text box into one seamless shape (no double-opacity seam)
        val br = cornerRadius * 0.75f
        val badgePath = Path().apply {
            addRoundRect(
                RectF(badgeRect.left, badgeRect.top, badgeRect.right, boxTop + cornerRadius),
                floatArrayOf(br, br, br, br, 0f, 0f, 0f, 0f),
                Path.Direction.CW
            )
        }
        val textBoxPath = Path().apply {
            addRoundRect(
                textBoxRect,
                floatArrayOf(cornerRadius, cornerRadius, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius),
                Path.Direction.CW
            )
        }
        badgePath.op(textBoxPath, Path.Op.UNION)
        canvas.drawPath(badgePath, bgPaint)

        val appIcon = BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher)
        if (appIcon != null) {
            val iconRect = RectF(
                badgeRect.left + badgePadH,
                badgeRect.top + badgePadV,
                badgeRect.left + badgePadH + iconSize,
                badgeRect.top + badgePadV + iconSize
            )
            val saveCount = canvas.save()
            val iconClip = Path().apply { addRoundRect(iconRect, iconSize * 0.2f, iconSize * 0.2f, Path.Direction.CW) }
            canvas.clipPath(iconClip)
            canvas.drawBitmap(appIcon, null, iconRect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restoreToCount(saveCount)
            appIcon.recycle()
        }

        val fm = badgeTextPaint.fontMetrics
        val badgeTextY = badgeRect.top + badgePadV + iconSize / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(appName, badgeRect.left + badgePadH + iconSize + iconGap, badgeTextY, badgeTextPaint)

        // Draw Header & Body vertically centered
        var currentY = boxTop + (boxHeight - totalTextHeight) / 2f
        if (headerLayout != null) {
            canvas.save()
            canvas.translate(textBoxLeft + pad, currentY)
            headerLayout.draw(canvas)
            canvas.restore()
            currentY += headerLayout.height + headerBodyGap
        }
        canvas.save()
        canvas.translate(textBoxLeft + pad, currentY)
        bodyLayout.draw(canvas)
        canvas.restore()
    }

    private fun centerCropRect(srcW: Int, srcH: Int, dstW: Float, dstH: Float): Rect {
        val dstRatio = dstW / dstH
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        return if (srcRatio > dstRatio) {
            val newW = (srcH * dstRatio).roundToInt().coerceAtMost(srcW)
            val x = (srcW - newW) / 2
            Rect(x, 0, x + newW, srcH)
        } else {
            val newH = (srcW / dstRatio).roundToInt().coerceAtMost(srcH)
            val y = (srcH - newH) / 2
            Rect(0, y, srcW, y + newH)
        }
    }

    private fun compressToLimit(source: Bitmap, qualityMode: String): ByteArray {
        val maxBytes = when (qualityMode) {
            "Low" -> 1 * 1024 * 1024
            "High" -> 10 * 1024 * 1024
            else -> 5 * 1024 * 1024
        }
        var jpegQuality = when (qualityMode) {
            "Low" -> 78
            "High" -> 98
            else -> 90
        }

        var working = source
        val baos = ByteArrayOutputStream()

        while (true) {
            baos.reset()
            working.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            if (baos.size() <= maxBytes) break

            if (jpegQuality > 30) {
                jpegQuality -= 12
            } else {
                val nextW = (working.width * 0.8f).roundToInt().coerceAtLeast(480)
                val nextH = (working.height * 0.8f).roundToInt().coerceAtLeast(480)
                if (nextW == working.width && nextH == working.height) break
                val scaled = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (working !== source) working.recycle()
                working = scaled
                jpegQuality = 75
            }
        }

        if (working !== source) working.recycle()
        return baos.toByteArray()
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap, qualityMode: String): Uri? {
        val jpegBytes = compressToLimit(bitmap, qualityMode)
        val fileName = "GPS_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GPSCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(jpegBytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            null
        }
    }
}
