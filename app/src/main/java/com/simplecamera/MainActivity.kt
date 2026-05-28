package com.simplecamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.simplecamera.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    enum class Mode { CAPTURE, BRIGHTNESS, ZOOM }

    private lateinit var binding: ActivityMainBinding
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var soundPool: SoundPool
    private var shutterSoundId = 0
    private var currentMode = Mode.CAPTURE
    private var zoomRatio = 1f
    private var requestedEVIndex = 0
    private var softwareEVStops = 0f
    private var signedInAccount: GoogleSignInAccount? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.msg_camera_permission_required), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            signedInAccount = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(Exception::class.java)
            updateSignInIndicator()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_sign_in_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        shutterSoundId = soundPool.load(this, R.raw.shutter, 1)

        setupModeButtons()
        checkPermissionsAndStartCamera()
        restoreGoogleSignIn()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        // Hide only the status bar; keep navigation bar visible so home/back always work
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun setupModeButtons() {
        binding.btnShutter.setOnClickListener { setMode(Mode.CAPTURE) }
        binding.btnBrightness.setOnClickListener { setMode(Mode.BRIGHTNESS) }
        binding.btnZoom.setOnClickListener { setMode(Mode.ZOOM) }
        binding.btnGoogleSignIn.setOnClickListener { signInToGoogle() }
        setMode(Mode.CAPTURE)
    }

    private fun setMode(mode: Mode) {
        currentMode = mode
        binding.btnShutter.isSelected = mode == Mode.CAPTURE
        binding.btnBrightness.isSelected = mode == Mode.BRIGHTNESS
        binding.btnZoom.isSelected = mode == Mode.ZOOM

        binding.statusText.visibility = if (mode == Mode.CAPTURE) View.GONE else View.VISIBLE
        if (mode != Mode.CAPTURE) refreshStatusText()
    }

    private fun refreshStatusText() {
        val cam = camera ?: return
        binding.statusText.text = when (currentMode) {
            Mode.BRIGHTNESS -> "EV $requestedEVIndex"
            Mode.ZOOM -> {
                val ratio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                "%.1fx".format(ratio)
            }
            Mode.CAPTURE -> ""
        }
    }

    // Intercept volume keys before audio system sees them
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_UP) return true
            if (event.action == KeyEvent.ACTION_DOWN) {
                val isRepeat = event.repeatCount > 0
                // For capture mode, only fire once per press (no repeat)
                if (isRepeat && currentMode == Mode.CAPTURE) return true
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) handleVolumeUp()
                else handleVolumeDown()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleVolumeUp() {
        when (currentMode) {
            Mode.CAPTURE -> takePhoto()
            Mode.BRIGHTNESS -> adjustExposure(+1)
            Mode.ZOOM -> adjustZoom(+0.1f)
        }
    }

    private fun handleVolumeDown() {
        when (currentMode) {
            Mode.CAPTURE -> takePhoto()
            Mode.BRIGHTNESS -> adjustExposure(-1)
            Mode.ZOOM -> adjustZoom(-0.1f)
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val stops = softwareEVStops
        playShutterClick()
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()

                    val rotated = rotateBitmap(bitmap, rotation)
                    val final = if (stops > 0f) applyBrightness(rotated, stops) else rotated
                    val uri = savePng(final)

                    val account = signedInAccount
                    if (account != null && uri != null) {
                        lifecycleScope.launch {
                            GooglePhotosUploader.upload(this@MainActivity, account, uri)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) { }
            }
        )
    }

    private fun adjustExposure(delta: Int) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val stepSize = state.exposureCompensationStep.toFloat()

        requestedEVIndex = (requestedEVIndex + delta).coerceIn(-30, range.upper)
        val hardwareIndex = requestedEVIndex.coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(hardwareIndex)

        softwareEVStops = maxOf(0f, (range.lower - requestedEVIndex) * stepSize)
        updateBrightnessOverlay(softwareEVStops)
        binding.statusText.text = "EV $requestedEVIndex"
    }

    private fun adjustZoom(delta: Float) {
        val cam = camera ?: return
        zoomRatio = (zoomRatio + delta).coerceIn(ZOOM_MIN, ZOOM_MAX)
        cam.cameraControl.setZoomRatio(zoomRatio)
        if (currentMode == Mode.ZOOM) {
            binding.statusText.text = "%.1fx".format(zoomRatio)
        }
    }

    private fun checkPermissionsAndStartCamera() {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                camera?.cameraControl?.setZoomRatio(zoomRatio)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun restoreGoogleSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(PHOTOS_SCOPE))) {
            signedInAccount = account
            updateSignInIndicator()
        }
    }

    private fun signInToGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(PHOTOS_SCOPE))
            .requestEmail()
            .build()
        signInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun updateSignInIndicator() {
        val account = signedInAccount
        binding.btnGoogleSignIn.text = account?.email ?: getString(R.string.btn_google_photos)
    }

    private fun playShutterClick() {
        soundPool.play(shutterSoundId, 1f, 1f, 1, 0, 1f)
    }

    private fun updateBrightnessOverlay(stops: Float) {
        val alpha = if (stops <= 0f) 0f
                    else (1f - Math.pow(2.0, -stops.toDouble()).toFloat()).coerceIn(0f, 0.95f)
        binding.brightnessOverlay.alpha = alpha
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer.apply { rewind() }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun applyBrightness(bitmap: Bitmap, stops: Float): Bitmap {
        val scale = Math.pow(2.0, -stops.toDouble()).toFloat()
        val cm = ColorMatrix(floatArrayOf(
            scale, 0f,    0f,    0f, 0f,
            0f,    scale, 0f,    0f, 0f,
            0f,    0f,    scale, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(bitmap, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return result
    }

    private fun savePng(bitmap: Bitmap): android.net.Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SimpleCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        soundPool.release()
    }

    companion object {
        const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"
        const val ZOOM_MIN = 0.5f
        const val ZOOM_MAX = 4.0f
    }
}
