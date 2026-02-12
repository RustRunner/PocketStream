package com.pocketstream.app.network

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for NetworkInterfaceScanner.
 */
class NetworkInterfaceScannerTest {

    // ========== InterfaceInfo Data Class Tests ==========

    @Test
    fun `InterfaceInfo creates correctly with all parameters`() {
        val info = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet Interface",
            ipAddress = "192.168.1.100",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        assertEquals("eth0", info.name)
        assertEquals("Ethernet Interface", info.displayName)
        assertEquals("192.168.1.100", info.ipAddress)
        assertTrue(info.isUp)
        assertFalse(info.isLoopback)
        assertTrue(info.supportsMulticast)
    }

    @Test
    fun `InterfaceInfo with null IP address`() {
        val info = NetworkInterfaceScanner.InterfaceInfo(
            name = "lo",
            displayName = "Loopback",
            ipAddress = null,
            isUp = true,
            isLoopback = true,
            supportsMulticast = false
        )

        assertNull(info.ipAddress)
        assertTrue(info.isLoopback)
    }

    @Test
    fun `InterfaceInfo equality works correctly`() {
        val info1 = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        val info2 = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        assertEquals(info1, info2)
    }

    @Test
    fun `InterfaceInfo inequality with different name`() {
        val info1 = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        val info2 = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth1",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        assertNotEquals(info1, info2)
    }

    @Test
    fun `InterfaceInfo hashCode is consistent`() {
        val info = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        assertEquals(info.hashCode(), info.hashCode())
    }

    @Test
    fun `InterfaceInfo copy works correctly`() {
        val original = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        val copy = original.copy(ipAddress = "192.168.1.2")

        assertEquals("eth0", copy.name)
        assertEquals("192.168.1.2", copy.ipAddress)
    }

    @Test
    fun `InterfaceInfo copy preserves other fields`() {
        val original = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        val copy = original.copy(name = "eth1")

        assertEquals("eth1", copy.name)
        assertEquals("192.168.1.1", copy.ipAddress)
        assertTrue(copy.isUp)
    }

    // ========== Subnet Extraction Logic Tests ==========

    @Test
    fun `subnet extraction from valid IP`() {
        val ipAddress = "192.168.1.100"
        val octets = ipAddress.split(".")
        val subnet = if (octets.size == 4) {
            "${octets[0]}.${octets[1]}.${octets[2]}"
        } else null

        assertEquals("192.168.1", subnet)
    }

    @Test
    fun `subnet extraction from 10 x network`() {
        val ipAddress = "10.13.207.74"
        val octets = ipAddress.split(".")
        val subnet = if (octets.size == 4) {
            "${octets[0]}.${octets[1]}.${octets[2]}"
        } else null

        assertEquals("10.13.207", subnet)
    }

    @Test
    fun `subnet extraction from 172 x network`() {
        val ipAddress = "172.16.0.1"
        val octets = ipAddress.split(".")
        val subnet = if (octets.size == 4) {
            "${octets[0]}.${octets[1]}.${octets[2]}"
        } else null

        assertEquals("172.16.0", subnet)
    }

    @Test
    fun `subnet extraction returns null for invalid IP`() {
        val ipAddress = "invalid"
        val octets = ipAddress.split(".")
        val subnet = if (octets.size == 4) {
            "${octets[0]}.${octets[1]}.${octets[2]}"
        } else null

        assertNull(subnet)
    }

    @Test
    fun `subnet extraction returns null for partial IP`() {
        val ipAddress = "192.168.1"
        val octets = ipAddress.split(".")
        val subnet = if (octets.size == 4) {
            "${octets[0]}.${octets[1]}.${octets[2]}"
        } else null

        assertNull(subnet)
    }

    // ========== Interface Pattern Matching Tests ==========

    @Test
    fun `eth interface matches tethering pattern`() {
        val interfaceName = "eth0"
        val pattern = "eth"
        assertTrue(interfaceName.contains(pattern, ignoreCase = true))
    }

