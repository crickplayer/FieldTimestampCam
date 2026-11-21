package com.fieldcam.timestamp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.media.AudioManager
import android.os.*
import android.provider.MediaStore
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.fieldcam.timestamp.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: CameraSettings
    private val telemetryState = TelemetryState()
    private lateinit var sensorLocationManager: SensorLocationManager
    private lateinit var renderer: TelemetryOverlayRenderer
    private lateinit var videoRenderer: TelemetryOverlayRenderer

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var intervalRunnable: Runnable? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            sensorLocationManager.start()
            startCamera()
        } else {
            Toast.makeText(this, "Camera, Mic, and Location permissions required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = CameraSettings.load(this)
        renderer = TelemetryOverlayRenderer(settings, telemetryState)
        videoRenderer = TelemetryOverlayRenderer(settings, telemetryState)
        binding.stampOverlay.renderer = renderer
        sensorLocationManager = SensorLocationManager(this, telemetryState)

        setupUI()

        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            sensorLocationManager.start()
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        binding.zoomSlider.visibility = if (settings.alwaysShowZoom) View.VISIBLE else View.GONE

        binding.btnCapturePhoto.setOnClickListener {
            if (settings.timerSeconds > 0) {
                handler.postDelayed({ takePhoto() }, settings.timerSeconds * 1000L)
            } else {
                takePhoto()
            }
        }

        binding.btnRecordVideo.setOnClickListener { toggleVideoRecording() }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        binding.btnSettings.setOnClickListener { openSettingsDialog() }

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(currentZoom * detector.scaleFactor)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (settings.touchToTakePhoto && event.action == android.view.MotionEvent.ACTION_UP) {
                takePhoto()
            }
            true
        }

        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val quality = when (settings.videoQuality) {
                VideoQualityPreset.LOW -> Quality.SD
                VideoQualityPreset.NORMAL -> Quality.HD
                VideoQualityPreset.HIGH -> Quality.FHD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // The live preview gets its stamp from StampOverlayView and photos
            // get theirs burned in after capture (see takePhoto). Only video
            // needs a CameraX effect, because each video frame must be stamped
            // while recording.
            val overlayEffect = OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                0,
                handler
            ) { error -> error.printStackTrace() }

            overlayEffect.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                // The overlay canvas keeps its old pixels, so wipe it first or
                // every frame's text piles up on top of the last one.
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                // Video frames arrive sideways (in the camera sensor's
                // orientation). Rotate the canvas so the stamp is upright
                // when the video is played back.
                val w = canvas.width.toFloat()
                val h = canvas.height.toFloat()
                val rotation = frame.rotationDegrees
                canvas.save()
                when (rotation) {
                    90 -> { canvas.translate(0f, h); canvas.rotate(-90f) }
                    180 -> { canvas.translate(w, h); canvas.rotate(180f) }
                    270 -> { canvas.translate(w, 0f); canvas.rotate(90f) }
                }
                val sideways = rotation == 90 || rotation == 270
                videoRenderer.draw(
                    canvas,
                    if (sideways) canvas.height else canvas.width,
                    if (sideways) canvas.width else canvas.height
                )
                canvas.restore()
                true
            }

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture!!)
                .addUseCase(videoCapture!!)
                .addEffect(overlayEffect)
                .build()

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, selector, useCaseGroup)

            camera?.cameraInfo?.zoomState?.observe(this) { state ->
                binding.zoomSlider.progress = (state.linearZoom * 100).toInt()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${settings.filePrefix}$timestamp"
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT && settings.mirrorFrontCamera

        // Capture into memory first so the stamp can be drawn onto the photo
        // before it is saved.
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val saved = try {
                    val photo = uprightBitmap(image, mirror)
                    // A separate renderer: this runs on the camera thread while
                    // the screen overlay keeps drawing on the main thread.
                    TelemetryOverlayRenderer(settings, telemetryState)
                        .draw(Canvas(photo), photo.width, photo.height)
                    savePhoto(photo, name)
                    photo.recycle()
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                } finally {
                    image.close()
                }
                if (saved) {
                    settings.photoIndex++
                    settings.save(this@MainActivity)
                }
                runOnUiThread {
                    val msg = if (saved) "Saved: $name.jpg" else "Could not save photo"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(exc: ImageCaptureException) {
                exc.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Photo failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** Turns the captured frame upright (and mirrors selfies if asked) as an editable bitmap. */
    private fun uprightBitmap(image: ImageProxy, mirror: Boolean): Bitmap {
        val raw = image.toBitmap()
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (turned !== raw) raw.recycle()
        if (turned.isMutable) return turned
        val editable = turned.copy(Bitmap.Config.ARGB_8888, true)
        turned.recycle()
        return editable
    }

    /** Saves the stamped photo to Documents/Timestamp and records time and GPS in its EXIF data. */
    private fun savePhoto(photo: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Documents/Timestamp")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused the new photo")
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                photo.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: throw IllegalStateException("Could not open the photo file")

            contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                val exifTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTime)
                exif.setAttribute(ExifInterface.TAG_DATETIME, exifTime)
                val lat = telemetryState.latitude
                val lon = telemetryState.longitude
                if (lat != null && lon != null) {
                    exif.setLatLong(lat, lon)
                    exif.setAltitude(telemetryState.altitudeMeters)
                }
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        val curRecording = activeRecording
        if (curRecording != null) {
            curRecording.stop()
            activeRecording = null
            binding.btnRecordVideo.text = "REC"
            return
        }

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (settings.useBluetoothMic) {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${settings.filePrefix}VID_$timestamp"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Timestamp")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = videoCapture?.output
            ?.prepareRecording(this, mediaStoreOutput)
            ?.withAudioEnabled()
            ?.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Start) {
                    binding.btnRecordVideo.text = "STOP"
                } else if (event is VideoRecordEvent.Finalize) {
                    binding.btnRecordVideo.text = "REC"
                    Toast.makeText(this, "Video saved: $name.mp4", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun openSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val cbCompass = CheckBox(this).apply { text = "Show Compass"; isChecked = settings.showCompass }
        val cbAltSpeed = CheckBox(this).apply { text = "Show Altitude and Speed"; isChecked = settings.showAltitudeSpeed }
        val cbIndex = CheckBox(this).apply { text = "Show Index Number"; isChecked = settings.showIndexNumber }
        val cbZoom = CheckBox(this).apply { text = "Always Display Zoom Slider"; isChecked = settings.alwaysShowZoom }
        val cbBtMic = CheckBox(this).apply { text = "Use Bluetooth Microphone"; isChecked = settings.useBluetoothMic }

        val etCustomText = EditText(this).apply {
            hint = "Display custom text / project note on camera"
            setText(settings.customText)
        }
        val etPrefix = EditText(this).apply {
            hint = "File name prefix (e.g. SITE_A_)"
            setText(settings.filePrefix)
        }

        val tvBgOpacity = TextView(this).apply { text = "Background Opacity" }
        val sbBgOpacity = SeekBar(this).apply { max = 255; progress = settings.backgroundOpacity }

        val tvTextOpacity = TextView(this).apply { text = "Text Opacity" }
        val sbTextOpacity = SeekBar(this).apply { max = 255; progress = settings.textOpacity }

        listOf(cbCompass, cbAltSpeed, cbIndex, cbZoom, cbBtMic, etCustomText, etPrefix,
               tvBgOpacity, sbBgOpacity, tvTextOpacity, sbTextOpacity).forEach { layout.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("OK") { _, _ ->
                settings.showCompass = cbCompass.isChecked
                settings.showAltitudeSpeed = cbAltSpeed.isChecked
                settings.showIndexNumber = cbIndex.isChecked
                settings.alwaysShowZoom = cbZoom.isChecked
                settings.useBluetoothMic = cbBtMic.isChecked
                settings.customText = etCustomText.text.toString()
                settings.filePrefix = etPrefix.text.toString()
                settings.backgroundOpacity = sbBgOpacity.progress
                settings.textOpacity = sbTextOpacity.progress
                settings.save(this)
                binding.zoomSlider.visibility = if (settings.alwaysShowZoom) View.VISIBLE else View.GONE
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorLocationManager.stop()
        cameraExecutor.shutdown()
        intervalRunnable?.let { handler.removeCallbacks(it) }
    }
}
