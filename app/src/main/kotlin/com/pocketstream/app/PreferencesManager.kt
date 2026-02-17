package com.pocketstream.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages app preferences using SharedPreferences.
 * Handles persistent storage for video stream settings and re-streaming configuration.
 * Sensitive data (credentials) are stored using EncryptedSharedPreferences.
 */
class PreferencesManager(context: Context) {

    enum class InputProtocol {
        RTSP, UDP
    }

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREFS_NAME = "pocket_stream_prefs"
        private const val SECURE_PREFS_NAME = "pocket_stream_secure_prefs"

        // Camera input protocol
        private const val KEY_INPUT_PROTOCOL = "input_protocol"

        // Stream input settings (UDP)
        private const val KEY_STREAM_PORT = "stream_port"
        private const val DEFAULT_STREAM_PORT = 8600

        // Camera RTSP input settings
        private const val KEY_CAMERA_RTSP_PORT = "camera_rtsp_port"
        private const val DEFAULT_CAMERA_RTSP_PORT = 554
        private const val KEY_CAMERA_RTSP_PATH = "camera_rtsp_path"
        private const val DEFAULT_CAMERA_RTSP_PATH = "/live"
        private const val KEY_CAMERA_USERNAME = "camera_username"
        private const val KEY_CAMERA_PASSWORD = "camera_password"

