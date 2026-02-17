package com.pocketstream.app

import com.pocketstream.app.BuildConfig
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.net.Inet4Address
import java.net.NetworkInterface
import com.pocketstream.app.util.TimeUtils

/**
 * Foreground service for RTSP server streaming of UDP video.
 * Receives UDP stream via LibVLC and re-broadcasts via authenticated RTSP.
 */
class RtspServerService : LifecycleService() {

    companion object {
        private const val TAG = "RtspServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rtsp_server_channel"
        private const val NOTIFICATION_UPDATE_INTERVAL = 5
        private const val BANDWIDTH_WINDOW_SIZE = 5

        // Broadcast actions
        const val ACTION_STATUS_UPDATE = "com.pocketstream.app.RTSP_SERVER_STATUS_UPDATE"
        const val EXTRA_IS_STREAMING = "is_streaming"
        const val EXTRA_UPTIME_SECONDS = "uptime_seconds"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_BANDWIDTH_BYTES_PER_SEC = "bandwidth_bytes_per_sec"

        // Service actions
        const val ACTION_START = "com.pocketstream.app.action.START_RTSP_SERVER"
        const val ACTION_STOP = "com.pocketstream.app.action.STOP_RTSP_SERVER"

        // Intent extras
        const val EXTRA_INPUT_URL = "input_url"
        const val EXTRA_RTSP_PORT = "rtsp_port"
        const val EXTRA_TOKEN = "token"

        // Reconnection constants
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isStreaming = false
    private var startTime: Long = 0
    private var uptimeJob: Job? = null
    private var rtspPort: Int = 8554
    private var inputUrl: String = "udp://@:8600"
    private var streamToken: String = ""

    // Reconnection state
    private var reconnectAttempts: Int = 0
    private var reconnectJob: Job? = null


    // Bandwidth tracking
    private var lastBytesRead: Long = 0
    private var lastBytesTime: Long = 0
    private var currentBandwidth: Long = 0 // bytes per second
    private var bandwidthSamples = LongArray(BANDWIDTH_WINDOW_SIZE)
    private var bandwidthSampleIndex = 0
    private var bandwidthSampleCount = 0

    // Notification throttling
    private var notificationTickCount = 0

    /**
     * Gets the stream path, including token if configured.
     * @return Stream path like "/stream" or "/stream-abc123def456"
     */
    private fun getStreamPath(): String {
        return if (streamToken.isNotBlank()) {
            "/stream-$streamToken"
        } else {
            "/stream"
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "RtspServerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                inputUrl = intent.getStringExtra(EXTRA_INPUT_URL) ?: "udp://@:8600"
                rtspPort = intent.getIntExtra(EXTRA_RTSP_PORT, 8554)
                streamToken = intent.getStringExtra(EXTRA_TOKEN) ?: ""
                reconnectAttempts = 0
                startRtspServer()
            }
            ACTION_STOP -> {
                stopRtspServer()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.rtsp_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.rtsp_notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(uptimeSeconds: Long = 0): Notification {
        // Intent to open MainActivity
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop the service
        val stopIntent = Intent(this, RtspServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Format uptime
        val uptimeText = TimeUtils.formatUptime(uptimeSeconds)

        val localIp = getLocalIpAddress() ?: "unknown"
        val streamUrl = "rtsp://$localIp:$rtspPort${getStreamPath()}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.rtsp_notification_title))
            .setContentText("$streamUrl | Uptime: $uptimeText")
            .setSmallIcon(R.drawable.ic_broadcast)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.rtsp_notification_stop),
                stopPendingIntent
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startRtspServer() {
        if (isStreaming) {
            Log.w(TAG, "RTSP server already running")
            return
        }

        try {
            val isRtspInput = inputUrl.startsWith("rtsp://")
            Log.d(TAG, "Starting RTSP server: input=$inputUrl -> RTSP port $rtspPort")
            // Start foreground service with notification
            startForeground(NOTIFICATION_ID, createNotification())

            // Initialize LibVLC with options conditional on input protocol
            val options = ArrayList<String>().apply {
                add("--network-caching=1000")
                add("--live-caching=500")
                if (isRtspInput) {
                    add("--rtsp-tcp")
                    add("--rtsp-timeout=10")
                } else {
                    add("--udp-timeout=10000")
                }
                // Increase output RTSP session timeout (default is 60 seconds)
                add("--rtsp-timeout=0")  // 0 = no timeout for output
                if (BuildConfig.DEBUG) {
                    add("-vvv")
                }
            }
            libVLC = LibVLC(this, options)

            // Create MediaPlayer
            mediaPlayer = MediaPlayer(libVLC).apply {
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> {
                            Log.i(TAG, "RTSP server: Playing/Streaming")
                            reconnectAttempts = 0
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            Log.e(TAG, "RTSP server: Error encountered")
                            broadcastStatus(error = "Stream error occurred")
                            if (isRtspInput) {
                                scheduleReconnect()
                            }
                        }
                        MediaPlayer.Event.Stopped -> {
                            Log.d(TAG, "RTSP server: Stopped")
                        }
                    }
                }
            }

            // Create media with sout for RTSP streaming
            val media = Media(libVLC, Uri.parse(inputUrl))

            // Sout option: receive UDP and serve via RTSP
            // Explicitly bind to 0.0.0.0 to allow remote access (WiFi, Tailscale, etc.)
            // Token is included in path for URL-based authentication
            val streamPath = getStreamPath()
            val soutOption = ":sout=#rtp{sdp=rtsp://0.0.0.0:$rtspPort$streamPath}"
            Log.d(TAG, "RTSP stream path: $streamPath (token: ${if (streamToken.isNotBlank()) "enabled" else "disabled"})")
            media.addOption(soutOption)
            media.addOption(":sout-keep")
            media.addOption(":network-caching=1000")

            mediaPlayer?.media = media
            // Note: do NOT call media.release() here — MediaPlayer needs the
            // live Media reference so that mediaPlayer.media.stats remains
            // accessible for bandwidth tracking.

            // Start playback (which starts the RTSP server)
            mediaPlayer?.play()

            // Update state
            isStreaming = true
            startTime = System.currentTimeMillis()

            // Start uptime timer
            startUptimeTimer()

            // Broadcast initial status
            broadcastStatus()

            Log.i(TAG, "RTSP server started on port $rtspPort")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting RTSP server", e)
            releaseResources()
            broadcastStatus(error = e.message ?: "Unknown error")
            stopSelf()
        }
    }

    private fun stopRtspServer() {
        if (!isStreaming) {
            Log.w(TAG, "RTSP server not currently running")
            return
        }

        try {
            Log.d(TAG, "Stopping RTSP server")

            // Cancel any pending reconnect
            reconnectJob?.cancel()
            reconnectJob = null

            // Stop timer
            stopUptimeTimer()

            // Release VLC resources
            releaseResources()

            // Update state
            isStreaming = false

            // Broadcast final status
            broadcastStatus()

            Log.i(TAG, "RTSP server stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTSP server", e)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            broadcastStatus(error = "Connection lost after $MAX_RECONNECT_ATTEMPTS retries")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            reconnectAttempts++
            Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${RECONNECT_DELAY_MS}ms")
            broadcastStatus(error = "Reconnecting ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")
            delay(RECONNECT_DELAY_MS)

            try {
                releaseResources()
                // Re-initialize and restart
                val isRtspInput = inputUrl.startsWith("rtsp://")
                val options = ArrayList<String>().apply {
                    add("--network-caching=1000")
                    add("--live-caching=500")
                    if (isRtspInput) {
                        add("--rtsp-tcp")
                        add("--rtsp-timeout=10")
                    } else {
                        add("--udp-timeout=10000")
                    }
                    add("--rtsp-timeout=0")
                    if (BuildConfig.DEBUG) {
                        add("-vvv")
                    }
                }
                libVLC = LibVLC(this@RtspServerService, options)

                mediaPlayer = MediaPlayer(libVLC).apply {
                    setEventListener { event ->
                        when (event.type) {
                            MediaPlayer.Event.Playing -> {
                                Log.i(TAG, "RTSP server: Reconnected successfully")
                                reconnectAttempts = 0
                            }
                            MediaPlayer.Event.EncounteredError -> {
                                Log.e(TAG, "RTSP server: Error on reconnect attempt")
                                broadcastStatus(error = "Reconnect failed")
                                scheduleReconnect()
                            }
                            MediaPlayer.Event.Stopped -> {
                                Log.d(TAG, "RTSP server: Stopped")
                            }
                        }
                    }
                }

                val media = Media(libVLC, Uri.parse(inputUrl))
                val streamPath = getStreamPath()
                media.addOption(":sout=#rtp{sdp=rtsp://0.0.0.0:$rtspPort$streamPath}")
                media.addOption(":sout-keep")
                media.addOption(":network-caching=1000")
                mediaPlayer?.media = media
                mediaPlayer?.play()

                Log.i(TAG, "Reconnect attempt $reconnectAttempts started")
            } catch (e: Exception) {
                Log.e(TAG, "Error during reconnect attempt $reconnectAttempts", e)
                scheduleReconnect()
            }
        }
    }

    private fun releaseResources() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            Log.d(TAG, "MediaPlayer released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null

        try {
            libVLC?.release()
            Log.d(TAG, "LibVLC released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LibVLC", e)
        }
        libVLC = null
    }

    private fun startUptimeTimer() {
        // Initialize bandwidth tracking
        lastBytesRead = 0
        lastBytesTime = System.currentTimeMillis()
        currentBandwidth = 0
        bandwidthSamples = LongArray(BANDWIDTH_WINDOW_SIZE)
        bandwidthSampleIndex = 0
        bandwidthSampleCount = 0
        notificationTickCount = 0

        uptimeJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (isStreaming) {
                    updateBandwidth()
                    broadcastStatus()
                    notificationTickCount++
                    if (notificationTickCount >= NOTIFICATION_UPDATE_INTERVAL) {
                        notificationTickCount = 0
                        val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
                        val notificationManager = getSystemService(NotificationManager::class.java)
                        notificationManager.notify(NOTIFICATION_ID, createNotification(uptimeSeconds))
                    }
                }
            }
        }
    }

