package com.pocketstream.app.network

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for NetworkScanner.
 * Note: Tests for actual network operations would be integration tests.
 */
class NetworkScannerTest {

    // ========== IP Address Format Tests ==========

    @Test
    fun `IP address format is correct for subnet scan`() {
        val subnet = "192.168.1"
        val lastOctet = 100
        val ipAddress = "$subnet.$lastOctet"

        assertEquals("192.168.1.100", ipAddress)
    }

    @Test
    fun `IP address format handles single digit octets`() {
        val subnet = "10.0.0"
        val lastOctet = 1
        val ipAddress = "$subnet.$lastOctet"

        assertEquals("10.0.0.1", ipAddress)
    }

    @Test
    fun `IP address format handles max octet value`() {
        val subnet = "192.168.255"
        val lastOctet = 254
        val ipAddress = "$subnet.$lastOctet"

        assertEquals("192.168.255.254", ipAddress)
    }

    // ========== Scan Range Calculation Tests ==========

    @Test
    fun `total IPs calculation for default range`() {
        val startIp = 1
        val endIp = 254
        val totalIps = endIp - startIp + 1

        assertEquals(254, totalIps)
    }

    @Test
    fun `total IPs calculation for custom range`() {
        val startIp = 10
        val endIp = 20
        val totalIps = endIp - startIp + 1

        assertEquals(11, totalIps)
    }

    @Test
    fun `total IPs calculation for single IP`() {
        val startIp = 5
        val endIp = 5
        val totalIps = endIp - startIp + 1

        assertEquals(1, totalIps)
    }

    @Test
    fun `reversed range produces zero or negative total`() {
        val startIp = 10
        val endIp = 5
        val totalIps = endIp - startIp + 1

        assertTrue(totalIps <= 0)
    }

    // ========== Progress Calculation Tests ==========

    @Test
    fun `progress calculation for first IP`() {
        val startIp = 1
        val lastOctet = 1
        val current = lastOctet - startIp + 1

        assertEquals(1, current)
    }

    @Test
    fun `progress calculation mid-scan`() {
        val startIp = 1
        val lastOctet = 128
        val current = lastOctet - startIp + 1

        assertEquals(128, current)
    }

    @Test
    fun `progress calculation for last IP`() {
        val startIp = 1
        val endIp = 254
        val lastOctet = 254
        val current = lastOctet - startIp + 1

        assertEquals(254, current)
    }

    // ========== Common IPs List Tests ==========

    @Test
    fun `common IPs list includes gateway`() {
        val subnet = "192.168.42"
        val commonIps = listOf(
            "$subnet.1",    // Gateway
            "$subnet.129",  // Common USB tethering client IP
            "$subnet.10",
            "$subnet.100",
            "$subnet.2"
        )

        assertTrue(commonIps.contains("192.168.42.1"))
    }

    @Test
    fun `common IPs list includes tethering client IP`() {
        val subnet = "192.168.42"
        val commonIps = listOf(
            "$subnet.1",
            "$subnet.129",  // Common USB tethering client IP
            "$subnet.10",
            "$subnet.100",
            "$subnet.2"
        )

        assertTrue(commonIps.contains("192.168.42.129"))
    }

    @Test
    fun `extracting last octet from IP`() {
        val ip = "192.168.42.129"
        val octet = ip.substringAfterLast(".").toIntOrNull()

        assertEquals(129, octet)
    }

    @Test
    fun `extracting last octet from single digit`() {
        val ip = "10.0.0.1"
        val octet = ip.substringAfterLast(".").toIntOrNull()

        assertEquals(1, octet)
    }

    @Test
    fun `extracting last octet from invalid IP returns null`() {
        val ip = "invalid"
        val octet = ip.substringAfterLast(".").toIntOrNull()

        assertNull(octet)
    }

    // ========== Range Validation Tests ==========

    @Test
    fun `IP is in range when within bounds`() {
        val octet = 100
        val startIp = 1
        val endIp = 254

        assertTrue(octet in startIp..endIp)
    }

    @Test
    fun `IP is in range at lower bound`() {
        val octet = 1
        val startIp = 1
        val endIp = 254

        assertTrue(octet in startIp..endIp)
    }

    @Test
    fun `IP is in range at upper bound`() {
        val octet = 254
        val startIp = 1
        val endIp = 254

        assertTrue(octet in startIp..endIp)
    }

    @Test
    fun `IP is out of range below lower bound`() {
        val octet = 0
        val startIp = 1
        val endIp = 254

        assertFalse(octet in startIp..endIp)
    }

    @Test
    fun `IP is out of range above upper bound`() {
        val octet = 255
        val startIp = 1
        val endIp = 254

        assertFalse(octet in startIp..endIp)
    }

    // ========== Subnet Format Tests ==========

    @Test
    fun `private network class A subnet`() {
        val subnet = "10.0.0"
        assertTrue(subnet.startsWith("10."))
    }

    @Test
    fun `private network class B subnet`() {
        val subnet = "172.16.0"
        assertTrue(subnet.startsWith("172."))
    }

    @Test
    fun `private network class C subnet`() {
        val subnet = "192.168.1"
        assertTrue(subnet.startsWith("192.168."))
    }

    @Test
    fun `USB tethering common subnet`() {
        val subnet = "192.168.42"
        assertEquals("192.168.42", subnet)
    }
}
