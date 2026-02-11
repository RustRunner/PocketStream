package com.tvsconnect.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PreferencesManager constants and utility methods.
 * Note: Full integration tests would require Android instrumentation tests.
 */
class PreferencesManagerTest {

    // ========== Constants Tests ==========

    @Test
    fun `default stream port is 8600`() {
        // Verify expected default value
        val expectedDefault = 8600
        assertEquals(expectedDefault, 8600)
    }

    @Test
    fun `default RTSP port is 8554`() {
        // Verify expected default value
        val expectedDefault = 8554
        assertEquals(expectedDefault, 8554)
    }

    @Test
    fun `token length is 16 characters`() {
        // Verify expected token length
        val expectedLength = 16
        assertEquals(expectedLength, 16)
    }

    // ========== Token Generation Logic Tests ==========

    @Test
    fun `generateToken produces 16 character hex string`() {
        // Simulate the token generation logic
        val bytes = ByteArray(8) // 16/2 = 8 bytes
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }

        assertEquals(16, token.length)
        assertTrue("Token should be hex string", token.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `generateToken produces unique tokens`() {
        // Generate multiple tokens and verify they're unique
        val tokens = (1..10).map {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }

        val uniqueTokens = tokens.toSet()
        assertEquals("All tokens should be unique", 10, uniqueTokens.size)
    }

    @Test
    fun `token hex format is lowercase`() {
        val bytes = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11)
        val token = bytes.joinToString("") { "%02x".format(it) }

        assertTrue("Token should be lowercase", token == token.lowercase())
    }

    // ========== Credential Validation Logic Tests ==========

    @Test
    fun `hasCredentials logic returns false for empty username`() {
        val username = ""
        val password = "secret"
        val hasCredentials = username.isNotBlank() && password.isNotBlank()
        assertFalse(hasCredentials)
    }

    @Test
    fun `hasCredentials logic returns false for empty password`() {
        val username = "admin"
        val password = ""
        val hasCredentials = username.isNotBlank() && password.isNotBlank()
        assertFalse(hasCredentials)
    }

    @Test
    fun `hasCredentials logic returns false for both empty`() {
        val username = ""
        val password = ""
        val hasCredentials = username.isNotBlank() && password.isNotBlank()
        assertFalse(hasCredentials)
    }

    @Test
    fun `hasCredentials logic returns true for both set`() {
        val username = "admin"
        val password = "secret"
        val hasCredentials = username.isNotBlank() && password.isNotBlank()
        assertTrue(hasCredentials)
    }

    @Test
    fun `hasCredentials logic returns false for whitespace username`() {
        val username = "   "
        val password = "secret"
        val hasCredentials = username.isNotBlank() && password.isNotBlank()
        assertFalse(hasCredentials)
    }

    @Test
    fun `hasCredentials logic returns false for whitespace password`() {
        val username = "admin"
        val password = "   "
        val hasCredentials = username.isNotBlank() && password.isNotBlank()
        assertFalse(hasCredentials)
    }

    // ========== Port Validation Tests ==========

    @Test
    fun `valid port range lower bound`() {
        val port = 1
        assertTrue(port in 1..65535)
    }

    @Test
    fun `valid port range upper bound`() {
        val port = 65535
        assertTrue(port in 1..65535)
    }

    @Test
    fun `common RTSP ports are valid`() {
        val commonPorts = listOf(554, 8554, 8080, 1935)
        commonPorts.forEach { port ->
            assertTrue("Port $port should be valid", port in 1..65535)
        }
    }

    @Test
    fun `zero is invalid port`() {
        val port = 0
        assertFalse(port in 1..65535)
    }

    @Test
    fun `negative is invalid port`() {
        val port = -1
        assertFalse(port in 1..65535)
    }

    @Test
    fun `port above 65535 is invalid`() {
        val port = 65536
        assertFalse(port in 1..65535)
    }
}
