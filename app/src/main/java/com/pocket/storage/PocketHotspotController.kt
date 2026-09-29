package com.pocket.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

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
