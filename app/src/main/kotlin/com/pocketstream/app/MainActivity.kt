package com.pocketstream.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.pocketstream.app.databinding.ActivityMainBinding
import com.pocketstream.app.manager.TetheringManager
import com.pocketstream.app.network.NetworkInterfaceScanner
import com.pocketstream.app.network.NetworkScanner
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.UnknownHostException
import com.pocketstream.app.util.TimeUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        private const val KEY_DETECTED_IP = "detected_ip"
        private const val KEY_PHONE_IP = "phone_ip"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var tetheringManager: TetheringManager
    private lateinit var networkInterfaceScanner: NetworkInterfaceScanner
    private lateinit var networkScanner: NetworkScanner
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var drawerLayout: DrawerLayout

    private var detectedIpAddress: String? = null
    private var phoneIpAddress: String? = null  // Phone's tethering IP (for receiving stream)
    private var isScanInProgress = false

    // Video streaming
    private var currentStreamPort: Int = 8600

    // RTSP server state
    private var isRtspStreaming = false
    private var rtspUrl: String? = null

    // Broadcast receiver for RTSP server status updates
    private val rtspStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RtspServerService.ACTION_STATUS_UPDATE) {
                val isStreaming = intent.getBooleanExtra(RtspServerService.EXTRA_IS_STREAMING, false)
                val uptimeSeconds = intent.getLongExtra(RtspServerService.EXTRA_UPTIME_SECONDS, 0)
                val streamUrl = intent.getStringExtra(RtspServerService.EXTRA_STREAM_URL)
                val errorMessage = intent.getStringExtra(RtspServerService.EXTRA_ERROR_MESSAGE)
                val bandwidthBytesPerSec = intent.getLongExtra(RtspServerService.EXTRA_BANDWIDTH_BYTES_PER_SEC, 0)
                updateRtspUI(isStreaming, uptimeSeconds, streamUrl, errorMessage, bandwidthBytesPerSec)
            }
        }
    }

    // Notification permission launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied")
            Toast.makeText(this, "Notification permission required for RTSP server", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        tetheringManager = TetheringManager(this)
        networkInterfaceScanner = NetworkInterfaceScanner()
        networkScanner = NetworkScanner()
        preferencesManager = PreferencesManager(this)

        // Load saved port
        currentStreamPort = preferencesManager.getStreamPort()

        // Restore saved state if activity was recreated
        savedInstanceState?.let {
            detectedIpAddress = it.getString(KEY_DETECTED_IP)
            phoneIpAddress = it.getString(KEY_PHONE_IP)
            Log.d(TAG, "Restored state - Detected IP: $detectedIpAddress, Phone IP: $phoneIpAddress")

            // Update UI with restored IPs
            detectedIpAddress?.let { ip ->
                binding.targetIp.text = ip
                binding.browserButton.isEnabled = true  // Re-enable Launch Browser button
                binding.scanResultText.text = getString(R.string.connect_success)
                binding.scanResultText.setTextColor(getColor(R.color.success))
            }
            phoneIpAddress?.let { ip ->
                binding.videoStreamUrl.text = ip
                binding.launchStreamButton.isEnabled = true  // Re-enable Launch Stream button
            }
        }

        // Setup toolbar with centered icon + title
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Setup drawer
        drawerLayout = binding.drawerLayout
        setupSettingsDrawer()

        // Setup UI
        setupTetheringSection()
        setupConnectionSection()
        setupDeviceLoginSection()
        setupVideoStreamSection()
        setupRtspServerSection()

        // Register broadcast receiver for RTSP server status updates
        LocalBroadcastManager.getInstance(this).registerReceiver(
            rtspStatusReceiver,
            IntentFilter(RtspServerService.ACTION_STATUS_UPDATE)
        )

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check initial tethering state
        checkTetheringState()
    }

    override fun onResume() {
        super.onResume()

        // Re-check tethering state when returning to activity
        checkTetheringState()

        // Restore button states based on current IP values
        // This ensures buttons work even after returning from other activities
        if (detectedIpAddress != null) {
            binding.browserButton.isEnabled = true
            binding.targetIp.text = detectedIpAddress
            binding.scanResultText.text = getString(R.string.connect_success)
            binding.scanResultText.setTextColor(getColor(R.color.success))
            Log.d(TAG, "onResume: Launch Browser enabled (IP: $detectedIpAddress)")
        }
        // Re-evaluate stream button based on protocol
        updateVideoStreamUrl()

        // Update RTSP card state to restore button states
        updateRtspCardState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save IP addresses so they persist across activity recreations
        outState.putString(KEY_DETECTED_IP, detectedIpAddress)
        outState.putString(KEY_PHONE_IP, phoneIpAddress)
        Log.d(TAG, "Saved state - Detected IP: $detectedIpAddress, Phone IP: $phoneIpAddress")
    }

    private fun setupTetheringSection() {
        // Tethering status is read-only - users must enable manually in Android Settings
        // Set initial state to disconnected - will be updated by checkTetheringState()
        updateTetheringStatus(false)

        // Refresh button to restart the app if something stalls
        binding.refreshButton.setOnClickListener {
            Log.d(TAG, "Refresh button clicked - recreating activity")
            recreate()
        }
    }

    private fun setupConnectionSection() {
        // Disable connect button until tethering is active
        binding.scanButton.isEnabled = false

        binding.scanButton.setOnClickListener {
            lifecycleScope.launch {
                connectToDevice()
            }
        }
    }

    private fun setupDeviceLoginSection() {
        // Disable browser button until IP is detected
        binding.browserButton.isEnabled = false

        binding.browserButton.setOnClickListener {
            launchBrowser()
        }
    }

    private fun checkTetheringState() {
        lifecycleScope.launch {
            // Only check for Ethernet tethering (USB tethering not supported)
            val isEthActive = tetheringManager.isEthernetTetheringActive()

            updateTetheringStatus(isEthActive)
        }
    }

    private fun updateTetheringStatus(isConnected: Boolean) {
        if (isConnected) {
            binding.tetheringStatus.text = getString(R.string.tethering_connected)
            binding.tetheringStatusIcon.visibility = View.VISIBLE
            binding.scanButton.isEnabled = true
        } else {
            binding.tetheringStatus.text = getString(R.string.tethering_disconnected)
            binding.tetheringStatusIcon.visibility = View.GONE

            // Disable all buttons except Refresh when tethering disconnects
            binding.scanButton.isEnabled = false
            binding.browserButton.isEnabled = false
            binding.launchStreamButton.isEnabled = false

            // Reset all scan results and data when tethering disconnects
            detectedIpAddress = null
            phoneIpAddress = null
            binding.targetIp.text = getString(R.string.no_target_ip)
            binding.videoStreamUrl.text = getString(R.string.stream_ip_not_detected)
            binding.videoStreamStatusIcon.visibility = View.GONE
            binding.scanResultText.text = getString(R.string.connect_idle)
            binding.scanResultText.setTextColor(getColor(R.color.text_secondary))
            binding.scanProgressBar.visibility = View.GONE


            // Update RTSP server card state (will disable since no connection)
            updateRtspCardState()
        }
    }

    private suspend fun connectToDevice() {
        if (isScanInProgress) {
            Log.w(TAG, "Connection already in progress, ignoring request")
            return
        }

        isScanInProgress = true
        Log.d(TAG, "Starting device connection")

        // Disable button during connection and show progress bar
        binding.scanButton.isEnabled = false
        binding.scanProgressBar.visibility = View.VISIBLE
        binding.scanProgressBar.progress = 0
        binding.scanResultText.text = getString(R.string.connect_in_progress)
        binding.scanResultText.setTextColor(getColor(R.color.text_secondary))

        try {
            // Get the primary tethering interface
            val tetheringInterface = networkInterfaceScanner.getPrimaryTetheringInterface()

            if (tetheringInterface == null) {
                Log.e(TAG, "No tethering interface found")
                // Reset tethering status since it's not actually active
                updateTetheringStatus(false)
                showConnectionError("Connection Error", getString(R.string.error_tethering_check_settings))
                return
            }

            Log.i(TAG, "Found tethering interface: ${tetheringInterface.name}, IP: ${tetheringInterface.ipAddress}")
            val phoneIp = tetheringInterface.ipAddress
            phoneIpAddress = phoneIp  // Store phone's IP for video streaming

            // Get the subnet from the interface
            val subnet = networkInterfaceScanner.getInterfaceSubnet(tetheringInterface)

            if (subnet == null) {
                Log.e(TAG, "Unable to determine subnet for interface ${tetheringInterface.name}")
                // Reset tethering status since we can't use it
                updateTetheringStatus(false)
                showConnectionError("Connection Error", getString(R.string.error_tethering_check_settings))
                return
            }

            Log.i(TAG, "Scanning subnet: $subnet (Phone IP: $phoneIp)")

            // Scan the network for active IPs
            val activeIps = networkScanner.scanSubnet(
                subnet = subnet,
                startIp = 1,
                endIp = 254
            ) { current, total ->
                // Update progress bar
                lifecycleScope.launch {
                    val progress = (current * 100) / total
                    binding.scanProgressBar.progress = progress
                }
            }

            Log.i(TAG, "Found ${activeIps.size} active IPs: $activeIps")

            if (activeIps.isEmpty()) {
                binding.scanProgressBar.visibility = View.GONE
                binding.scanResultText.text = getString(R.string.connect_no_devices)
                binding.scanResultText.setTextColor(getColor(R.color.warning))
                Toast.makeText(
                    this,
                    R.string.error_no_devices,
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Filter out the phone's own IP (gateway)
            val otherDeviceIps = activeIps.filter { it != phoneIp }
            Log.i(TAG, "After excluding phone IP ($phoneIp): ${otherDeviceIps.size} device(s) found: $otherDeviceIps")

            if (otherDeviceIps.isEmpty()) {
                Log.w(TAG, "No other devices found besides phone")
                binding.scanProgressBar.visibility = View.GONE
                binding.scanResultText.text = getString(R.string.connect_no_devices)
                binding.scanResultText.setTextColor(getColor(R.color.warning))
                Toast.makeText(
                    this,
                    R.string.error_no_devices,
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Point-to-point mode: use the first device found
            detectedIpAddress = otherDeviceIps.first()

            if (otherDeviceIps.size == 1) {
                Log.i(TAG, "Single device detected - using $detectedIpAddress")
            } else {
                Log.w(TAG, "Multiple devices found (${otherDeviceIps.size}), using first: $detectedIpAddress")
            }

            binding.scanProgressBar.visibility = View.GONE
            binding.scanResultText.text = getString(R.string.connect_success)
            binding.scanResultText.setTextColor(getColor(R.color.success))
            binding.browserButton.isEnabled = true
            binding.targetIp.text = detectedIpAddress

            // Update video stream URL
            updateVideoStreamUrl()

            // Update RTSP server card state now that connection is established
            updateRtspCardState()

            Toast.makeText(
                this,
                getString(R.string.connect_success),
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: UnknownHostException) {
            Log.e(TAG, "DNS resolution error during connection", e)
            showConnectionError("Connection Error", "DNS resolution failed: ${e.message}")
        } catch (e: IOException) {
            Log.e(TAG, "I/O error during connection", e)
            showConnectionError("Connection Error", "Network I/O error: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during connection", e)
            showConnectionError("Permission Error", "Required permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during connection", e)
            showConnectionError("Connection Error", getString(R.string.error_scan_exception, e.message ?: "Unknown error"))
        } finally {
            binding.scanButton.isEnabled = true
            isScanInProgress = false
            Log.d(TAG, "Device connection completed")
        }
    }

    /**
     * Launches the device browser to the detected IP address.
     */
    private fun launchBrowser() {
        if (detectedIpAddress == null) {
            Log.w(TAG, "Attempted to launch browser without target IP")
            showErrorDialog(
                "Browser Launch Error",
                getString(R.string.error_no_target_ip)
            )
            return
        }

        Log.d(TAG, "Launching browser to http://$detectedIpAddress")


        // Create intent to open URL in browser - force HTTP for local device
        val url = "http://$detectedIpAddress"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // Try to prevent HTTPS auto-upgrade by explicitly setting package to browser
            // This helps with Chrome's HTTPS-first mode
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("com.android.browser.application_id", packageName)
        }

        try {
            startActivity(intent)

            Log.i(TAG, "Browser launched successfully to $url")
            Toast.makeText(
                this,
                "Opening browser to $detectedIpAddress",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error launching browser", e)

            showErrorDialog("Browser Error", "Cannot open browser: ${e.message}")
        }
    }

    /**
     * Shows an error dialog to the user with detailed information.
     */
    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    /**
     * Helper to show connection error state in UI and display error dialog.
     */
    private fun showConnectionError(title: String, message: String) {
        binding.scanProgressBar.visibility = View.GONE
        binding.scanResultText.text = getString(R.string.connect_failed)
        binding.scanResultText.setTextColor(getColor(R.color.error))
        showErrorDialog(title, message)
    }

    // ========== Video Streaming Section ==========

    /**
     * Sets up the video streaming section UI and event handlers.
     */
    private fun setupVideoStreamSection() {
        // Disable launch button until IP is detected
        binding.launchStreamButton.isEnabled = false

        // Launch stream button - now launches directly to fullscreen
        binding.launchStreamButton.setOnClickListener {
            launchFullscreenVideo()
        }
    }

    /**
     * Updates the video stream URL display when IP changes.
     * Shows camera RTSP URL (without credentials) for RTSP mode, or phone IP for UDP mode.
     */
    private fun updateVideoStreamUrl() {
        val isRtsp = preferencesManager.getInputProtocol() == PreferencesManager.InputProtocol.RTSP

        if (isRtsp) {
            binding.videoStreamDescription.text = getString(R.string.video_stream_description_rtsp)
            binding.phoneIpLabel.text = getString(R.string.stream_url_label_rtsp)
            if (detectedIpAddress != null) {
                binding.videoStreamUrl.text = preferencesManager.buildCameraRtspDisplayUrl(detectedIpAddress!!)
                binding.launchStreamButton.isEnabled = true
            } else {
                binding.videoStreamUrl.text = getString(R.string.stream_ip_not_detected)
                binding.launchStreamButton.isEnabled = false
            }
        } else {
            binding.videoStreamDescription.text = getString(R.string.video_stream_description)
            binding.phoneIpLabel.text = getString(R.string.stream_url_label_udp)
            if (phoneIpAddress != null) {
                binding.videoStreamUrl.text = phoneIpAddress
                binding.launchStreamButton.isEnabled = true
            } else {
                binding.videoStreamUrl.text = getString(R.string.stream_ip_not_detected)
                binding.launchStreamButton.isEnabled = false
            }
        }
    }

    // ========== RTSP Server Section ==========

    /**
     * Sets up the RTSP Server card UI and event handlers.
     */
    private fun setupRtspServerSection() {
        // Set up button click handler
        binding.launchRtspButton.setOnClickListener {
            toggleRtspServer()
        }

        // Set up copy URL button
        binding.copyRtspUrlButton.setOnClickListener {
            copyRtspUrlToClipboard()
        }

        // Initialize card state based on settings
        updateRtspCardState()
    }

    /**
     * Updates the RTSP Server card state based on settings and connection status.
     * - Tile is at full brightness when enabled in settings
     * - Button is only enabled when:
     *   1. RTSP server is enabled in settings, AND
     *   2. Camera connection has been established (detectedIpAddress is set)
     */
    private fun updateRtspCardState() {
        val isEnabledInSettings = preferencesManager.isRtspEnabled()
        val hasConnection = detectedIpAddress != null

        // Button is only enabled when all conditions are met
        val canStartRtsp = isEnabledInSettings && hasConnection
        binding.launchRtspButton.isEnabled = canStartRtsp
        binding.copyRtspUrlButton.isEnabled = canStartRtsp && isRtspStreaming

        // Update URL and values based on settings
        if (!isEnabledInSettings) {
            binding.rtspUrlValue.text = getString(R.string.rtsp_url_not_available)
            binding.rtspUptimeValue.text = getString(R.string.rtsp_uptime_default)
            binding.rtspBandwidthValue.text = getString(R.string.rtsp_bandwidth_default)
        } else if (!hasConnection && !isRtspStreaming) {
            // Enabled in settings but no connection yet
            binding.rtspUrlValue.text = getString(R.string.rtsp_connect_first)
            binding.rtspUptimeValue.text = getString(R.string.rtsp_uptime_default)
            binding.rtspBandwidthValue.text = getString(R.string.rtsp_bandwidth_default)
        }

        // Set alpha on tile content only when disabled in settings
        // When enabled in settings, tile is full brightness even if button is disabled
        val alpha = if (isEnabledInSettings || isRtspStreaming) 1.0f else 0.5f
        binding.rtspServerTitle.alpha = alpha
        binding.rtspServerDescription.alpha = alpha
        binding.rtspUrlLabel.alpha = alpha
        binding.rtspUrlValue.alpha = alpha
        binding.rtspCredentialsLabel.alpha = alpha
        binding.rtspCredentialsValue.alpha = alpha
        binding.rtspUptimeLabel.alpha = alpha
        binding.rtspUptimeValue.alpha = alpha
        binding.rtspBandwidthLabel.alpha = alpha
        binding.rtspBandwidthValue.alpha = alpha
    }

    /**
     * Toggles the RTSP server on/off.
     */
    private fun toggleRtspServer() {
        if (!preferencesManager.isRtspEnabled()) {
            Toast.makeText(this, getString(R.string.rtsp_disabled), Toast.LENGTH_SHORT).show()
            return
        }

        if (isRtspStreaming) {
            stopRtspServerService()
        } else {
            startRtspServerService()
        }
    }

    /**
     * Starts the RTSP server foreground service.
     * Passes the full input URL (RTSP or UDP) based on selected protocol.
     */
    private fun startRtspServerService() {
        val isRtsp = preferencesManager.getInputProtocol() == PreferencesManager.InputProtocol.RTSP
        val inputUrl = if (isRtsp && detectedIpAddress != null) {
            preferencesManager.buildCameraRtspUrl(detectedIpAddress!!)
        } else {
            "udp://@:$currentStreamPort"
        }

        val rtspPort = preferencesManager.getRtspPort()
        val token = preferencesManager.getRtspToken()

        Log.d(TAG, "Starting RtspServerService: input=$inputUrl, RTSP=$rtspPort, token=${if (token.isNotBlank()) "enabled" else "disabled"}")

        val intent = Intent(this, RtspServerService::class.java).apply {
            action = RtspServerService.ACTION_START
            putExtra(RtspServerService.EXTRA_INPUT_URL, inputUrl)
            putExtra(RtspServerService.EXTRA_RTSP_PORT, rtspPort)
            putExtra(RtspServerService.EXTRA_TOKEN, token)
        }

        startForegroundService(intent)
    }

    /**
     * Stops the RTSP server foreground service.
     */
    private fun stopRtspServerService() {
        Log.d(TAG, "Stopping RtspServerService")

        val intent = Intent(this, RtspServerService::class.java).apply {
            action = RtspServerService.ACTION_STOP
        }
        startService(intent)
    }

    /**
     * Updates the RTSP Server card UI based on service status.
     * Called by the broadcast receiver.
     */
    private fun updateRtspUI(
        streaming: Boolean,
        uptimeSeconds: Long,
        streamUrl: String?,
        errorMessage: String?,
        bandwidthBytesPerSec: Long = 0
    ) {
        isRtspStreaming = streaming
        rtspUrl = streamUrl

        if (streaming) {
            // Update button
            binding.launchRtspButton.text = getString(R.string.stop_rtsp)

            // Update URL display
            binding.rtspUrlValue.text = streamUrl ?: "Starting..."
            binding.copyRtspUrlButton.isEnabled = streamUrl != null

            // Update credentials display (show that auth is active)
            binding.rtspCredentialsValue.text = getString(R.string.rtsp_auth_active)

            // Update uptime
            binding.rtspUptimeValue.text = TimeUtils.formatUptime(uptimeSeconds)

            // Update bandwidth (auto-scale KB/s or MB/s)
            binding.rtspBandwidthValue.text = formatBandwidth(bandwidthBytesPerSec)

        } else {
            // Update button
            binding.launchRtspButton.text = getString(R.string.launch_rtsp)

            // Reset URL display
            if (preferencesManager.isRtspEnabled()) {
                binding.rtspUrlValue.text = getString(R.string.rtsp_not_streaming)
            } else {
                binding.rtspUrlValue.text = getString(R.string.rtsp_url_not_available)
            }
            binding.copyRtspUrlButton.isEnabled = false

            // Token is always configured (auto-generated)
            binding.rtspCredentialsValue.text = getString(R.string.rtsp_auth_configured)

            // Reset uptime and bandwidth
            binding.rtspUptimeValue.text = getString(R.string.rtsp_uptime_default)
            binding.rtspBandwidthValue.text = getString(R.string.rtsp_bandwidth_default)
        }

        // Show error message if present
        if (errorMessage != null) {
            Toast.makeText(
                this,
                getString(R.string.rtsp_error, errorMessage),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Formats bandwidth in auto-scaled units (kb/s or Mbps).
     * Converts bytes/sec to kilobits/sec (network standard).
     */
    private fun formatBandwidth(bytesPerSec: Long): String {
        val bitsPerSec = bytesPerSec * 8
        return when {
            bitsPerSec <= 0 -> "0 kb/s"
            bitsPerSec < 1_000_000 -> {
                // Show in kb/s (kilobits)
                val kbps = bitsPerSec / 1000.0
                String.format("%.0f kb/s", kbps)
            }
            else -> {
                // Show in Mbps (megabits)
                val mbps = bitsPerSec / 1_000_000.0
                String.format("%.1f Mbps", mbps)
            }
        }
    }

    /**
     * Copies the RTSP URL to clipboard.
     */
    private fun copyRtspUrlToClipboard() {
        val url = rtspUrl ?: return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("RTSP URL", url)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            this,
            getString(R.string.rtsp_url_copied),
            Toast.LENGTH_SHORT
        ).show()

        Log.d(TAG, "RTSP URL copied to clipboard: $url")
    }

    /**
     * Launches fullscreen video activity directly.
     * Builds URL based on selected input protocol (RTSP or UDP).
     */
    private fun launchFullscreenVideo() {
        val isRtsp = preferencesManager.getInputProtocol() == PreferencesManager.InputProtocol.RTSP

        val streamUrl = if (isRtsp) {
            if (detectedIpAddress == null) {
                Toast.makeText(this, getString(R.string.error_scan_first), Toast.LENGTH_SHORT).show()
                return
            }
            preferencesManager.buildCameraRtspUrl(detectedIpAddress!!)
        } else {
            if (phoneIpAddress == null) {
                Toast.makeText(this, getString(R.string.error_scan_first), Toast.LENGTH_SHORT).show()
                return
            }
            "udp://@:$currentStreamPort"
        }

        val intent = Intent(this, FullscreenVideoActivity::class.java).apply {
            putExtra(EXTRA_STREAM_URL, streamUrl)
        }
        startActivity(intent)
    }

    // Screenshot and recording functions removed - now handled in fullscreen activity

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // Toggle settings drawer
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Sets up the settings drawer with stream and re-stream configuration options.
     */
    private fun setupSettingsDrawer() {
        val navDrawer = binding.navDrawer.root

        // Setup tabs (Settings | About)
        val tabSettings = navDrawer.findViewById<TextView>(R.id.tabSettings)
        val tabAbout = navDrawer.findViewById<TextView>(R.id.tabAbout)
        val settingsScrollView = navDrawer.findViewById<ScrollView>(R.id.settingsScrollView)
        val aboutScrollView = navDrawer.findViewById<ScrollView>(R.id.aboutScrollView)
        val tabIndicator = navDrawer.findViewById<View>(R.id.tabIndicator)

        fun selectTab(isSettings: Boolean) {
            settingsScrollView.visibility = if (isSettings) View.VISIBLE else View.GONE
            aboutScrollView.visibility = if (isSettings) View.GONE else View.VISIBLE
            tabSettings.setTextColor(getColor(if (isSettings) R.color.text_primary else R.color.text_tertiary))
            tabAbout.setTextColor(getColor(if (isSettings) R.color.text_tertiary else R.color.text_primary))
            // Animate indicator to left or right half
            tabIndicator.post {
                val parentWidth = (tabIndicator.parent as View).width
                val halfWidth = parentWidth / 2
                val params = tabIndicator.layoutParams
                params.width = halfWidth
                tabIndicator.layoutParams = params
                tabIndicator.translationX = if (isSettings) 0f else halfWidth.toFloat()
            }
        }

        tabSettings.setOnClickListener { selectTab(true) }
        tabAbout.setOnClickListener { selectTab(false) }
        // Initialize to Settings tab
        selectTab(true)

        // Get references to camera input controls
        val btnProtocolRtsp = navDrawer.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProtocolRtsp)
        val btnProtocolUdp = navDrawer.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProtocolUdp)
        val rtspInputSection = navDrawer.findViewById<LinearLayout>(R.id.rtspInputSection)
        val udpInputSection = navDrawer.findViewById<LinearLayout>(R.id.udpInputSection)
        val cameraRtspPortInput = navDrawer.findViewById<TextInputEditText>(R.id.cameraRtspPortInput)
        val cameraRtspPathInput = navDrawer.findViewById<TextInputEditText>(R.id.cameraRtspPathInput)
        val cameraUsernameInput = navDrawer.findViewById<TextInputEditText>(R.id.cameraUsernameInput)
        val cameraPasswordInput = navDrawer.findViewById<TextInputEditText>(R.id.cameraPasswordInput)
        val streamPortInput = navDrawer.findViewById<TextInputEditText>(R.id.streamPortInput)

        // Get references to RTSP server (output) controls
        val rtspEnableSwitch = navDrawer.findViewById<SwitchMaterial>(R.id.rtspEnableSwitch)
        val rtspPortInput = navDrawer.findViewById<TextInputEditText>(R.id.rtspPortInput)
        val rtspTokenValue = navDrawer.findViewById<TextView>(R.id.rtspTokenValue)
        val regenerateTokenButton = navDrawer.findViewById<com.google.android.material.button.MaterialButton>(R.id.regenerateTokenButton)

        // Highlight the active protocol button, dim the other
        val accentFill = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2600E676"))
        val clearFill = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)

        fun selectProtocol(isRtsp: Boolean) {
            if (isRtsp) {
                btnProtocolRtsp.setTextColor(getColor(R.color.accent))
                btnProtocolRtsp.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                btnProtocolRtsp.backgroundTintList = accentFill
                btnProtocolUdp.setTextColor(getColor(R.color.text_tertiary))
                btnProtocolUdp.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
                btnProtocolUdp.backgroundTintList = clearFill
            } else {
                btnProtocolUdp.setTextColor(getColor(R.color.accent))
                btnProtocolUdp.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                btnProtocolUdp.backgroundTintList = accentFill
                btnProtocolRtsp.setTextColor(getColor(R.color.text_tertiary))
                btnProtocolRtsp.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
                btnProtocolRtsp.backgroundTintList = clearFill
            }
            rtspInputSection.visibility = if (isRtsp) View.VISIBLE else View.GONE
            udpInputSection.visibility = if (isRtsp) View.GONE else View.VISIBLE
        }

        // Load current settings
        val isRtspProtocol = preferencesManager.getInputProtocol() == PreferencesManager.InputProtocol.RTSP
        selectProtocol(isRtspProtocol)

        cameraRtspPortInput.setText(preferencesManager.getCameraRtspPort().toString())
        cameraRtspPathInput.setText(preferencesManager.getCameraRtspPath())
        cameraUsernameInput.setText(preferencesManager.getCameraUsername())
        cameraPasswordInput.setText(preferencesManager.getCameraPassword())
        streamPortInput.setText(preferencesManager.getStreamPort().toString())

        rtspEnableSwitch.isChecked = preferencesManager.isRtspEnabled()
        rtspPortInput.setText(preferencesManager.getRtspPort().toString())
        rtspTokenValue.text = "Stream Token: ${preferencesManager.getRtspToken()}"

        // Protocol button listeners
        btnProtocolRtsp.setOnClickListener {
            preferencesManager.setInputProtocol(PreferencesManager.InputProtocol.RTSP)
            selectProtocol(true)
            Log.d(TAG, "Input protocol set to: RTSP")
            updateVideoStreamUrl()
        }

        btnProtocolUdp.setOnClickListener {
            preferencesManager.setInputProtocol(PreferencesManager.InputProtocol.UDP)
            selectProtocol(false)
            Log.d(TAG, "Input protocol set to: UDP")
            updateVideoStreamUrl()
        }

        // Camera RTSP port listener
        cameraRtspPortInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val port = s?.toString()?.toIntOrNull()
                if (port != null && port in 1..65535) {
                    preferencesManager.saveCameraRtspPort(port)
                    Log.d(TAG, "Camera RTSP port saved: $port")
                    updateVideoStreamUrl()
                }
            }
        })

        // Camera RTSP path listener
        cameraRtspPathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val path = s?.toString() ?: ""
                if (path.isNotBlank()) {
                    preferencesManager.saveCameraRtspPath(path)
                    Log.d(TAG, "Camera RTSP path saved: $path")
                    updateVideoStreamUrl()
                }
            }
        })

        // Camera credentials listeners
        cameraUsernameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                preferencesManager.saveCameraUsername(s?.toString() ?: "")
            }
        })

        cameraPasswordInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                preferencesManager.saveCameraPassword(s?.toString() ?: "")
            }
        })

        // UDP stream port listener
        streamPortInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val port = s?.toString()?.toIntOrNull()
                if (port != null && port in 1..65535) {
                    currentStreamPort = port
                    preferencesManager.saveStreamPort(port)
                    Log.d(TAG, "Stream port saved: $port")
                    updateVideoStreamUrl()
                }
            }
        })

        // Set up RTSP server (output) change listeners
        rtspEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setRtspEnabled(isChecked)
            Log.d(TAG, "RTSP server enabled: $isChecked")
            updateRtspCardState()
        }

        rtspPortInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val port = s?.toString()?.toIntOrNull()
                if (port != null && port in 1..65535) {
                    preferencesManager.saveRtspPort(port)
                    Log.d(TAG, "RTSP port saved: $port")
                }
            }
        })

        regenerateTokenButton.setOnClickListener {
            val newToken = preferencesManager.regenerateRtspToken()
            rtspTokenValue.text = "Stream Token: $newToken"
            Toast.makeText(this, getString(R.string.rtsp_token_regenerated), Toast.LENGTH_LONG).show()
            Log.d(TAG, "RTSP token regenerated")
        }

        // Setup About section (version text + licenses button)
        setupAboutSection(navDrawer)

        // Handle back press to close drawer first
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Sets up the About section in the settings drawer.
     */
    private fun setupAboutSection(navDrawer: View) {
        // Set version text
        val versionText = navDrawer.findViewById<TextView>(R.id.aboutVersionText)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "1.0"
            versionText.text = getString(R.string.about_app_version, versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            versionText.text = getString(R.string.about_app_version, "1.0")
        }

        // View licenses button
        val viewLicensesButton = navDrawer.findViewById<com.google.android.material.button.MaterialButton>(R.id.viewLicensesButton)
        viewLicensesButton.setOnClickListener {
            showLicensesDialog()
        }
    }

    /**
     * Shows a dialog with open source licenses information.
     */
    private fun showLicensesDialog() {
        val licenseText = """
            |PocketStream is open source software licensed under the AGPL License.
            |
            |THIRD-PARTY LIBRARIES:
            |
            |LibVLC for Android
            |License: LGPL-2.1+
            |https://www.videolan.org/vlc/libvlc.html
            |
            |AndroidX Libraries
            |License: Apache 2.0
            |
            |Google Material Design
            |License: Apache 2.0
            |
            |Kotlin Coroutines
            |License: Apache 2.0
            |
            |Full license details available at:
            |https://github.com/RustRunner/PocketStream
        """.trimMargin()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_view_licenses))
            .setMessage(licenseText)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop RTSP server if running
        if (isRtspStreaming) {
            Log.d(TAG, "Stopping RTSP server on app close")
            stopRtspServerService()
        }
        // Unregister broadcast receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(rtspStatusReceiver)
        Log.d(TAG, "MainActivity destroyed")
    }
}

