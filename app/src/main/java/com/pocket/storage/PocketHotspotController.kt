package com.pocket.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SoftApConfiguration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

internal data class PocketHotspotCredentials(
    val ssid: String,
    val password: String
)

internal class PocketHotspotController(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(WifiManager::class.java)
            ?: error("Wi-Fi service unavailable")

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start(
        onStarted: (PocketHotspotCredentials) -> Unit,
        onFailed: (Int) -> Unit
    ): Boolean {
        if (!hasRequiredPermission()) {
            onFailed(-1)
            return false
        }

        if (reservation != null) return true

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(
                hotspotReservation: WifiManager.LocalOnlyHotspotReservation
            ) {
                reservation = hotspotReservation

                val credentials = readCredentials(hotspotReservation)
                onStarted(credentials)
            }

            override fun onStopped() {
                reservation = null
            }

            override fun onFailed(reason: Int) {
                reservation = null
                onFailed(reason)
            }
        }

        @Suppress("DEPRECATION")
        wifiManager.startLocalOnlyHotspot(
            callback,
            Handler(Looper.getMainLooper())
        )
        return true
    }

    fun stop() {
        reservation?.close()
        reservation = null
    }

    fun isRunning(): Boolean = reservation != null


    fun findHotspotHostAddress(
        onFound: (String) -> Unit,
        onFailed: () -> Unit
    ) {
        Thread {
            repeat(20) {
                val address = findCandidateAddress()
                if (address != null) {
                    Handler(Looper.getMainLooper()).post {
                        onFound(address)
                    }
                    return@Thread
                }
                Thread.sleep(250)
            }

            Handler(Looper.getMainLooper()).post {
                onFailed()
            }
        }.start()
    }

    private fun findCandidateAddress(): String? {
        val connectivity =
            appContext.getSystemService(ConnectivityManager::class.java)
                ?: return null

        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            val isLocalNetwork =
                if (Build.VERSION.SDK_INT >= 35) {
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
                    )
                } else {
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

            if (!isLocalNetwork) continue

            val links = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            val address = links.firstOrNull { link ->
                link.address is Inet4Address && !link.address.isLoopbackAddress
            }?.address as? Inet4Address

            if (address != null) {
                return address.hostAddress
            }
        }

        return null
    }

    private fun hasRequiredPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    @Suppress("DEPRECATION")
    private fun readCredentials(
        hotspotReservation: WifiManager.LocalOnlyHotspotReservation
    ): PocketHotspotCredentials {
        return if (Build.VERSION.SDK_INT >= 30) {
            val config: SoftApConfiguration =
                hotspotReservation.softApConfiguration
                    ?: error("Android did not return hotspot configuration")
            PocketHotspotCredentials(
                ssid = config.ssid ?: error("hotspot SSID unavailable"),
                password = config.passphrase ?: error("hotspot password unavailable")
            )
        } else {
            val config: WifiConfiguration =
                hotspotReservation.wifiConfiguration
                    ?: error("Android did not return hotspot configuration")
            PocketHotspotCredentials(
                ssid = config.SSID,
                password = config.preSharedKey
            )
        }
    }
}
