package com.fieldcam.timestamp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.location.Location
import android.media.MediaActionSound
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.fieldcam.timestamp.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    @Volatile private var settings = CameraSettings()
    @Volatile private var logoBitmap: Bitmap? = null
    private val telemetry = TelemetryState()
    private val tiles = MapTileCache()
    private lateinit var sensors: SensorLocationManager
    private lateinit var renderer: TelemetryOverlayRenderer

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var overlayEffect: OverlayEffect? = null
    private var activeRecording: Recording? = null
    private var recordingHasAudio = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = 0 // 0 off, 1 auto, 2 on, 3 torch
    private var batterySaverOn = false
    private var lastSavedUri: Uri? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val drawThread = HandlerThread("overlay-draw").apply { start() }
    private val drawHandler = Handler(drawThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shutterSound = MediaActionSound()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionsResult() }

    private val logoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importLogo(uri)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = CameraSettings.load(this)
        CameraSettings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        loadLogo()
        applyOrientationSetting()

        renderer = TelemetryOverlayRenderer(telemetry, tiles, { settings }, { logoBitmap })
        sensors = SensorLocationManager(this, telemetry)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        setupUi()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, mainHandler)

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (needed.all { granted(it) }) onPermissionsResult() else permissionLauncher.launch(needed)
    }

    override fun onResume() {
        super.onResume()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) sensors.start()
        updateRotation()
    }

    override fun onPause() {
        super.onPause()
        if (activeRecording == null) sensors.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        CameraSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        activeRecording?.stop()
        sensors.shutdown()
        tiles.shutdown()
        overlayEffect?.close()
        drawThread.quitSafely()
        cameraExecutor.shutdown()
        shutterSound.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionsResult() {
        if (!granted(Manifest.permission.CAMERA)) {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            return
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            sensors.start()
        } else {
            Toast.makeText(this, "Location permission denied – GPS & address will not be stamped.", Toast.LENGTH_LONG).show()
        }
        startCamera()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        val old = settings
        settings = CameraSettings.load(this)
        when (key) {
            "video_quality", "aspect_ratio" ->
                if (activeRecording == null) bindCamera()
                else Toast.makeText(this, "Applies after the current recording.", Toast.LENGTH_SHORT).show()
            "orientation" -> applyOrientationSetting()
            "record_audio" -> {
                activeRecording?.let { if (recordingHasAudio) it.mute(!settings.recordAudio) }
                updateTopBar()
            }
            "show_logo" -> if (settings.showLogo && logoBitmap == null) pickLogo()
        }
        if (old.batterySaverBrightness != settings.batterySaverBrightness && batterySaverOn) applyBatterySaver(true)
    }

    // ------------------------------------------------------------------- UI

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUi() {
        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.btnCapturePhoto.setOnClickListener { takePhoto() }
        binding.btnRecordVideo.setOnClickListener { toggleRecording() }
        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnLocation.setOnClickListener { showLocationPicker() }
        binding.btnBattery.setOnClickListener { applyBatterySaver(!batterySaverOn) }
        binding.btnMic.setOnClickListener {
            CameraSettings.prefs(this).edit().putBoolean("record_audio", !settings.recordAudio).apply()
        }
        binding.btnFlash.setOnClickListener {
            flashMode = (flashMode + 1) % 4
            applyFlash()
            updateTopBar()
        }
        binding.btnGallery.setOnClickListener {
            val uri = lastSavedUri ?: return@setOnClickListener Toast.makeText(this, "Nothing captured yet", Toast.LENGTH_SHORT).show()
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        updateTopBar()

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(z * detector.scaleFactor)
                return true
            }
        })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { applyBatterySaver(!batterySaverOn); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (batterySaverOn) return true
                val point = binding.previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev); tapDetector.onTouchEvent(ev); true
        }
        binding.dimOverlay.setOnTouchListener { _, ev -> tapDetector.onTouchEvent(ev); true }

        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun updateTopBar() {
        binding.btnFlash.text = when (flashMode) { 1 -> "⚡ Auto"; 2 -> "⚡ On"; 3 -> "🔦 Torch"; else -> "⚡ Off" }
        binding.btnMic.text = if (settings.recordAudio) "🎤 On" else "🔇 Off"
        binding.btnBattery.text = if (batterySaverOn) "🔋 On" else "🔋"
    }

    private fun applyOrientationSetting() {
        requestedOrientation = when (settings.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun applyBatterySaver(on: Boolean) {
        batterySaverOn = on
        val lp = window.attributes
        if (on) {
            val pct = settings.batterySaverBrightness.coerceIn(0, 100) / 100f
            val system = runCatching {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)
            lp.screenBrightness = (system * pct).coerceIn(0.01f, 1f)
            binding.dimOverlay.visibility = View.VISIBLE
            binding.dimOverlay.setBackgroundColor(Color.argb(((1f - pct) * 235).toInt(), 0, 0, 0))
            binding.dimHint.visibility = View.VISIBLE
            mainHandler.postDelayed({ binding.dimHint.visibility = View.GONE }, 2500)
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            binding.dimOverlay.visibility = View.GONE
            binding.dimHint.visibility = View.GONE
        }
        window.attributes = lp
        updateTopBar()
    }

    fun openSettings() {
        if (supportFragmentManager.findFragmentByTag("settings") == null) {
            SettingsSheet().show(supportFragmentManager, "settings")
        }
    }

    // --------------------------------------------------------------- camera

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) = updateRotation()
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    private fun updateRotation() {
        val rot = displayRotation()
        sensors.displayRotationDeg = rot * 90
        imageCapture?.targetRotation = rot
        if (activeRecording == null) videoCapture?.targetRotation = rot
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val rotation = displayRotation()
        val aspect = if (settings.aspectRatio == "16:9") AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()

        val preview = Preview.Builder().setResolutionSelector(resolution).setTargetRotation(rotation).build()
        preview.surfaceProvider = binding.previewView.surfaceProvider

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .build()

        // Keep the same VideoCapture while a (persistent) recording runs, so the
        // camera can be switched and effects changed without stopping the video.
        if (videoCapture == null || activeRecording == null) {
            val quality = when (settings.videoQuality) {
                "UHD" -> Quality.UHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> Quality.FHD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setAspectRatio(aspect)
                .build()
            videoCapture = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
        }

        overlayEffect?.close()
        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.IMAGE_CAPTURE or CameraEffect.VIDEO_CAPTURE,
            0, drawHandler
        ) { it.printStackTrace() }
        effect.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val crop = frame.cropRect
            val cw = crop.width().toFloat()
            val ch = crop.height().toFloat()
            val rot = frame.rotationDegrees
            val upW = if (rot % 180 == 0) cw else ch
            val upH = if (rot % 180 == 0) ch else cw
            c.save()
            c.translate(crop.left.toFloat(), crop.top.toFloat())
            when (rot) {
                90 -> { c.translate(0f, ch); c.rotate(270f) }
                180 -> { c.translate(cw, ch); c.rotate(180f) }
                270 -> { c.translate(cw, 0f); c.rotate(90f) }
            }
            if (frame.isMirroring) c.scale(-1f, 1f, upW / 2f, 0f)
            try {
                renderer.draw(c, upW.toInt(), upH.toInt())
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            c.restore()
            true
        }
        overlayEffect = effect

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(imageCapture!!)
            .addUseCase(videoCapture!!)
            .addEffect(effect)
            .build()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, group)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        applyFlash()
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            binding.zoomSlider.progress = (state.linearZoom * 100).toInt()
        }
    }

    private fun applyFlash() {
        imageCapture?.flashMode = when (flashMode) {
            1 -> ImageCapture.FLASH_MODE_AUTO; 2 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(flashMode == 3)
    }

    private fun currentLocation(): Location? {
        val lat = telemetry.latitude ?: return null
        val lon = telemetry.longitude ?: return null
        return Location("fused").apply {
            latitude = lat; longitude = lon
            telemetry.altitudeMeters?.let { altitude = it }
            time = System.currentTimeMillis()
        }
    }

    private fun baseName(): String =
        settings.filePrefix + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    // ---------------------------------------------------------------- photo

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = baseName()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TimestampCamera")
            }
        }
        val sdAvailable = StorageHelper.sdCardVolume(this) != null
        if (settings.saveToSdCard && !sdAvailable) {
            Toast.makeText(this, "No SD card found – saving to internal storage", Toast.LENGTH_SHORT).show()
        }
        val metadata = ImageCapture.Metadata().apply {
            location = currentLocation()
            isReversedHorizontal = false
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, StorageHelper.imagesCollection(this, settings.saveToSdCard), values
        ).setMetadata(metadata).build()

        if (settings.shutterSound) shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        flashScreen()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                lastSavedUri = output.savedUri
                val p = CameraSettings.prefs(this@MainActivity)
                p.edit().putInt("photo_index", p.getInt("photo_index", 1) + 1).apply()
                runOnUiThread { Toast.makeText(this@MainActivity, "Saved $name.jpg", Toast.LENGTH_SHORT).show() }
            }
            override fun onError(exc: ImageCaptureException) {
                exc.printStackTrace()
                runOnUiThread { Toast.makeText(this@MainActivity, "Photo failed: ${exc.message}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun flashScreen() {
        binding.captureFlash.alpha = 0.7f
        binding.captureFlash.visibility = View.VISIBLE
        binding.captureFlash.animate().alpha(0f).setDuration(180).withEndAction {
            binding.captureFlash.visibility = View.GONE
        }.start()
    }

    // ---------------------------------------------------------------- video

    @OptIn(ExperimentalPersistentRecording::class)
    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
            return
        }
        val vc = videoCapture ?: return
        val name = baseName()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TimestampCamera")
            }
        }
        val outputBuilder = MediaStoreOutputOptions.Builder(
            contentResolver, StorageHelper.videoCollection(this, settings.saveToSdCard)
        ).setContentValues(values)
        currentLocation()?.let { outputBuilder.setLocation(it) }

        var pending = vc.output.prepareRecording(this, outputBuilder.build())
        recordingHasAudio = settings.recordAudio && granted(Manifest.permission.RECORD_AUDIO)
        if (recordingHasAudio) pending = pending.withAudioEnabled()
        if (settings.recordAudio && !recordingHasAudio) {
            Toast.makeText(this, "Microphone permission denied – recording without audio", Toast.LENGTH_SHORT).show()
        }
        if (settings.shutterSound) shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)

        activeRecording = pending.asPersistentRecording().start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.btnRecordVideo.text = "■"
                    binding.recTimer.visibility = View.VISIBLE
                }
                is VideoRecordEvent.Status -> {
                    val secs = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    binding.recTimer.text = String.format(Locale.US, "● %02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    binding.btnRecordVideo.text = ""
                    binding.recTimer.visibility = View.GONE
                    activeRecording = null
                    if (settings.shutterSound) shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Toast.makeText(this, "Video error ${event.error}: ${event.cause?.message}", Toast.LENGTH_LONG).show()
                    } else {
                        lastSavedUri = event.outputResults.outputUri
                        Toast.makeText(this, "Saved $name.mp4", Toast.LENGTH_SHORT).show()
                    }
                    updateRotation()
                }
            }
        }
    }

    // ------------------------------------------------------------- location

    fun showLocationPicker() {
        val fixed = listOf("📡 Automatic address (from GPS)", "✏️ Type a custom location / text…")
        var nearby: List<android.location.Address> = emptyList()
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
            (fixed + "⏳ Searching nearby places…").toMutableList())
        val dialog = AlertDialog.Builder(this)
            .setTitle("Location shown on camera")
            .setAdapter(adapter) { _, which ->
                val p = CameraSettings.prefs(this).edit()
                when (which) {
                    0 -> p.putString("custom_location", "").putBoolean("show_address", true).apply()
                    1 -> promptCustomLocation()
                    else -> nearby.getOrNull(which - 2)?.getAddressLine(0)?.let {
                        p.putString("custom_location", it).putBoolean("show_address", true).apply()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        sensors.nearbyAddresses { list ->
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                nearby = list
                adapter.clear()
                adapter.addAll(fixed)
                if (list.isEmpty()) adapter.add("(No nearby places yet – waiting for GPS / network)")
                else adapter.addAll(list.map { "📍 " + (it.getAddressLine(0) ?: "") })
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun promptCustomLocation() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(settings.customLocation)
            hint = "e.g. Central Park Zoo 🦁"
        }
        AlertDialog.Builder(this)
            .setTitle("Custom location")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                CameraSettings.prefs(this).edit()
                    .putString("custom_location", input.text.toString().trim())
                    .putBoolean("show_address", true).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----------------------------------------------------------------- logo

    fun pickLogo() = logoPicker.launch("image/*")

    private fun logoFile() = File(filesDir, "logo.png")

    private fun importLogo(uri: Uri) {
        cameraExecutor.execute {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@execute
                val scale = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                logoFile().outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                logoBitmap = scaled
                runOnUiThread {
                    CameraSettings.prefs(this).edit().putBoolean("show_logo", true).apply()
                    Toast.makeText(this, "Logo set", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not load image: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun loadLogo() {
        val f = logoFile()
        if (f.exists()) logoBitmap = BitmapFactory.decodeFile(f.absolutePath)
    }

    fun hasSdCard() = StorageHelper.sdCardVolume(this) != null
}
