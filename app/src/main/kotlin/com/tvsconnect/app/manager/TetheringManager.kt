package com.tvsconnect.app.manager

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Monitors Ethernet tethering status only.
 *
 * Note: This class only checks tethering status. Users must enable tethering
 * manually in Android Settings → Network & Internet → Hotspot & Tethering.
 *
 * USB, Bluetooth, and WiFi tethering are NOT supported - only Ethernet.
 */
class TetheringManager(private val context: Context) {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "TetheringManager"
    }

    /**
     * Checks if Ethernet tethering is currently active.
     * Only detects "eth" interfaces, excludes USB (rndis), Bluetooth, and WiFi.
     * @return true if Ethernet tethering is active, false otherwise
     */
    suspend fun isEthernetTetheringActive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val tetheredIfaces = getTetheredIfaces()
            // Only check for "eth" interfaces - exclude rndis (USB), bt (Bluetooth), wlan (WiFi)
            val isActive = tetheredIfaces.any { iface ->
                iface.contains("eth", ignoreCase = true) &&
                !iface.contains("rndis", ignoreCase = true) &&
                !iface.contains("usb", ignoreCase = true)
            }
            Log.d(TAG, "Ethernet tethering active: $isActive, interfaces: $tetheredIfaces")
            isActive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Ethernet tethering status: ${e.message}", e)
            false
        }
    }

    /**
     * Gets the list of currently tethered network interfaces.
     * @return List of interface names
     */
    private fun getTetheredIfaces(): List<String> {
        return try {
            val method: Method = connectivityManager.javaClass.getDeclaredMethod("getTetheredIfaces")
            val result = method.invoke(connectivityManager)
            (result as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tethered interfaces: ${e.message}", e)
            emptyList()
        }
    }
}
