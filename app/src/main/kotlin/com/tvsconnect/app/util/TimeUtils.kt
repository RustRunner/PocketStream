package com.tvsconnect.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility object for time-related formatting operations.
 * Provides consistent time formatting across the application.
 */
object TimeUtils {

    private const val TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"

    /**
     * Formats a duration in seconds to a human-readable time string.
     * Format: "HH:MM:SS" if hours > 0, otherwise "MM:SS"
     *
     * @param seconds The duration in seconds
     * @return Formatted time string (e.g., "1:23:45" or "05:30")
     */
    fun formatUptime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }

    /**
     * Generates a timestamp string suitable for filenames.
     * Format: "yyyyMMdd_HHmmss" (e.g., "20241218_143025")
     *
     * @return Timestamp string for current date/time
     */
    fun generateTimestamp(): String {
        return SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(Date())
    }
}
