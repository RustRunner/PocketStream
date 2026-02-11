package com.tvsconnect.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Scans IP addresses on a network to discover active devices.
 */
class NetworkScanner {

    companion object {
        private const val TAG = "NetworkScanner"
        private const val TIMEOUT_MS = 1000 // 1 second timeout per IP
        private const val SCAN_PORT = 22 // SSH port - common on Raspberry Pi
    }

    /**
     * Scans a range of IP addresses to find active devices.
     * @param subnet The subnet to scan (e.g., "192.168.42")
     * @param startIp Starting IP in range (default 1)
     * @param endIp Ending IP in range (default 254)
     * @param onProgress Optional callback for scan progress (current IP, total IPs)
     * @return List of active IP addresses
     */
    suspend fun scanSubnet(
        subnet: String,
        startIp: Int = 1,
        endIp: Int = 254,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting network scan on subnet $subnet (IPs $startIp-$endIp)")

        val activeIps = mutableListOf<String>()
        val totalIps = endIp - startIp + 1

        // Scan IPs in parallel using async
        val scanJobs = (startIp..endIp).map { lastOctet ->
            async {
                val ipAddress = "$subnet.$lastOctet"
                onProgress?.invoke(lastOctet - startIp + 1, totalIps)

                if (isHostReachable(ipAddress)) {
                    Log.d(TAG, "Found active IP: $ipAddress")
                    ipAddress
                } else {
                    null
                }
            }
        }

        // Wait for all scans to complete
        val results = scanJobs.awaitAll()
        activeIps.addAll(results.filterNotNull())

        Log.d(TAG, "Scan complete. Found ${activeIps.size} active IPs: $activeIps")
        activeIps
    }

    /**
     * Checks if a specific IP address is reachable.
     * Uses TCP connection attempt on common port (SSH - 22) for faster detection.
     * @param ipAddress The IP address to check
     * @return true if host is reachable, false otherwise
     */
    private suspend fun isHostReachable(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Method 1: Try TCP connection on SSH port (faster and more reliable)
            if (isPortOpen(ipAddress, SCAN_PORT)) {
                return@withContext true
            }

            // Method 2: Fallback to InetAddress.isReachable (ICMP ping)
            val inetAddress = InetAddress.getByName(ipAddress)
            inetAddress.isReachable(TIMEOUT_MS)
        } catch (e: Exception) {
            // Host not reachable
            false
        }
    }

    /**
     * Checks if a specific port is open on the given IP address.
     * @param ipAddress The IP address to check
     * @param port The port to check
     * @return true if port is open, false otherwise
     */
    private fun isPortOpen(ipAddress: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ipAddress, port), TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Performs a quick scan to find the first active IP in the subnet.
     * Useful for faster discovery when you just need to find any device.
     * @param subnet The subnet to scan (e.g., "192.168.42")
     * @param startIp Starting IP in range (default 1)
     * @param endIp Ending IP in range (default 254)
     * @return First active IP found, or null if none found
     */
    suspend fun findFirstActiveIp(
        subnet: String,
        startIp: Int = 1,
        endIp: Int = 254
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Quick scan: Finding first active IP on subnet $subnet")

        // Common Raspberry Pi IP addresses to check first
        val commonIps = listOf(
            "$subnet.1",    // Gateway
            "$subnet.129",  // Common USB tethering client IP
            "$subnet.10",
            "$subnet.100",
            "$subnet.2"
        )

        // Check common IPs first
        for (ip in commonIps) {
            val octet = ip.substringAfterLast(".").toIntOrNull() ?: continue
            if (octet in startIp..endIp && isHostReachable(ip)) {
                Log.d(TAG, "Found active IP (common): $ip")
                return@withContext ip
            }
        }

        // If not found in common IPs, scan the entire range
        for (lastOctet in startIp..endIp) {
            val ipAddress = "$subnet.$lastOctet"
            if (isHostReachable(ipAddress)) {
                Log.d(TAG, "Found active IP (full scan): $ipAddress")
                return@withContext ipAddress
            }
        }

        Log.d(TAG, "No active IP found on subnet $subnet")
        null
    }
}