    /**
     * Updates bandwidth calculation from LibVLC media statistics.
     * Uses readBytes to track incoming UDP stream data.
     */
    private fun updateBandwidth() {
        try {
            val media = mediaPlayer?.media
            val stats = media?.stats
            if (stats != null) {
                val currentBytes = stats.readBytes.toLong()
                val sentBytes = stats.sentBytes.toLong()
                val demuxBytes = stats.demuxReadBytes.toLong()
                val currentTime = System.currentTimeMillis()
                val timeDelta = currentTime - lastBytesTime

                // Use the best available byte counter: readBytes, demuxReadBytes, or sentBytes
                val bestBytes = when {
                    currentBytes > 0 -> currentBytes
                    demuxBytes > 0 -> demuxBytes
                    sentBytes > 0 -> sentBytes
                    else -> 0L
                }

                if (timeDelta > 0 && lastBytesRead > 0 && bestBytes > 0) {
                    val bytesDelta = bestBytes - lastBytesRead
                    if (bytesDelta >= 0) {
                        val instantaneous = (bytesDelta * 1000) / timeDelta
                        bandwidthSamples[bandwidthSampleIndex] = instantaneous
                        bandwidthSampleIndex = (bandwidthSampleIndex + 1) % BANDWIDTH_WINDOW_SIZE
                        if (bandwidthSampleCount < BANDWIDTH_WINDOW_SIZE) bandwidthSampleCount++
                        var sum = 0L
                        for (i in 0 until bandwidthSampleCount) sum += bandwidthSamples[i]
                        currentBandwidth = sum / bandwidthSampleCount
                    }
                }

                lastBytesRead = bestBytes
                lastBytesTime = currentTime

            } else {
                Log.d(TAG, "Stats unavailable: media=${media != null}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting media stats", e)
        }
    }

    private fun stopUptimeTimer() {
        uptimeJob?.cancel()
        uptimeJob = null
    }

    private fun broadcastStatus(error: String? = null) {
        val uptimeSeconds = if (isStreaming) {
            (System.currentTimeMillis() - startTime) / 1000
        } else {
            0L
        }

        val localIp = getLocalIpAddress()
        val streamUrl = if (localIp != null && isStreaming) {
            "rtsp://$localIp:$rtspPort${getStreamPath()}"
        } else {
            null
        }

        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_IS_STREAMING, isStreaming)
            putExtra(EXTRA_UPTIME_SECONDS, uptimeSeconds)
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_BANDWIDTH_BYTES_PER_SEC, currentBandwidth)
            error?.let { putExtra(EXTRA_ERROR_MESSAGE, it) }
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Gets the local IP address, prioritizing Tailscale VPN addresses (100.x.x.x).
     * This ensures the stream URL works over Tailscale VPN connections.
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var tailscaleIp: String? = null
            var fallbackIp: String? = null

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val interfaceName = networkInterface.name.lowercase()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue

                        // Prioritize Tailscale IPs (100.x.x.x CGNAT range) or tailscale interface
                        if (ip.startsWith("100.") || interfaceName.contains("tailscale")) {
                            tailscaleIp = ip
                            Log.d(TAG, "Found Tailscale IP: $ip on interface $interfaceName")
                        } else if (fallbackIp == null) {
                            // Keep first non-Tailscale IP as fallback
                            fallbackIp = ip
                            Log.d(TAG, "Found fallback IP: $ip on interface $interfaceName")
                        }
                    }
                }
            }

            // Return Tailscale IP if available, otherwise fallback
            val selectedIp = tailscaleIp ?: fallbackIp
            Log.d(TAG, "Selected IP address: $selectedIp (Tailscale: ${tailscaleIp != null})")
            return selectedIp

        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return null
    }

    override fun onDestroy() {
        stopRtspServer()
        super.onDestroy()
        Log.d(TAG, "RtspServerService destroyed")
    }
}
