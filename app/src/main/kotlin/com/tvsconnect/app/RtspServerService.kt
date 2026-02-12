package com.tvsconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Timer
import java.util.TimerTask
import com.tvsconnect.app.util.TimeUtils

/**
 * Foreground service for RTSP server streaming of UDP video.
 * Receives UDP stream via LibVLC and re-broadcasts via authenticated RTSP.
 */
class RtspServerService : Service() {

    companion object {
        private const val TAG = "RtspServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rtsp_server_channel"

        // Broadcast actions
        const val ACTION_STATUS_UPDATE = "com.tvsconnect.app.RTSP_SERVER_STATUS_UPDATE"
        const val EXTRA_IS_STREAMING = "is_streaming"
        const val EXTRA_UPTIME_SECONDS = "uptime_seconds"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_BANDWIDTH_BYTES_PER_SEC = "bandwidth_bytes_per_sec"

        // Service actions
        const val ACTION_START = "com.tvsconnect.app.action.START_RTSP_SERVER"
        const val ACTION_STOP = "com.tvsconnect.app.action.STOP_RTSP_SERVER"

        // Intent extras
        const val EXTRA_UDP_PORT = "udp_port"
        const val EXTRA_RTSP_PORT = "rtsp_port"
        const val EXTRA_TOKEN = "token"
    }

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isStreaming = false
    private var startTime: Long = 0
    private var uptimeTimer: Timer? = null
    private var rtspPort: Int = 8554
    private var udpPort: Int = 8600
    private var streamToken: String = ""

    // Bandwidth tracking
    private var lastBytesRead: Long = 0
    private var lastBytesTime: Long = 0
    private var currentBandwidth: Long = 0 // bytes per second

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
        when (intent?.action) {
            ACTION_START -> {
                udpPort = intent.getIntExtra(EXTRA_UDP_PORT, 8600)
                rtspPort = intent.getIntExtra(EXTRA_RTSP_PORT, 8554)
                streamToken = intent.getStringExtra(EXTRA_TOKEN) ?: ""
                startRtspServer()
            }
            ACTION_STOP -> {
                stopRtspServer()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            Log.d(TAG, "Starting RTSP server: UDP port $udpPort -> RTSP port $rtspPort")
            // Start foreground service with notification
            startForeground(NOTIFICATION_ID, createNotification())

            // Initialize LibVLC with RTSP authentication options
            val options = ArrayList<String>().apply {
                add("--network-caching=1000")
                add("--live-caching=500")
                add("--udp-timeout=10000")
                // Increase RTSP session timeout (default is 60 seconds)
                add("--rtsp-timeout=0")  // 0 = no timeout
                add("-vvv")
            }
            libVLC = LibVLC(this, options)

            // Create MediaPlayer
            mediaPlayer = MediaPlayer(libVLC).apply {
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> {
                            Log.i(TAG, "RTSP server: Playing/Streaming")
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            Log.e(TAG, "RTSP server: Error encountered")
                            broadcastStatus(error = "Stream error occurred")
                        }
                        MediaPlayer.Event.Stopped -> {
                            Log.d(TAG, "RTSP server: Stopped")
                        }
                    }
                }
            }

            // Create media with sout for RTSP streaming
            val udpUrl = "udp://@:$udpPort"
            val media = Media(libVLC, Uri.parse(udpUrl))

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
            media.release()

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

            // Stop timer
            stopUptimeTimer()

            // Stop MediaPlayer
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            // Release LibVLC
            libVLC?.release()
            libVLC = null

            // Update state
            isStreaming = false

            // Broadcast final status
            broadcastStatus()

            Log.i(TAG, "RTSP server stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTSP server", e)
        }
    }

    private fun startUptimeTimer() {
        // Initialize bandwidth tracking
        lastBytesRead = 0
        lastBytesTime = System.currentTimeMillis()
        currentBandwidth = 0

        uptimeTimer = Timer()
        uptimeTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (isStreaming) {
                    // Calculate bandwidth from LibVLC stats
                    updateBandwidth()
                    broadcastStatus()
                    // Update notification
                    val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager.notify(NOTIFICATION_ID, createNotification(uptimeSeconds))
                }
            }
        }, 1000, 1000) // Update every second
    }

    /**
     * Updates bandwidth calculation from LibVLC media statistics.
     * Uses readBytes to track incoming UDP stream data.
     */
    private fun updateBandwidth() {
        try {
            val stats = mediaPlayer?.media?.stats
            if (stats != null) {
                val currentBytes = stats.readBytes.toLong()
                val currentTime = System.currentTimeMillis()
                val timeDelta = currentTime - lastBytesTime

                if (timeDelta > 0 && lastBytesRead > 0) {
                    val bytesDelta = currentBytes - lastBytesRead
                    if (bytesDelta >= 0) {
                        // Calculate bytes per second
                        currentBandwidth = (bytesDelta * 1000) / timeDelta
                    }
                }

                lastBytesRead = currentBytes
                lastBytesTime = currentTime
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting media stats", e)
        }
    }

    private fun stopUptimeTimer() {
        uptimeTimer?.cancel()
        uptimeTimer = null
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
