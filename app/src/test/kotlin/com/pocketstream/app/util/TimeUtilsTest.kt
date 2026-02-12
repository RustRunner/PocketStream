package com.pocketstream.app.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TimeUtils utility class.
 */
class TimeUtilsTest {

    // ========== formatUptime Tests ==========

    @Test
    fun `formatUptime with zero seconds returns 00 00`() {
        val result = TimeUtils.formatUptime(0)
        assertEquals("00:00", result)
    }

    @Test
    fun `formatUptime with seconds only returns correct format`() {
        val result = TimeUtils.formatUptime(45)
        assertEquals("00:45", result)
    }

    @Test
    fun `formatUptime with minutes and seconds returns correct format`() {
        val result = TimeUtils.formatUptime(125) // 2 minutes 5 seconds
        assertEquals("02:05", result)
    }

    @Test
    fun `formatUptime with exactly one minute returns 01 00`() {
        val result = TimeUtils.formatUptime(60)
        assertEquals("01:00", result)
    }

    @Test
    fun `formatUptime with hours returns HH MM SS format`() {
        val result = TimeUtils.formatUptime(3661) // 1 hour 1 minute 1 second
        assertEquals("1:01:01", result)
    }

    @Test
    fun `formatUptime with multiple hours returns correct format`() {
        val result = TimeUtils.formatUptime(7325) // 2 hours 2 minutes 5 seconds
        assertEquals("2:02:05", result)
    }

    @Test
    fun `formatUptime with exactly one hour returns 1 00 00`() {
        val result = TimeUtils.formatUptime(3600)
        assertEquals("1:00:00", result)
    }

    @Test
    fun `formatUptime with 59 minutes 59 seconds returns MM SS format`() {
        val result = TimeUtils.formatUptime(3599) // 59 minutes 59 seconds
        assertEquals("59:59", result)
    }

    @Test
    fun `formatUptime with large value returns correct format`() {
        val result = TimeUtils.formatUptime(86400) // 24 hours
        assertEquals("24:00:00", result)
    }

    @Test
    fun `formatUptime with 10 hours formats correctly`() {
        val result = TimeUtils.formatUptime(36000) // 10 hours
        assertEquals("10:00:00", result)
    }

    // ========== generateTimestamp Tests ==========

    @Test
    fun `generateTimestamp returns non-empty string`() {
        val result = TimeUtils.generateTimestamp()
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generateTimestamp returns correct format pattern`() {
        val result = TimeUtils.generateTimestamp()
        // Format: yyyyMMdd_HHmmss (e.g., "20241218_143025")
        val pattern = Regex("\\d{8}_\\d{6}")
        assertTrue("Timestamp '$result' should match pattern yyyyMMdd_HHmmss", pattern.matches(result))
    }

    @Test
    fun `generateTimestamp returns 15 character string`() {
        val result = TimeUtils.generateTimestamp()
        assertEquals(15, result.length)
    }

    @Test
    fun `generateTimestamp contains underscore at position 8`() {
        val result = TimeUtils.generateTimestamp()
        assertEquals('_', result[8])
    }

    @Test
    fun `generateTimestamp year part is reasonable`() {
        val result = TimeUtils.generateTimestamp()
        val year = result.substring(0, 4).toInt()
        assertTrue("Year should be between 2020 and 2100", year in 2020..2100)
    }

    @Test
    fun `generateTimestamp month part is valid`() {
        val result = TimeUtils.generateTimestamp()
        val month = result.substring(4, 6).toInt()
        assertTrue("Month should be between 1 and 12", month in 1..12)
    }

    @Test
    fun `generateTimestamp day part is valid`() {
        val result = TimeUtils.generateTimestamp()
        val day = result.substring(6, 8).toInt()
        assertTrue("Day should be between 1 and 31", day in 1..31)
    }

    @Test
    fun `generateTimestamp hour part is valid`() {
        val result = TimeUtils.generateTimestamp()
        val hour = result.substring(9, 11).toInt()
        assertTrue("Hour should be between 0 and 23", hour in 0..23)
    }

    @Test
    fun `generateTimestamp minute part is valid`() {
        val result = TimeUtils.generateTimestamp()
        val minute = result.substring(11, 13).toInt()
        assertTrue("Minute should be between 0 and 59", minute in 0..59)
    }

    @Test
    fun `generateTimestamp second part is valid`() {
        val result = TimeUtils.generateTimestamp()
        val second = result.substring(13, 15).toInt()
        assertTrue("Second should be between 0 and 59", second in 0..59)
    }
}
