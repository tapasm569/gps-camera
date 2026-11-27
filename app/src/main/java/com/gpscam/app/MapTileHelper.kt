package com.gpscam.app

import android.content.Context
import android.graphics.*
import android.location.Geocoder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

data class GeoResult(
    val header: String,
    val fullAddress: String
)

object MapTileHelper {

    private fun countryCodeToFlag(code: String?): String {
        if (code == null || code.length != 2) return ""
        val first = Character.codePointAt(code.uppercase(Locale.US), 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(code.uppercase(Locale.US), 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun fetchGeoDetails(context: Context, lat: Double, lng: Double): GeoResult? {
        return try {
            @Suppress("DEPRECATION")
            val addr = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()
                ?: return null

            val city = addr.locality ?: addr.subAdminArea ?: addr.featureName ?: ""
            val state = addr.adminArea ?: ""
            val country = addr.countryName ?: ""
            val flag = countryCodeToFlag(addr.countryCode)
            val baseHeader = listOf(city, state, country).filter { it.isNotBlank() }.joinToString(", ")
            val header = if (flag.isNotBlank()) "$baseHeader $flag" else baseHeader
            val fullAddress = addr.getAddressLine(0) ?: baseHeader

            GeoResult(header = header, fullAddress = fullAddress)
        } catch (_: Exception) {
            null
        }
    }

    fun downloadMapTile(lat: Double, lng: Double, outputFile: File, zoom: Int = 16): Boolean {
        return try {
            val n = 2.0.pow(zoom)
            val x = floor((lng + 180.0) / 360.0 * n).toInt()
            val latRad = Math.toRadians(lat)
            val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()

            // Esri World Imagery Satellite Tile (uses z/y/x order)
            val url = URL("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$y/$x")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "GPSCameraApp/1.0")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (conn.responseCode != 200) return false
            val raw = BitmapFactory.decodeStream(conn.inputStream) ?: return false
            val mutable = raw.copy(Bitmap.Config.ARGB_8888, true)
            raw.recycle()

            val canvas = Canvas(mutable)
            val w = mutable.width.toFloat()
            val h = mutable.height.toFloat()

            // Draw classic red teardrop GPS pin at center
            val cx = w / 2f
            val tipY = h * 0.52f
            val r = w * 0.065f
            val headCy = tipY - r * 1.9f

            val pinPath = Path().apply {
                moveTo(cx, tipY)
                cubicTo(cx - r * 1.1f, tipY - r * 0.9f, cx - r, headCy + r * 0.3f, cx - r, headCy)
                arcTo(RectF(cx - r, headCy - r, cx + r, headCy + r), 180f, 180f, false)
                cubicTo(cx + r, headCy + r * 0.3f, cx + r * 1.1f, tipY - r * 0.9f, cx, tipY)
                close()
            }
            val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(229, 57, 53)
                style = Paint.Style.FILL
                setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0))
            }
            val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(120, 20, 20)
                style = Paint.Style.FILL
            }
            canvas.drawPath(pinPath, pinPaint)
            canvas.drawCircle(cx, headCy, r * 0.38f, innerDotPaint)

            // Draw "Google" text near bottom of map tile
            val textSize = w * 0.17f
            val textX = w * 0.08f
            val textY = h * 0.88f

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 20, 20, 20)
                this.textSize = textSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                style = Paint.Style.STROKE
                strokeWidth = w * 0.025f
                strokeJoin = Paint.Join.ROUND
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                style = Paint.Style.FILL
            }
            canvas.drawText("Google", textX, textY, strokePaint)
            canvas.drawText("Google", textX, textY, fillPaint)

            outputFile.outputStream().use { out ->
                mutable.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            mutable.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }
}
