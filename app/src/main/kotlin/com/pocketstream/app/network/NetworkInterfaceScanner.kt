package com.pocketstream.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Scans and detects network interfaces, particularly tethering interfaces.
 */
class NetworkInterfaceScanner {

    companion object {
        private const val TAG = "NetworkInterfaceScanner"

        // Ethernet tethering interface pattern only (USB tethering not supported)
        private val TETHERING_INTERFACE_PATTERNS = listOf(
            "eth"    // Ethernet only
        )
    }

    /**
     * Data class representing a network interface with its properties.
     */
    data class InterfaceInfo(
        val name: String,
        val displayName: String,
        val ipAddress: String?,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val supportsMulticast: Boolean
    )

    /**
     * Gets all active network interfaces on the device.
     * @return List of InterfaceInfo objects
     */
    suspend fun getAllInterfaces(): List<InterfaceInfo> = withContext(Dispatchers.IO) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val interfaceList = mutableListOf<InterfaceInfo>()

            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                val info = createInterfaceInfo(netInterface)
                interfaceList.add(info)

                Log.d(TAG, "Found interface: ${info.name} (${info.displayName}), " +
                        "IP: ${info.ipAddress}, Up: ${info.isUp}")
            }

            interfaceList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network interfaces: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Gets only active tethering interfaces (rndis0, eth0, usb0, etc.).
     * @return List of active tethering interfaces
     */
    suspend fun getTetheringInterfaces(): List<InterfaceInfo> = withContext(Dispatchers.IO) {
        try {
            val allInterfaces = getAllInterfaces()
            val tetheringInterfaces = allInterfaces.filter { iface ->
                // Check if interface name matches tethering patterns
                val matchesPattern = TETHERING_INTERFACE_PATTERNS.any { pattern ->
                    iface.name.contains(pattern, ignoreCase = true)
                }

                // Interface must be up and not loopback
                matchesPattern && iface.isUp && !iface.isLoopback
            }

            Log.d(TAG, "Found ${tetheringInterfaces.size} active tethering interfaces")
            tetheringInterfaces.forEach { iface ->
                Log.d(TAG, "Tethering interface: ${iface.name} - ${iface.ipAddress}")
            }

            tetheringInterfaces
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tethering interfaces: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Gets the primary active Ethernet tethering interface (eth0).
     * @return InterfaceInfo of the primary tethering interface, or null if none found
     */
    suspend fun getPrimaryTetheringInterface(): InterfaceInfo? = withContext(Dispatchers.IO) {
        val tetheringInterfaces = getTetheringInterfaces()

        // Only look for Ethernet interfaces
        val primary = tetheringInterfaces.firstOrNull { it.name.contains("eth", ignoreCase = true) }

        if (primary != null) {
            Log.d(TAG, "Primary tethering interface: ${primary.name} - ${primary.ipAddress}")
        } else {
            Log.w(TAG, "No active Ethernet tethering interface found")
        }

        primary
    }

    /**
     * Gets the IP address subnet for the given interface.
     * Useful for determining the scan range (e.g., 192.168.42.0/24).
     * @param interfaceInfo The interface to analyze
     * @return IP subnet string (e.g., "192.168.42"), or null if unavailable
     */
    suspend fun getInterfaceSubnet(interfaceInfo: InterfaceInfo): String? = withContext(Dispatchers.IO) {
        try {
            interfaceInfo.ipAddress?.let { ipAddress ->
                // Extract subnet (first 3 octets)
                val octets = ipAddress.split(".")
                if (octets.size == 4) {
                    val subnet = "${octets[0]}.${octets[1]}.${octets[2]}"
                    Log.d(TAG, "Interface ${interfaceInfo.name} subnet: $subnet")
                    return@withContext subnet
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get interface subnet: ${e.message}", e)
            null
        }
    }

    /**
     * Creates an InterfaceInfo object from a NetworkInterface.
     */
    private fun createInterfaceInfo(netInterface: NetworkInterface): InterfaceInfo {
        val ipAddress = getIpv4Address(netInterface)

        return InterfaceInfo(
            name = netInterface.name,
            displayName = netInterface.displayName,
            ipAddress = ipAddress,
            isUp = netInterface.isUp,
            isLoopback = netInterface.isLoopback,
            supportsMulticast = netInterface.supportsMulticast()
        )
    }

    /**
     * Extracts the IPv4 address from a NetworkInterface.
     * @param netInterface The network interface
     * @return IPv4 address string, or null if not found
     */
    private fun getIpv4Address(netInterface: NetworkInterface): String? {
        try {
            val addresses = netInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                // Return only IPv4 addresses (not IPv6)
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IPv4 address for ${netInterface.name}: ${e.message}", e)
        }
        return null
    }
}
