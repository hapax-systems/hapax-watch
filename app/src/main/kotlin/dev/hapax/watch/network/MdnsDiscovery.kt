package dev.hapax.watch.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovers `_hapax._tcp` services on the local network via mDNS/NSD.
 * Thread-safe — can be called from any coroutine context.
 */
class MdnsDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Searches for the hapax service on the LAN.
     * @param timeout max time to wait in milliseconds
     * @return "ip:port" string if found, null otherwise
     */
    fun discover(timeout: Long = 5000): String? {
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val listenerRef = AtomicReference<NsdManager.DiscoveryListener?>(null)

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                // Resolve to get IP and port
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${si.serviceName}: error $errorCode")
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host?.hostAddress
                        val port = si.port
                        if (host != null) {
                            val address = "$host:$port"
                            Log.i(TAG, "Resolved hapax service: $address")
                            result.set(address)
                            latch.countDown()
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS discovery start failed: error $errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS discovery stop failed: error $errorCode")
            }
        }

        listenerRef.set(discoveryListener)

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS discovery: ${e.message}")
            return null
        }

        // Wait for result or timeout
        latch.await(timeout, TimeUnit.MILLISECONDS)

        // Stop discovery
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery: ${e.message}")
        }

        return result.get()
    }

    companion object {
        private const val TAG = "MdnsDiscovery"
        private const val SERVICE_TYPE = "_hapax._tcp."
    }
}
