package com.geostamp.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var gpsStatus: TextView
    private lateinit var stampPreview: TextView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private var lastAzimuth: Float? = null
    private var sensorManager: SensorManager? = null
    private var accelReading: FloatArray? = null
    private var magnetReading: FloatArray? = null

    // Live stamp preview state
    private var lastAddress: String? = null
    private var lastGeocodeLat = 0.0
    private var lastGeocodeLon = 0.0
    private var lastGeocodeMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            refreshStampPreview()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            updateStatus()
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        gpsStatus = findViewById(R.id.gpsStatus)
        stampPreview = findViewById(R.id.stampPreview)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<ImageButton>(R.id.btnShutter).setOnClickListener { capturePhoto() }
        findViewById<ImageButton>(R.id.btnGallery).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivity(intent)
        }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { view ->
            showSettingsMenu(view)
        }

        refreshStampPreview()
        uiHandler.post(clockTick)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        if (hasPermissions()) {
            startCamera()
            startLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                100
            )
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
            startLocation()
        } else {
            Toast.makeText(this, "Camera and location permission are required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: " + e.message, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("MissingPermission")
    private fun startLocation() {
        if (!hasPermissions()) return
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener)
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 10f, locationListener)
            val last = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) {
                lastLocation = last
                updateStatus()
            }
        } catch (e: SecurityException) {
            // permissions were checked above
        }
    }

    private fun updateStatus() {
        val loc = lastLocation
        gpsStatus.text = if (loc != null) {
            "GPS %.5f, %.5f (±%.0f m)".format(Locale.US, loc.latitude, loc.longitude, loc.accuracy)
        } else {
            "Waiting for GPS…"
        }
        refreshStampPreview()
        maybeReverseGeocode(loc)
    }

    /** Live stamp preview: the same lines the photo will be stamped with. */
    private fun refreshStampPreview() {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val dateLine = GeoStamp.formatStampDate(fmt.format(Date()))
        val loc = lastLocation
        val address = lastAddress ?: if (loc == null) "Waiting for GPS…" else "Locating address…"
        val coords = if (loc != null) {
            "Lat %.5f, Lon %.5f".format(Locale.US, loc.latitude, loc.longitude)
        } else {
            "Lat —, Lon —"
        }
        stampPreview.text = "$address\n$coords\n$dateLine"
    }

    /** Throttled background reverse-geocode so the preview shows a real address. */
    private fun maybeReverseGeocode(loc: Location?) {
        if (loc == null) return
        val now = System.currentTimeMillis()
        val moved = FloatArray(1)
        Location.distanceBetween(lastGeocodeLat, lastGeocodeLon, loc.latitude, loc.longitude, moved)
        if (lastAddress != null && moved[0] < 50f && now - lastGeocodeMs < 60_000L) return
        lastGeocodeLat = loc.latitude
        lastGeocodeLon = loc.longitude
        lastGeocodeMs = now
        val lat = loc.latitude
        val lon = loc.longitude
        Thread {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
                if (list != null && list.isNotEmpty()) {
                    val line = list[0].getAddressLine(0)
                    if (!line.isNullOrBlank()) {
                        lastAddress = line
                        runOnUiThread { refreshStampPreview() }
                    }
                }
            } catch (e: Exception) {
                // keep the coords-only preview
            }
        }.start()
    }

    private fun showSettingsMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.poi))
        popup.menu.add(0, 2, 0, getString(R.string.signature))
        popup.menu.add(0, 3, 0, getString(R.string.pdf))
        popup.menu.add(0, 4, 0, getString(R.string.dashboard))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showPoiDialog()
                2 -> showSignatureDialog()
                3 -> createPdfReport()
                4 -> finish()
            }
            true
        }
        popup.show()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) accelReading = event.values.clone()
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magnetReading = event.values.clone()
        val acc = accelReading ?: return
        val mag = magnetReading ?: return
        val r = FloatArray(9)
        val i = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, i, acc, mag)) {
            val o = FloatArray(3)
            SensorManager.getOrientation(r, o)
            var az = Math.toDegrees(o[0].toDouble()).toFloat()
            if (az < 0) az += 360f
            lastAzimuth = az
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "capture_tmp.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processCapturedFile(file)
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: " + exc.message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun processCapturedFile(file: File) {
        Toast.makeText(this, "Processing stamp…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val raw = BitmapFactory.decodeFile(file.absolutePath)
                if (raw == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Could not read photo", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val loc = lastLocation
                val lat = loc?.latitude ?: 0.0
                val lon = loc?.longitude ?: 0.0
                val isMock = loc?.isFromMockProvider ?: false
                var address = "Location unavailable"
                if (loc != null) {
                    try {
                        @Suppress("DEPRECATION")
                        val list = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
                        if (list != null && list.isNotEmpty()) {
                            address = list[0].getAddressLine(0) ?: address
                        }
                    } catch (e: Exception) {
                        // keep fallback text
                    }
                }
                val (timeText, timeSource) = GeoStamp.fetchTime()
                val weather = if (loc != null) GeoStamp.fetchWeather(lat, lon) else null
                val poi = if (loc != null) GeoStamp.findPoi(this, lat, lon) else null
                val signature = GeoStamp.getSignature(this)
                val mapSize = minOf((raw.width * 0.22).toInt(), 768)
                val map = if (loc != null && mapSize > 0) GeoStamp.fetchMapBitmap(lat, lon, mapSize) else null
                val data = GeoStamp.StampData(
                    timeText, timeSource, isMock, address, lat, lon,
                    lastAzimuth, weather, poi, signature
                )
                val stamped = GeoStamp.drawStamp(this, raw, data, map)
                val base = GeoStamp.stampFileName()
                val originalName = base.replace(".jpg", "_original.jpg")
                GeoStamp.saveJpegToGallery(this, raw, originalName)
                val stampedUri = GeoStamp.saveJpegToGallery(this, stamped, base)
                runOnUiThread {
                    if (stampedUri != null) {
                        Toast.makeText(this, "Saved to Pictures/GeoStamp", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Save failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Processing failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPoiDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_poi, null)
        val et = view.findViewById<EditText>(R.id.etPoiName)
        AlertDialog.Builder(this)
            .setTitle("Save this place")
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = et.text.toString().trim()
                val loc = lastLocation
                if (name.isEmpty()) {
                    Toast.makeText(this, "Enter a place name", Toast.LENGTH_SHORT).show()
                } else if (loc == null) {
                    Toast.makeText(this, "GPS not ready yet", Toast.LENGTH_SHORT).show()
                } else {
                    GeoStamp.savePoi(this, GeoStamp.Poi(name, loc.latitude, loc.longitude))
                    Toast.makeText(this, "Place saved: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSignatureDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_signature, null)
        val etCompany = view.findViewById<EditText>(R.id.etCompany)
        val etContact = view.findViewById<EditText>(R.id.etContact)
        val (company, contact) = GeoStamp.loadSignature(this)
        etCompany.setText(company)
        etContact.setText(contact)
        AlertDialog.Builder(this)
            .setTitle("Signature on photos")
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                GeoStamp.saveSignature(
                    this,
                    etCompany.text.toString().trim(),
                    etContact.text.toString().trim()
                )
                Toast.makeText(this, "Signature saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(clockTick)
        cameraExecutor.shutdown()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
        }
        sensorManager?.unregisterListener(this)
    }
}
