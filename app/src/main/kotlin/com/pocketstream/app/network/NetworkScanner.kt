package com.pocketstream.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

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
        val completedCount = AtomicInteger(0)

        // Scan IPs in parallel using async
        val scanJobs = (startIp..endIp).map { lastOctet ->
            async {
                val ipAddress = "$subnet.$lastOctet"

                val result = if (isHostReachable(ipAddress)) {
                    Log.d(TAG, "Found active IP: $ipAddress")
                    ipAddress
                } else {
                    null
                }
                onProgress?.invoke(completedCount.incrementAndGet(), totalIps)
                result
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

}
