package dev.hapax.watch.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Keeps WiFi active on the watch and tracks network availability.
 * Uses [ConnectivityManager.requestNetwork] with TRANSPORT_WIFI to
 * prevent the radio from sleeping while the sensor service is running.
 */
class ConnectivityHelper(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var isNetworkAvailable: Boolean = false
        private set

    private var listener: ((Boolean) -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "WiFi network available")
            isNetworkAvailable = true
            listener?.invoke(true)
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "WiFi network lost")
            isNetworkAvailable = false
            listener?.invoke(false)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            val hasInternet = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
            )
            Log.d(TAG, "Network capabilities changed, internet=$hasInternet")
        }
    }

    /**
     * Request a WiFi network and begin monitoring connectivity.
     * @param onNetworkChanged optional callback invoked when network state changes
     */
    fun requestWifi(onNetworkChanged: ((Boolean) -> Unit)? = null) {
        listener = onNetworkChanged
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.requestNetwork(request, networkCallback)
            Log.i(TAG, "WiFi network requested")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot request network (missing permission): ${e.message}")
        }
    }

    /** Release the network request. Safe to call multiple times. */
    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.i(TAG, "WiFi network request released")
        } catch (e: IllegalArgumentException) {
            // Callback was not registered — harmless
        }
        isNetworkAvailable = false
        listener = null
    }

    companion object {
        private const val TAG = "ConnectivityHelper"
    }
}
