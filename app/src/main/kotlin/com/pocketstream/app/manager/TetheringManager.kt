package com.pocketstream.app.manager

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
     * Detects interfaces starting with "eth" (e.g., eth0).
     * @return true if Ethernet tethering is active, false otherwise
     */
    suspend fun isEthernetTetheringActive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val tetheredIfaces = getTetheredIfaces()
            val isActive = tetheredIfaces.any { it.startsWith("eth", ignoreCase = true) }
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
            when (result) {
                is Array<*> -> result.mapNotNull { it as? String }
                is List<*> -> result.mapNotNull { it as? String }
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tethered interfaces: ${e.message}", e)
            emptyList()
        }
    }
}
