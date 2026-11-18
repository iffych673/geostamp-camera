package com.geostamp.camera

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Launch screen: photo stats, recent stamped photos, quick actions. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var tvToday: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvWeek: TextView
    private lateinit var tvPlaces: TextView
    private lateinit var tvDate: TextView
    private lateinit var photoGrid: GridLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        tvToday = findViewById(R.id.tvToday)
        tvTotal = findViewById(R.id.tvTotal)
        tvWeek = findViewById(R.id.tvWeek)
        tvPlaces = findViewById(R.id.tvPlaces)
        tvDate = findViewById(R.id.tvDate)
        photoGrid = findViewById(R.id.photoGrid)
        tvEmpty = findViewById(R.id.tvEmpty)

        tvDate.text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US).format(Date())

        findViewById<Button>(R.id.btnOpenCamera).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btnPdfReport).setOnClickListener { createPdfReport() }

        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        loadDashboard()
    }

    private fun loadDashboard() {
        Thread {
            val photos = queryPhotos()
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val startOfWeek = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            var today = 0
            var week = 0
            for ((_, taken) in photos) {
                if (taken >= startOfDay) today++
                if (taken >= startOfWeek) week++
            }
            val places = GeoStamp.loadPois(this).size
            val recent = photos.take(12)
            runOnUiThread {
                tvToday.text = today.toString()
                tvTotal.text = photos.size.toString()
                tvWeek.text = week.toString()
                tvPlaces.text = places.toString()
                populateGrid(recent)
            }
        }.start()
    }

    /** (uri, takenMs) for stamped GeoStamp photos, newest first. */
    private fun queryPhotos(): List<Pair<Uri, Long>> {
        val out = ArrayList<Pair<Uri, Long>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sel = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?"
        val args = arrayOf("GeoStamp_%")
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, sel, args,
                MediaStore.Images.Media.DATE_TAKEN + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: ""
                    if (name.endsWith("_original.jpg")) continue
                    val id = c.getLong(idCol)
                    var taken = c.getLong(takenCol)
                    if (taken <= 0) taken = c.getLong(addedCol) * 1000L
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    out.add(Pair(uri, taken))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun populateGrid(recent: List<Pair<Uri, Long>>) {
        photoGrid.removeAllViews()
        tvEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        for ((uri, _) in recent) {
            val iv = ImageView(this)
            val size = (110 * resources.displayMetrics.density).toInt()
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = size
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            }
            iv.layoutParams = params
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setOnClickListener {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(view)
                } catch (e: Exception) {
                    Toast.makeText(this, "No image viewer found", Toast.LENGTH_SHORT).show()
                }
            }
            photoGrid.addView(iv)
            Thread {
                val bmp = loadThumb(uri)
                if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) }
            }.start()
        }
    }

    private fun loadThumb(uri: Uri): android.graphics.Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(400, 400), null)
            } else {
                @Suppress("DEPRECATION")
                contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createPdfReport() {
        Toast.makeText(this, "Building PDF…", Toast.LENGTH_SHORT).show()
        Thread {
            val uri = PdfReport.generateTodayPdf(this)
            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this, "No GeoStamp photos from today found", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share PDF report"))
            }
        }.start()
    }
}
