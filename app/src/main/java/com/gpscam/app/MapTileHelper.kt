package com.gpscam.app

import android.content.Context
import android.graphics.*
import android.location.Geocoder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

object MapTileHelper {

    fun fetchAddress(context: Context, lat: Double, lng: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
            list?.firstOrNull()?.getAddressLine(0)
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

            val url = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "GPSCameraApp/1.0")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (conn.responseCode != 200) return false
            val raw = BitmapFactory.decodeStream(conn.inputStream) ?: return false
            val mutable = raw.copy(Bitmap.Config.ARGB_8888, true)
            raw.recycle()

            // Draw a red location pin at the center of the tile
            val canvas = Canvas(mutable)
            val cx = mutable.width / 2f
            val cy = mutable.height / 2f
            val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawCircle(cx, cy, 14f, pinPaint)
            canvas.drawCircle(cx, cy, 14f, strokePaint)

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
