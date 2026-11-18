package com.geostamp.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Builds a branded PDF report from today's stamped GeoStamp photos. */
object PdfReport {

    fun generateTodayPdf(ctx: Context): Uri? {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val items = ArrayList<Triple<Uri, String, Long>>()
        val resolver = ctx.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sel = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? AND " +
            MediaStore.Images.Media.DATE_TAKEN + " >= ?"
        val args = arrayOf("GeoStamp_%", startOfDay.toString())
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, sel, args,
            MediaStore.Images.Media.DATE_TAKEN + " ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            var count = 0
            while (c.moveToNext() && count < 40) {
                val name = c.getString(nameCol) ?: ""
                if (name.endsWith("_original.jpg")) continue
                val id = c.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                items.add(Triple(uri, name, c.getLong(dateCol)))
                count++
            }
        }
        if (items.isEmpty()) return null

        val doc = PdfDocument()
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = Paint().apply { color = Color.BLACK; textSize = 16f }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        canvas.drawText("GeoStamp Report", 40f, 80f, titlePaint)
        canvas.drawText(dateFmt.format(Date()), 40f, 120f, bodyPaint)
        canvas.drawText(items.size.toString() + " photos", 40f, 150f, bodyPaint)
        doc.finishPage(page)

        var pageNum = 2
        for ((uri, name, taken) in items) {
            try {
                val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: continue
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
                page = doc.startPage(pageInfo)
                canvas = page.canvas
                val maxW = 515f
                val scale = maxW / bmp.width
                val h = bmp.height * scale
                val scaled = Bitmap.createScaledBitmap(bmp, maxW.toInt(), h.toInt(), true)
                canvas.drawBitmap(scaled, 40f, 40f, null)
                canvas.drawText(name, 40f, 40f + h + 30f, bodyPaint)
                canvas.drawText(timeFmt.format(Date(taken)), 40f, 40f + h + 55f, bodyPaint)
                doc.finishPage(page)
                pageNum++
                if (bmp != scaled) bmp.recycle()
                scaled.recycle()
            } catch (e: Exception) {
                continue
            }
        }

        val pdfName = "GeoStamp_Report_" + dateFmt.format(Date()) + ".pdf"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, pdfName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GeoStamp")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { doc.writeTo(it) }
                doc.close()
                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "GeoStamp"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, pdfName)
                FileOutputStream(file).use { doc.writeTo(it) }
                doc.close()
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("application/pdf"), null)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            try {
                doc.close()
            } catch (ignored: Exception) {
            }
            null
        }
    }
}
