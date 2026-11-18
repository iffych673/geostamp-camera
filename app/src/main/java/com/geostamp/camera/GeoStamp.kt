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

    /** 3x3 OSM tiles at zoom 16, center-cropped to [sizePx]. A failed tile is
     *  filled with a neutral green instead of failing the whole map. */
    fun fetchMapBitmap(lat: Double, lon: Double, sizePx: Int): Bitmap? {
        return try {
            val size = sizePx.coerceIn(64, 768)
            val zoom = 16
            val n = 1 shl zoom
            val xtile = floor((lon + 180.0) / 360.0 * n).toInt()
            val latRad = Math.toRadians(lat)
            val ytile = floor((1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
            val big = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(big)
            val fillPaint = Paint().apply { color = Color.rgb(183, 217, 168) }
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val tx = xtile + dx
                    val ty = ytile + dy
                    val left = ((dx + 1) * 256).toFloat()
                    val top = ((dy + 1) * 256).toFloat()
                    var tile: Bitmap? = null
                    try {
                        val url = "https://tile.openstreetmap.org/$zoom/$tx/$ty.png"
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.setRequestProperty("User-Agent", "GeoStampCamera/1.0")
                        if (conn.responseCode == 200) {
                            tile = BitmapFactory.decodeStream(conn.inputStream)
                        }
                    } catch (e: Exception) {
                        tile = null
                    }
                    if (tile != null) {
                        canvas.drawBitmap(tile, left, top, null)
                        tile.recycle()
                    } else {
                        canvas.drawRect(left, top, left + 256f, top + 256f, fillPaint)
                    }
                }
            }
            val cropLeft = (768 - size) / 2
            val cropTop = (768 - size) / 2
            Bitmap.createBitmap(big, cropLeft, cropTop, size, size)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- stamp drawing (GPS Map Camera style) ----------

    /** "Wednesday, 18/11/2026 08:52 AM GMT +05:00" from "yyyy-MM-dd HH:mm:ss". */
    fun formatStampDate(timeText: String): String {
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val d = inFmt.parse(timeText) ?: Date()
            val outFmt = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.US)
            val off = java.util.TimeZone.getDefault().getOffset(d.time)
            val sign = if (off >= 0) "+" else "-"
            val abs = Math.abs(off)
            val hh = abs / 3600000
            val mm = (abs % 3600000) / 60000
            outFmt.format(d) + " GMT " + sign + String.format(Locale.US, "%02d:%02d", hh, mm)
        } catch (e: Exception) {
            timeText
        }
    }

    /** Small map-pin icon (white pin + dark lens dot), like the app icon. */
    private fun drawPinIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val r = size * 0.32f
        val centerY = cy - size * 0.14f
        val pinPaint = Paint().apply { this.color = color; isAntiAlias = true }
        canvas.drawCircle(cx, centerY, r, pinPaint)
        val tri = android.graphics.Path().apply {
            moveTo(cx - r * 0.85f, centerY + r * 0.3f)
            lineTo(cx + r * 0.85f, centerY + r * 0.3f)
            lineTo(cx, cy + size * 0.5f)
            close()
        }
        canvas.drawPath(tri, pinPaint)
        canvas.drawCircle(
            cx, centerY, r * 0.45f,
            Paint().apply { this.color = Color.rgb(11, 110, 79); isAntiAlias = true }
        )
    }

    /** Pic-1 style stamp: map thumb bottom-left, dark rounded panels, header pill.
     *  All geometry derives from the photo WIDTH, so a portrait photo gets the
     *  exact same-looking stamp as a landscape one. */
    fun drawStamp(ctx: Context, src: Bitmap, data: StampData, map: Bitmap?): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        // Geometry, scaled from the 1600x1200 GPS Map Camera reference.
        val panelH = w * 0.19f
        val panelBottom = h - w * 0.02f
        val panelTop = panelBottom - panelH
        val pillH = w * 0.04f
        val pillTop = panelTop - pillH
        val pillBottom = panelTop
        val panelX0 = w * 0.315f
        val panelX1 = w * 0.888f
        val pillX0 = w * 0.708f
        val pillX1 = panelX1
        val mapSize = panelH
        val mapX = w * 0.113f
        val mapY = panelTop
        val textX = w * 0.334f
        val corner = w * 0.01f
        val panelPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }

        // Dark translucent rounded panels.
        canvas.drawRoundRect(panelX0, panelTop, panelX1, panelBottom, corner, corner, panelPaint)
        canvas.drawRoundRect(pillX0, pillTop, pillX1, pillBottom, corner * 0.75f, corner * 0.75f, panelPaint)

        // Header pill: map-pin logo + "GeoStamp Camera".
        val pillTextSize = w * 0.0188f
        val headPaint = Paint().apply {
            color = Color.WHITE
            textSize = pillTextSize
            isAntiAlias = true
        }
        val iconSize = pillTextSize * 1.2f
        val iconLeft = pillX0 + (pillBottom - pillTop) * 0.25f
        val iconTop = pillTop + (pillBottom - pillTop - iconSize) / 2f
        drawPinIcon(
            canvas,
            iconLeft + iconSize / 2f, iconTop + iconSize / 2f,
            iconSize, Color.WHITE
        )
        val headTextX = iconLeft + iconSize + pillTextSize * 0.4f
        val headBaseline = pillTop + (pillBottom - pillTop) * 0.5f + pillTextSize * 0.36f
        canvas.drawText("GeoStamp Camera", headTextX, headBaseline, headPaint)

        // Map thumbnail with red pinpoint. If the map failed to load, draw a
        // muted-green map panel with road lines so the stamp always shows a
        // map area with a pin, even offline.
        val mapLeft = mapX
        val mapTop = mapY
        val mapRight = mapX + mapSize
        val mapBottom = mapY + mapSize
        canvas.save()
        val clip = android.graphics.Path().apply {
            addRoundRect(
                mapLeft, mapTop, mapRight, mapBottom,
                corner, corner, android.graphics.Path.Direction.CW
            )
        }
        canvas.clipPath(clip)
        if (map != null) {
            val scaled = Bitmap.createScaledBitmap(map, mapSize.toInt(), mapSize.toInt(), true)
            canvas.drawBitmap(scaled, mapLeft, mapTop, null)
            if (scaled != map) scaled.recycle()
        } else {
            canvas.drawRect(
                mapLeft, mapTop, mapRight, mapBottom,
                Paint().apply { color = Color.rgb(123, 175, 110) }
            )
            val roadPaint = Paint().apply {
                color = Color.rgb(216, 216, 216)
                strokeWidth = mapSize * 0.02f
                isAntiAlias = true
            }
            canvas.drawLine(mapLeft, mapTop + mapSize * 0.35f, mapRight, mapTop + mapSize * 0.55f, roadPaint)
            canvas.drawLine(mapLeft + mapSize * 0.6f, mapTop, mapLeft + mapSize * 0.4f, mapBottom, roadPaint)
        }
        canvas.restore()

        // Red pinpoint at the map centre (always drawn).
        val cx = mapLeft + mapSize / 2f
        val pinR = mapSize * 0.085f
        val cy = mapTop + mapSize / 2f - pinR * 0.4f
        val pinPaint = Paint().apply { color = Color.rgb(229, 57, 53); isAntiAlias = true }
        canvas.drawCircle(cx, cy, pinR, pinPaint)
        canvas.drawCircle(cx, cy, pinR * 0.45f, Paint().apply { color = Color.WHITE; isAntiAlias = true })
        val tri = android.graphics.Path().apply {
            moveTo(cx - pinR * 0.7f, cy + pinR * 0.55f)
            lineTo(cx + pinR * 0.7f, cy + pinR * 0.55f)
            lineTo(cx, cy + pinR * 1.5f)
            close()
        }
        canvas.drawPath(tri, pinPaint)
        if (map != null) {
            val attrPaint = Paint().apply {
                color = Color.WHITE
                textSize = w * 0.008f
                isAntiAlias = true
                setShadowLayer(2f, 1f, 1f, Color.BLACK)
            }
            canvas.drawText("© OpenStreetMap", mapLeft + 4f, mapBottom - 4f, attrPaint)
        }

        // Text lines (Pic-1 order): title, address, coords, date, note, extras.
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = w * 0.03f
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.WHITE
            textSize = w * 0.0213f
            isAntiAlias = true
        }
        val lines = ArrayList<Triple<String, Paint, Float>>()
        lines.add(Triple(data.poiName ?: data.address, titlePaint, 1.35f))
        if (data.poiName != null) lines.add(Triple(data.address, bodyPaint, 1f))
        var coord = "Lat %.6f° Long %.6f°".format(Locale.US, data.lat, data.lon)
        if (data.heading != null) coord += " · Heading %d°".format(Locale.US, data.heading.toInt())
        lines.add(Triple(coord, bodyPaint, 1f))
        var dateLine = formatStampDate(data.timeText)
        if (data.isMock) dateLine += " · MOCK LOCATION"
        lines.add(Triple(dateLine, bodyPaint, 1f))
        lines.add(Triple("Note: Captured by GeoStamp Camera", bodyPaint, 1f))
        if (data.weather != null) lines.add(Triple(data.weather, bodyPaint, 1f))
        if (!data.signature.isNullOrBlank()) lines.add(Triple(data.signature, bodyPaint, 1f))

        val pad = w * 0.012f
        var totalWeight = 0f
        for ((_, _, wt) in lines) totalWeight += wt
        val unit = (panelBottom - panelTop - pad * 2f) / totalWeight
        var y = panelTop + pad + unit * 0.62f
        val maxTextW = panelX1 - textX - pad
        for ((text, paint, wt) in lines) {
            drawTextEllipsize(canvas, text, paint, maxTextW, textX, y)
            y += unit * wt
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