        // RTSP server settings (output re-stream)
        private const val KEY_RTSP_ENABLED = "rtsp_enabled"
        private const val KEY_RTSP_PORT = "rtsp_port"
        private const val KEY_RTSP_TOKEN = "rtsp_token"
        private const val DEFAULT_RTSP_PORT = 8554
        private const val TOKEN_LENGTH = 16
    }

    // Regular preferences for non-sensitive settings
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Encrypted preferences for sensitive data (credentials)
    private val securePreferences: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular SharedPreferences if encryption fails
        Log.e(TAG, "Failed to create encrypted preferences, using fallback", e)
        context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the saved stream port.
     * @return The saved port number, or default 8600 if not set.
     */
    fun getStreamPort(): Int {
        return sharedPreferences.getInt(KEY_STREAM_PORT, DEFAULT_STREAM_PORT)
    }

    /**
     * Saves the stream port to persistent storage.
     * @param port The port number to save (typically 1-65535).
     */
    fun saveStreamPort(port: Int) {
        sharedPreferences.edit().putInt(KEY_STREAM_PORT, port).apply()
    }

    /**
     * Resets the stream port to default value.
     */
    fun resetStreamPort() {
        saveStreamPort(DEFAULT_STREAM_PORT)
    }

    /**
     * Clears all preferences.
     */
    fun clearAll() {
        sharedPreferences.edit().clear().apply()
        securePreferences.edit().clear().apply()
    }

    // ========== Camera Input Protocol ==========

    fun getInputProtocol(): InputProtocol {
        val value = sharedPreferences.getString(KEY_INPUT_PROTOCOL, InputProtocol.RTSP.name)
        return try {
            InputProtocol.valueOf(value ?: InputProtocol.RTSP.name)
        } catch (e: IllegalArgumentException) {
            InputProtocol.RTSP
        }
    }

    fun setInputProtocol(protocol: InputProtocol) {
        sharedPreferences.edit().putString(KEY_INPUT_PROTOCOL, protocol.name).apply()
    }

    // ========== Camera RTSP Input Settings ==========

    fun getCameraRtspPort(): Int {
        return sharedPreferences.getInt(KEY_CAMERA_RTSP_PORT, DEFAULT_CAMERA_RTSP_PORT)
    }

    fun saveCameraRtspPort(port: Int) {
        sharedPreferences.edit().putInt(KEY_CAMERA_RTSP_PORT, port).apply()
    }

    fun getCameraRtspPath(): String {
        return sharedPreferences.getString(KEY_CAMERA_RTSP_PATH, DEFAULT_CAMERA_RTSP_PATH) ?: DEFAULT_CAMERA_RTSP_PATH
    }

    fun saveCameraRtspPath(path: String) {
        sharedPreferences.edit().putString(KEY_CAMERA_RTSP_PATH, path).apply()
    }

    fun getCameraUsername(): String {
        return securePreferences.getString(KEY_CAMERA_USERNAME, "") ?: ""
    }

    fun saveCameraUsername(username: String) {
        securePreferences.edit().putString(KEY_CAMERA_USERNAME, username).apply()
    }

    fun getCameraPassword(): String {
        return securePreferences.getString(KEY_CAMERA_PASSWORD, "") ?: ""
    }

    fun saveCameraPassword(password: String) {
        securePreferences.edit().putString(KEY_CAMERA_PASSWORD, password).apply()
    }

    /**
     * Builds the full RTSP URL for the camera input.
     * Format: rtsp://[user:pass@]<cameraIp>:<port><path>
     */
    fun buildCameraRtspUrl(cameraIp: String): String {
        val port = getCameraRtspPort()
        val path = getCameraRtspPath()
        val username = getCameraUsername()
        val password = getCameraPassword()

        val userInfo = if (username.isNotBlank() && password.isNotBlank()) {
            "$username:$password@"
        } else {
            ""
        }

        return "rtsp://$userInfo$cameraIp:$port$path"
    }

    /**
     * Builds a display-safe version of the camera RTSP URL (credentials masked).
     */
    fun buildCameraRtspDisplayUrl(cameraIp: String): String {
        val port = getCameraRtspPort()
        val path = getCameraRtspPath()
        return "rtsp://$cameraIp:$port$path"
    }

    // ========== RTSP Server Settings (Output Re-stream) ==========

    /**
     * Checks if RTSP server is enabled.
     * @return true if RTSP server is enabled, false otherwise (default: false).
     */
    fun isRtspEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_RTSP_ENABLED, false)
    }

    /**
     * Enables or disables RTSP server.
     * @param enabled true to enable, false to disable.
     */
    fun setRtspEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_RTSP_ENABLED, enabled).apply()
    }

    /**
     * Gets the RTSP server port.
     * @return The saved port number, or default 8554 if not set.
     */
    fun getRtspPort(): Int {
        return sharedPreferences.getInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT)
    }

    /**
     * Saves the RTSP server port.
     * @param port The port number to save (typically 1-65535).
     */
    fun saveRtspPort(port: Int) {
        sharedPreferences.edit().putInt(KEY_RTSP_PORT, port).apply()
    }

    /**
     * Gets the RTSP stream token for URL-based authentication.
     * If no token exists, generates and saves a new one.
     * Stored in encrypted preferences.
     * @return The stream token (16 character hex string).
     */
    fun getRtspToken(): String {
        var token = securePreferences.getString(KEY_RTSP_TOKEN, "") ?: ""
        if (token.isBlank()) {
            token = generateToken()
            saveRtspToken(token)
        }
        return token
    }

    /**
     * Saves the RTSP stream token.
     * Stored in encrypted preferences.
     * @param token The token to save.
     */
    fun saveRtspToken(token: String) {
        securePreferences.edit().putString(KEY_RTSP_TOKEN, token).apply()
    }

    /**
     * Generates a new random RTSP stream token and saves it.
     * @return The newly generated token.
     */
    fun regenerateRtspToken(): String {
        val token = generateToken()
        saveRtspToken(token)
        Log.d(TAG, "Generated new RTSP token")
        return token
    }

    /**
     * Generates a random hex token.
     */
    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_LENGTH / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Resets all RTSP server settings to defaults.
     * Note: Token is preserved to avoid breaking existing client configurations.
     * Use regenerateRtspToken() to explicitly change the token.
     */
    fun resetRtspSettings() {
        sharedPreferences.edit()
            .putBoolean(KEY_RTSP_ENABLED, false)
            .putInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT)
            .apply()
    }
}
