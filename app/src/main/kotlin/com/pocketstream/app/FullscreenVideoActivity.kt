package com.pocketstream.app

import com.pocketstream.app.BuildConfig
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import com.pocketstream.app.databinding.ActivityFullscreenVideoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.pocketstream.app.util.TimeUtils

/**
 * Fullscreen landscape video player activity.
 * Displays UDP video stream in immersive fullscreen mode.
 */
class FullscreenVideoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FullscreenVideoActivity"
        private const val STREAM_CONNECTION_TIMEOUT_MS = 30000L // 30 seconds for UDP stream
        private const val STALE_THRESHOLD_MS = 3000L
        private const val HEALTH_CHECK_INTERVAL_MS = 500L
    }

    private lateinit var binding: ActivityFullscreenVideoBinding
    private lateinit var preferencesManager: PreferencesManager
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var streamUrl: String? = null

    // Recording state
    private var isRecording = false
    private var recordingStartTime: Long = 0
    private var recordingFile: File? = null
    private var timerJob: Job? = null

    // Stream health monitoring
    private var healthCheckJob: Job? = null
    private var lastReadBytes: Long = 0
    private var staleSinceTimestamp: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Keep screen on during video playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityFullscreenVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize preferences manager
        preferencesManager = PreferencesManager(this)

        // Get stream URL from intent
        streamUrl = intent.getStringExtra(MainActivity.EXTRA_STREAM_URL)

        if (streamUrl == null) {
            Toast.makeText(this, "No stream URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Setup fullscreen immersive mode
        setupFullscreenMode()

        // Setup back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Setup screenshot button
        binding.screenshotButton.setOnClickListener {
            takeScreenshot()
        }

        // Setup record button
        binding.recordButton.setOnClickListener {
            toggleRecording()
        }

        // Initialize video player
        initializePlayer()
    }

    /**
     * Takes a screenshot of the current video frame using PixelCopy API.
     * PixelCopy is required for SurfaceView content capture.
     */
    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Toast.makeText(this, "Screenshot requires Android 7.0 or higher", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val surfaceView = binding.fullscreenPlayerView

            // Check if surface is valid
            if (!surfaceView.holder.surface.isValid) {
                Toast.makeText(this, "Video surface not ready", Toast.LENGTH_SHORT).show()
                return
            }

            // Create bitmap with SurfaceView dimensions
            val bitmap = Bitmap.createBitmap(
                surfaceView.width,
                surfaceView.height,
                Bitmap.Config.ARGB_8888
            )

            // Define the source rect (entire surface)
            val srcRect = Rect(0, 0, surfaceView.width, surfaceView.height)

            // Use PixelCopy to capture the SurfaceView content
            PixelCopy.request(
                surfaceView,
                srcRect,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        // Save bitmap on background thread
                        lifecycleScope.launch(Dispatchers.IO) {
                            saveBitmapToFile(bitmap)
                        }
                    } else {
                        bitmap.recycle()
                        runOnUiThread {
                            val errorMsg = when (copyResult) {
                                PixelCopy.ERROR_UNKNOWN -> "Unknown error"
                                PixelCopy.ERROR_TIMEOUT -> "Timeout"
                                PixelCopy.ERROR_SOURCE_NO_DATA -> "No video data"
                                PixelCopy.ERROR_SOURCE_INVALID -> "Invalid source"
                                PixelCopy.ERROR_DESTINATION_INVALID -> "Invalid destination"
                                else -> "Error code: $copyResult"
                            }
                            Toast.makeText(this, "Screenshot failed: $errorMsg", Toast.LENGTH_SHORT).show()
                            Log.e(TAG, "PixelCopy failed: $errorMsg")
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            Toast.makeText(this, "Failed to take screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Saves a bitmap to file in the Pictures directory.
     * Uses MediaStore API for Android 10+ compatibility.
     */
    private suspend fun saveBitmapToFile(bitmap: Bitmap) {
        try {
            val timestamp = TimeUtils.generateTimestamp()
            val filename = "PS_Screenshot_$timestamp.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketStream")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }

                    // Mark as complete
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@FullscreenVideoActivity,
                            "Screenshot saved to Pictures/PocketStream/$filename",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    Log.d(TAG, "Screenshot saved via MediaStore: $filename")
                } else {
                    throw Exception("Failed to create MediaStore entry")
                }
            } else {
                // Legacy method for Android 9 and below
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, "PocketStream")
                appDir.mkdirs()

                val file = File(appDir, filename)

                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                // Notify media scanner
                MediaScannerConnection.scanFile(
                    this@FullscreenVideoActivity,
                    arrayOf(file.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FullscreenVideoActivity,
                        "Screenshot saved to Pictures/PocketStream/$filename",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Log.d(TAG, "Screenshot saved: ${file.absolutePath}")
            }

            bitmap.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot", e)
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@FullscreenVideoActivity,
                    "Failed to save screenshot: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Toggles recording on/off.
     */
    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    /**
     * Starts recording the video stream to an MP4 file.
     * Records to app's cache directory first, then copies to MediaStore when done.
     */
    private fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        try {
            // Use app's cache directory for recording (no permission needed)
            val cacheDir = File(cacheDir, "recordings")
            cacheDir.mkdirs()

            // Generate filename with timestamp
            val timestamp = TimeUtils.generateTimestamp()
            val filename = "TVS_Recording_$timestamp.mp4"
            recordingFile = File(cacheDir, filename)

            val outputPath = recordingFile!!.absolutePath
            Log.d(TAG, "Starting recording to: $outputPath")

            // Stop current playback
            mediaPlayer?.stop()

            // Create new media with recording options
            val media = Media(libVLC, Uri.parse(streamUrl!!))

            // Build sout option for recording
            val soutOption = buildRecordingSoutOption()
            media.addOption(soutOption)
            media.addOption(":sout-keep")
            media.addOption(":network-caching=1000")

            mediaPlayer?.media = media
            media.release()

            // Start playback (which also starts recording)
            mediaPlayer?.play()

            // Update state
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            // Update UI
            updateRecordingUI(true)
            startRecordingTimer()

            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Recording started: $filename")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_LONG).show()
            isRecording = false
        }
    }

    /**
     * Stops recording and saves the file to MediaStore.
     * @param restartPlayback If true, restarts normal playback after stopping recording.
     *                        Pass false when called from onDestroy to avoid playing on a destroyed surface.
     */
    private fun stopRecording(restartPlayback: Boolean = true) {
        if (!isRecording) {
            Log.w(TAG, "Not currently recording")
            return
        }

        try {
            Log.d(TAG, "Stopping recording (restartPlayback=$restartPlayback)")

            // Stop timer
            stopRecordingTimer()

            // Stop playback to finalize recording
            mediaPlayer?.stop()

            // Restart normal playback only if the activity is still alive
            if (restartPlayback) {
                val media = Media(libVLC, Uri.parse(streamUrl!!))
                media.addOption(":network-caching=1000")
                media.addOption(":live-caching=500")
                mediaPlayer?.media = media
                media.release()
                mediaPlayer?.play()
            }

            // Update state
            isRecording = false

            // Update UI
            updateRecordingUI(false)

            // Copy recording from cache to MediaStore on background thread.
            // Use a standalone CoroutineScope so the save completes even if
            // the activity is destroyed (e.g., when called from onDestroy).
            val tempFile = recordingFile
            if (tempFile != null && tempFile.exists()) {
                CoroutineScope(Dispatchers.IO).launch {
                    saveRecordingToMediaStore(tempFile)
                }
            } else {
                Toast.makeText(this, "Recording file not found", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            Toast.makeText(this, "Error stopping recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copies recording from cache to MediaStore for gallery visibility.
     */
    private suspend fun saveRecordingToMediaStore(tempFile: File) {
        try {
            val filename = tempFile.name

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/PocketStream")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }

                    // Mark as complete
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

                    // Delete temp file
                    tempFile.delete()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "Recording saved to Pictures/PocketStream/$filename",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    Log.i(TAG, "Recording saved via MediaStore: $filename")
                } else {
                    throw Exception("Failed to create MediaStore entry")
                }
            } else {
                // Legacy method for Android 9 and below - copy to Pictures
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, "PocketStream")
                appDir.mkdirs()

                val destFile = File(appDir, filename)
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()

                // Notify media scanner
                MediaScannerConnection.scanFile(
                    this@FullscreenVideoActivity,
                    arrayOf(destFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FullscreenVideoActivity,
                        "Recording saved to Pictures/PocketStream/$filename",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Log.i(TAG, "Recording saved: ${destFile.absolutePath}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving recording to MediaStore", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "Failed to save recording: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Starts the recording timer that updates the UI every second.
     */
    private fun startRecordingTimer() {
        timerJob = lifecycleScope.launch {
            while (isRecording) {
                val elapsedSeconds = (System.currentTimeMillis() - recordingStartTime) / 1000

                withContext(Dispatchers.Main) {
                    binding.recordingTimer.text = TimeUtils.formatUptime(elapsedSeconds)
                }

                delay(1000)
            }
        }
    }

    /**
     * Stops the recording timer.
     */
    private fun stopRecordingTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * Updates the UI to reflect recording state.
     */
    private fun updateRecordingUI(recording: Boolean) {
        if (recording) {
            binding.recordingIndicator.visibility = View.VISIBLE
            binding.recordButton.text = "Stop"
            binding.recordButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.recording_button_active)
            )

            // Start blinking animation for recording dot
            startRecordingDotAnimation()
        } else {
            binding.recordingIndicator.visibility = View.GONE
            binding.recordButton.text = "Record"
            binding.recordButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.recording_button_inactive)
            )
            binding.recordingTimer.text = "00:00"

            // Stop animation
            stopRecordingDotAnimation()
        }
    }

    /**
     * Starts blinking animation for the recording dot.
     */
    private fun startRecordingDotAnimation() {
        val animation = AlphaAnimation(1.0f, 0.3f).apply {
            duration = 500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.recordingDot.startAnimation(animation)
    }

    /**
     * Stops the recording dot animation.
     */
    private fun stopRecordingDotAnimation() {
        binding.recordingDot.clearAnimation()
    }

    private fun startStreamHealthMonitor() {
        healthCheckJob?.cancel()
        lastReadBytes = 0
        staleSinceTimestamp = 0
        healthCheckJob = lifecycleScope.launch {
            while (true) {
                val currentBytes = mediaPlayer?.media?.stats?.readBytes?.toLong() ?: 0L
                withContext(Dispatchers.Main) {
                    if (lastReadBytes == 0L && currentBytes == 0L) {
                        // Stats not yet available — skip to avoid false stale at startup
                    } else if (currentBytes > lastReadBytes) {
                        staleSinceTimestamp = 0
                        updateStaleUI(false, 0)
                    } else if (lastReadBytes > 0L) {
                        val now = System.currentTimeMillis()
                        if (staleSinceTimestamp == 0L) {
                            staleSinceTimestamp = now
                        }
                        val staleDurationMs = now - staleSinceTimestamp
                        if (staleDurationMs >= STALE_THRESHOLD_MS) {
                            updateStaleUI(true, staleDurationMs / 1000)
                        }
                    }
                    Unit
                }
                lastReadBytes = currentBytes
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun stopStreamHealthMonitor() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        lastReadBytes = 0
        staleSinceTimestamp = 0
        updateStaleUI(false, 0)
    }

    private fun updateStaleUI(stale: Boolean, seconds: Long) {
        if (stale) {
            binding.staleIndicator.text = "STALE ${seconds}s"
            binding.staleIndicator.visibility = View.VISIBLE
        } else {
            binding.staleIndicator.visibility = View.GONE
        }
    }

    /**
     * Builds the sout option string for recording.
     */
    private fun buildRecordingSoutOption(): String {
        val outputPath = recordingFile!!.absolutePath
        return ":sout=#duplicate{dst=display,dst=std{access=file,mux=mp4,dst=$outputPath}}"
    }

    /**
     * Sets up immersive fullscreen mode with hidden system bars.
     */
    private fun setupFullscreenMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController?.apply {
            // Hide both status and navigation bars
            hide(WindowInsetsCompat.Type.systemBars())
            // Set immersive sticky mode so bars reappear on swipe
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Initializes and starts the LibVLC player.
     */
    private fun initializePlayer() {
        Log.d(TAG, "Initializing fullscreen LibVLC player for: $streamUrl")

        lifecycleScope.launch {
            try {
                binding.loadingText.text = "Connecting to stream..."
                binding.loadingText.visibility = View.VISIBLE

                // Initialize LibVLC
                val options = ArrayList<String>().apply {
                    add("--network-caching=1000")
                    add("--live-caching=500")
                    add("--udp-timeout=10000")
                    if (BuildConfig.DEBUG) {
                        add("-vvv")
                    }
                }
                libVLC = LibVLC(this@FullscreenVideoActivity, options)
                Log.d(TAG, "LibVLC initialized")

                // Create MediaPlayer
                mediaPlayer = MediaPlayer(libVLC).apply {
                    val player = this

                    // Set event listener
                    setEventListener { event ->
                        when (event.type) {
                            MediaPlayer.Event.Opening -> {
                                runOnUiThread {
                                    binding.loadingText.visibility = View.VISIBLE
                                    binding.loadingText.text = "Connecting to stream..."
                                    Log.d(TAG, "LibVLC: Opening stream")
                                }
                            }
                            MediaPlayer.Event.Buffering -> {
                                runOnUiThread {
                                    if (event.buffering < 100f) {
                                        binding.loadingText.visibility = View.VISIBLE
                                        binding.loadingText.text = "Buffering... ${event.buffering.toInt()}%"
                                        Log.d(TAG, "LibVLC: Buffering ${event.buffering}%")
                                    } else {
                                        // Buffering complete, hide the text
                                        binding.loadingText.visibility = View.GONE
                                        staleSinceTimestamp = 0
                                        updateStaleUI(false, 0)
                                        Log.d(TAG, "LibVLC: Buffering complete (100%)")
                                    }
                                }
                            }
                            MediaPlayer.Event.Playing -> {
                                runOnUiThread {
                                    binding.loadingText.visibility = View.GONE
                                    startStreamHealthMonitor()
                                    Log.i(TAG, "LibVLC: Fullscreen stream ready")
                                }
                            }
                            MediaPlayer.Event.Stopped -> {
                                runOnUiThread {
                                    binding.loadingText.visibility = View.VISIBLE
                                    binding.loadingText.text = "Stream ended"
                                    stopStreamHealthMonitor()
                                    Log.d(TAG, "LibVLC: Stream stopped")
                                }
                            }
                            MediaPlayer.Event.EncounteredError -> {
                                runOnUiThread {
                                    Log.e(TAG, "LibVLC: Encountered error")
                                    binding.loadingText.visibility = View.VISIBLE
                                    binding.loadingText.text = "Stream error"
                                    stopStreamHealthMonitor()
                                    binding.staleIndicator.text = "STALE"
                                    binding.staleIndicator.visibility = View.VISIBLE

                                    Toast.makeText(
                                        this@FullscreenVideoActivity,
                                        "Stream error occurred",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            MediaPlayer.Event.Vout -> {
                                Log.d(TAG, "LibVLC: Video output event - vout count: ${event.voutCount}")
                            }
                            else -> {
                                Log.d(TAG, "LibVLC event: ${event.type}")
                            }
                        }
                    }

                    // Wait for SurfaceView to be ready before attaching
                    binding.fullscreenPlayerView.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.d(TAG, "Fullscreen SurfaceView created, attaching LibVLC output")
                            val vout: IVLCVout = player.vlcVout
                            if (!vout.areViewsAttached()) {
                                vout.setVideoView(binding.fullscreenPlayerView)
                                vout.attachViews()
                                Log.d(TAG, "LibVLC: Fullscreen video output attached")
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            Log.d(TAG, "Fullscreen SurfaceView changed: ${width}x${height}")
                            player.vlcVout.setWindowSize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            Log.d(TAG, "Fullscreen SurfaceView destroyed")
                        }
                    })

                    // If surface already exists, attach immediately
                    if (binding.fullscreenPlayerView.holder.surface.isValid) {
                        Log.d(TAG, "Fullscreen surface already valid, attaching LibVLC output now")
                        val vout: IVLCVout = vlcVout
                        vout.setVideoView(binding.fullscreenPlayerView)
                        vout.attachViews()
                    }

                    // Create media and start playback
                    val media = Media(libVLC, Uri.parse(streamUrl!!))
                    media.addOption(":network-caching=1000")
                    media.addOption(":live-caching=500")
                    this.media = media
                    media.release()

                    play()
                }

                Log.i(TAG, "LibVLC fullscreen player started")

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing fullscreen LibVLC player", e)
                binding.loadingText.visibility = View.VISIBLE
                binding.loadingText.text = "Error: ${e.message}"
                Toast.makeText(
                    this@FullscreenVideoActivity,
                    "Failed to initialize player: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't pause if recording - allow background operation
        if (!isRecording) {
            stopStreamHealthMonitor()
            mediaPlayer?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        mediaPlayer?.play()
        startStreamHealthMonitor()
    }

    override fun onDestroy() {
        // Stop recording if active before destroying — don't restart playback
        // since the surface and player are about to be released
        if (isRecording) {
            stopRecording(restartPlayback = false)
        }
        stopStreamHealthMonitor()

        super.onDestroy()
        mediaPlayer?.apply {
            vlcVout.detachViews()
            release()
        }
        mediaPlayer = null
        libVLC?.release()
        libVLC = null
        Log.d(TAG, "Fullscreen LibVLC player released")
    }
}
