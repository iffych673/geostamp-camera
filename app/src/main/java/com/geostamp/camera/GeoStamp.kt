package com.geostamp.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Own-code helpers for the GeoStamp Camera stamp pipeline.
 * No API keys required: OSM tiles, Open-Meteo weather, worldtimeapi time.
 */
object GeoStamp {

    data class StampData(
        val timeText: String,
        val timeSource: String,
        val isMock: Boolean,
        val address: String,
        val lat: Double,
        val lon: Double,
        val heading: Float?,
        val weather: String?,
        val poiName: String?,
        val signature: String?
    )

    data class Poi(val name: String, val lat: Double, val lon: Double)

    // ---------- network helpers ----------

    private fun httpGet(urlStr: String, timeoutMs: Int = 8000): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "GeoStampCamera/1.0")
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            null
        }
    }

    /** Returns (display time, "Network time" or "Device time"). */
    fun fetchTime(): Pair<String, String> {
        val body = httpGet("https://worldtimeapi.org/api/ip")
        if (body != null) {
            try {
                val dt = JSONObject(body).getString("datetime") // 2026-11-04T21:30:00+05:00
                val clean = dt.substring(0, 19).replace('T', ' ')
                return Pair(clean, "Network time")
            } catch (e: Exception) {
                // fall through to device time
            }
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return Pair(fmt.format(Date()), "Device time")
    }

    fun fetchWeather(lat: Double, lon: Double): String? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,wind_speed_10m"
        val body = httpGet(url) ?: return null
        return try {
            val cur = JSONObject(body).getJSONObject("current")
            val t = cur.getDouble("temperature_2m")
            val h = cur.getInt("relative_humidity_2m")
            val w = cur.getDouble("wind_speed_10m")
            "%.1f°C · Humidity %d%% · Wind %.0f km/h".format(Locale.US, t, h, w)
        } catch (e: Exception) {
            null
        }
    }

    /** 3x3 OSM tiles at zoom 16, center-cropped to [sizePx]. Null on any failure. */
    fun fetchMapBitmap(lat: Double, lon: Double, sizePx: Int): Bitmap? {
        return try {
            val zoom = 16
            val n = 1 shl zoom
            val xtile = floor((lon + 180.0) / 360.0 * n).toInt()
            val latRad = Math.toRadians(lat)
            val ytile = floor((1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
            val big = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(big)
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val tx = xtile + dx
                    val ty = ytile + dy
                    val url = "https://tile.openstreetmap.org/$zoom/$tx/$ty.png"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "GeoStampCamera/1.0")
                    if (conn.responseCode != 200) return null
                    val tile = BitmapFactory.decodeStream(conn.inputStream) ?: return null
                    canvas.drawBitmap(tile, ((dx + 1) * 256).toFloat(), ((dy + 1) * 256).toFloat(), null)
                    tile.recycle()
                }
            }
            val left = (768 - sizePx) / 2
            val top = (768 - sizePx) / 2
            Bitmap.createBitmap(big, left, top, sizePx, sizePx)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- stamp drawing ----------

    fun drawStamp(src: Bitmap, data: StampData, map: Bitmap?): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width
        val h = out.height
        val barH = (h * 0.30).toInt()
        val barTop = h - barH
        val barPaint = Paint().apply { color = Color.argb(175, 0, 0, 0) }
        canvas.drawRect(0f, barTop.toFloat(), w.toFloat(), h.toFloat(), barPaint)

        val pad = (w / 50).toFloat()
        val textSize = w / 30f
        val bold = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val plain = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val small = Paint().apply {
            color = Color.LTGRAY
            this.textSize = w / 55f
        }

        val lines = ArrayList<Pair<String, Paint>>()
        var first = data.timeText + " · " + data.timeSource
        if (data.isMock) first += " · MOCK LOCATION DETECTED"
        lines.add(Pair(first, bold))
        lines.add(Pair(data.poiName ?: data.address, plain))
        var coord = "Lat %.5f, Lon %.5f".format(Locale.US, data.lat, data.lon)
        if (data.heading != null) coord += " · Heading %d°".format(Locale.US, data.heading.toInt())
        lines.add(Pair(coord, plain))
        if (data.weather != null) lines.add(Pair(data.weather, plain))
        if (!data.signature.isNullOrBlank()) lines.add(Pair(data.signature, plain))

        var mapSize = 0
        var mapBitmap: Bitmap? = null
        if (map != null) {
            mapSize = (barH * 0.92).toInt()
            mapBitmap = Bitmap.createScaledBitmap(map, mapSize, mapSize, true)
        }
        val textRight = w - pad - if (mapSize > 0) (mapSize + pad) else 0f
        var y = barTop + pad + textSize
        val lineH = textSize * 1.35f
        for ((text, paint) in lines) {
            if (y > h - pad) break
            drawTextEllipsize(canvas, text, paint, textRight - pad, pad, y)
            y += lineH
        }
        if (mapBitmap != null) {
            val mx = w - pad - mapSize
            val my = barTop + (barH - mapSize) / 2f
            canvas.drawBitmap(mapBitmap, mx, my, null)
            canvas.drawText("© OpenStreetMap contributors", mx, my + mapSize - 4f, small)
        }
        return out
    }

    // drawText with manual ellipsis so long addresses never overflow the bar
    private fun drawTextEllipsize(canvas: Canvas, text: String, paint: Paint, maxWidth: Float, x: Float, y: Float) {
        var t = text
        while (paint.measureText(t) > maxWidth && t.length > 4) {
            t = t.substring(0, t.length - 4) + "…"
        }
        canvas.drawText(t, x, y, paint)
    }

    // ---------- prefs: signature + POI ----------

    fun getSignature(ctx: Context): String? {
        val p = ctx.getSharedPreferences("geostamp", Context.MODE_PRIVATE)
        val company = p.getString("sig_company", "") ?: ""
        val contact = p.getString("sig_contact", "") ?: ""
        if (company.isBlank() && contact.isBlank()) return null
        return listOf(company, contact).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun saveSignature(ctx: Context, company: String, contact: String) {
        ctx.getSharedPreferences("geostamp", Context.MODE_PRIVATE).edit()
            .putString("sig_company", company).putString("sig_contact", contact).apply()
    }

    fun loadSignature(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences("geostamp", Context.MODE_PRIVATE)
        return Pair(p.getString("sig_company", "") ?: "", p.getString("sig_contact", "") ?: "")
    }

    fun loadPois(ctx: Context): List<Poi> {
        val raw = ctx.getSharedPreferences("geostamp", Context.MODE_PRIVATE).getString("pois", "[]") ?: "[]"
        val out = ArrayList<Poi>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Poi(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")))
            }
        } catch (e: Exception) {
            // ignore corrupt prefs
        }
        return out
    }

    fun savePoi(ctx: Context, poi: Poi) {
        val arr = JSONArray()
        for (p in loadPois(ctx)) {
            if (p.name != poi.name) {
                val o = JSONObject()
                o.put("name", p.name); o.put("lat", p.lat); o.put("lon", p.lon)
                arr.put(o)
            }
        }
        val o = JSONObject()
        o.put("name", poi.name); o.put("lat", poi.lat); o.put("lon", poi.lon)
        arr.put(o)
        ctx.getSharedPreferences("geostamp", Context.MODE_PRIVATE).edit()
            .putString("pois", arr.toString()).apply()
    }

    fun findPoi(ctx: Context, lat: Double, lon: Double, radiusM: Float = 150f): String? {
        val results = FloatArray(1)
        for (p in loadPois(ctx)) {
            android.location.Location.distanceBetween(lat, lon, p.lat, p.lon, results)
            if (results[0] <= radiusM) return p.name
        }
        return null
    }

    // ---------- saving ----------

    fun stampFileName(): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "GeoStamp_" + fmt.format(Date()) + ".jpg"
    }

    fun saveJpegToGallery(ctx: Context, bitmap: Bitmap, displayName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoStamp")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "GeoStamp"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, displayName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        return stream.toByteArray()
    }
}
