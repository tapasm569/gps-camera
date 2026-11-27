package com.gpscam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class StampData(
    val title: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val note: String
)

object ImageStamper {

    fun stampAndSave(context: Context, tempFile: File, data: StampData, mapThumbFile: File?): Boolean {
        val rawBitmap = BitmapFactory.decodeFile(tempFile.absolutePath) ?: return false
        val rotated = rotateIfRequired(rawBitmap, tempFile.absolutePath)
        if (rotated != rawBitmap) rawBitmap.recycle()

        val mutable = rotated.copy(Bitmap.Config.ARGB_8888, true)
        rotated.recycle()

        drawOverlay(mutable, data, mapThumbFile)
        val saved = saveToGallery(context, mutable)
        mutable.recycle()
        tempFile.delete()
        return saved
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

    private fun drawOverlay(bitmap: Bitmap, data: StampData, mapThumbFile: File?) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val margin = w * 0.03f
        val cardHeight = h * 0.17f
        val cardTop = h - cardHeight - margin
        val cardRect = RectF(margin, cardTop, w - margin, h - margin)

        // Dark slate translucent card
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 15, 23, 42)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 100, 116, 139)
            style = Paint.Style.STROKE
            strokeWidth = w * 0.003f
        }
        val cornerRadius = w * 0.025f
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        val pad = cardHeight * 0.1f
        val mapSize = cardHeight - (pad * 2)
        val mapRect = RectF(cardRect.left + pad, cardRect.top + pad, cardRect.left + pad + mapSize, cardRect.bottom - pad)

        // Draw saved map thumbnail or fallback box
        val mapBitmap = if (mapThumbFile != null && mapThumbFile.exists()) {
            BitmapFactory.decodeFile(mapThumbFile.absolutePath)
        } else null

        if (mapBitmap != null) {
            val saveCount = canvas.save()
            val clipPath = Path().apply { addRoundRect(mapRect, cornerRadius * 0.6f, cornerRadius * 0.6f, Path.Direction.CW) }
            canvas.clipPath(clipPath)
            val src = centerCropRect(mapBitmap.width, mapBitmap.height)
            canvas.drawBitmap(mapBitmap, src, mapRect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restoreToCount(saveCount)
            mapBitmap.recycle()
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 41, 59) }
            canvas.drawRoundRect(mapRect, cornerRadius * 0.6f, cornerRadius * 0.6f, placeholderPaint)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(148, 163, 184)
                textSize = mapSize * 0.18f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("MAP", mapRect.centerX(), mapRect.centerY() + labelPaint.textSize / 3f, labelPaint)
        }

        // Text block on the right
        val textLeft = mapRect.right + pad
        val textWidth = (cardRect.right - pad - textLeft).toInt().coerceAtLeast(100)
        val timestamp = SimpleDateFormat("dd/MM/yyyy hh:mm:ss a 'GMT'XXX", Locale.getDefault()).format(Date())
        val coords = if (data.latitude != null && data.longitude != null) {
            String.format(Locale.US, "Lat %.6f°  Long %.6f°", data.latitude, data.longitude)
        } else {
            "Lat --  Long --"
        }

        val lines = buildString {
            if (data.title.isNotBlank()) appendLine(data.title)
            appendLine(data.address.ifBlank { "Location not set" })
            appendLine(coords)
            append(timestamp)
            if (data.note.isNotBlank()) append("
${data.note}")
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = cardHeight * 0.135f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        val staticLayout = StaticLayout.Builder
            .obtain(lines, 0, lines.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.05f)
            .setMaxLines(6)
            .build()

        canvas.save()
        canvas.translate(textLeft, cardRect.top + pad)
        staticLayout.draw(canvas)
        canvas.restore()
    }

    private fun centerCropRect(w: Int, h: Int): Rect {
        val size = minOf(w, h)
        val x = (w - size) / 2
        val y = (h - size) / 2
        return Rect(x, y, x + size, y + size)
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
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
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