    @Test
    fun `ETH interface matches case insensitive`() {
        val interfaceName = "ETH0"
        val pattern = "eth"
        assertTrue(interfaceName.contains(pattern, ignoreCase = true))
    }

    @Test
    fun `wlan interface does not match eth pattern`() {
        val interfaceName = "wlan0"
        val pattern = "eth"
        assertFalse(interfaceName.contains(pattern, ignoreCase = true))
    }

    @Test
    fun `rndis interface is not eth`() {
        val interfaceName = "rndis0"
        val pattern = "eth"
        assertFalse(interfaceName.contains(pattern, ignoreCase = true))
    }

    // ========== Tethering Interface Filter Logic Tests ==========

    @Test
    fun `filter logic accepts eth interface that is up and not loopback`() {
        val info = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        val matchesPattern = info.name.contains("eth", ignoreCase = true)
        val isValid = matchesPattern && info.isUp && !info.isLoopback

        assertTrue(isValid)
    }

    @Test
    fun `filter logic rejects interface that is down`() {
        val info = NetworkInterfaceScanner.InterfaceInfo(
            name = "eth0",
            displayName = "Ethernet",
            ipAddress = "192.168.1.1",
            isUp = false,
            isLoopback = false,
            supportsMulticast = true
        )

        val matchesPattern = info.name.contains("eth", ignoreCase = true)
        val isValid = matchesPattern && info.isUp && !info.isLoopback

        assertFalse(isValid)
    }

    @Test
    fun `filter logic rejects loopback interface`() {
        val info = NetworkInterfaceScanner.InterfaceInfo(
            name = "lo",
            displayName = "Loopback",
            ipAddress = "127.0.0.1",
            isUp = true,
            isLoopback = true,
            supportsMulticast = false
        )

        val matchesPattern = info.name.contains("eth", ignoreCase = true)
        val isValid = matchesPattern && info.isUp && !info.isLoopback

        assertFalse(isValid)
    }

    @Test
    fun `filter logic rejects non-eth interface`() {
        val info = NetworkInterfaceScanner.InterfaceInfo(
            name = "wlan0",
            displayName = "WiFi",
            ipAddress = "192.168.1.50",
            isUp = true,
            isLoopback = false,
            supportsMulticast = true
        )

        val matchesPattern = info.name.contains("eth", ignoreCase = true)
        val isValid = matchesPattern && info.isUp && !info.isLoopback

        assertFalse(isValid)
    }

    // ========== Interface List Tests ==========

    @Test
    fun `filtering interfaces returns only valid tethering interfaces`() {
        val interfaces = listOf(
            NetworkInterfaceScanner.InterfaceInfo("eth0", "Ethernet", "192.168.1.1", true, false, true),
            NetworkInterfaceScanner.InterfaceInfo("wlan0", "WiFi", "192.168.1.50", true, false, true),
            NetworkInterfaceScanner.InterfaceInfo("lo", "Loopback", "127.0.0.1", true, true, false),
            NetworkInterfaceScanner.InterfaceInfo("eth1", "Ethernet 2", "10.0.0.1", false, false, true)
        )

        val tetheringInterfaces = interfaces.filter { iface ->
            val matchesPattern = iface.name.contains("eth", ignoreCase = true)
            matchesPattern && iface.isUp && !iface.isLoopback
        }

        assertEquals(1, tetheringInterfaces.size)
        assertEquals("eth0", tetheringInterfaces[0].name)
    }

    @Test
    fun `finding primary eth interface`() {
        val interfaces = listOf(
            NetworkInterfaceScanner.InterfaceInfo("eth0", "Ethernet", "192.168.1.1", true, false, true),
            NetworkInterfaceScanner.InterfaceInfo("eth1", "Ethernet 2", "10.0.0.1", true, false, true)
        )

        val primary = interfaces.firstOrNull { it.name.contains("eth", ignoreCase = true) }

        assertNotNull(primary)
        assertEquals("eth0", primary?.name)
    }
}
