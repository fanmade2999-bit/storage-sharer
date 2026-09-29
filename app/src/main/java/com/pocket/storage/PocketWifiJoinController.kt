package com.pocket.storage

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build

internal class PocketWifiJoinController(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(WifiManager::class.java)
            ?: error("Wi-Fi service unavailable")

    private var lastSuggestion: WifiNetworkSuggestion? = null

    fun suggestAndConnect(ssid: String, password: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            return false
        }

        val suggestion = runCatching {
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()
        }.getOrElse { return false }

        lastSuggestion?.let {
            runCatching {
                wifiManager.removeNetworkSuggestions(listOf(it))
            }
        }

        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        ) {
            lastSuggestion = suggestion
            return true
        }

        return false
    }

    fun forgetLastSuggestion() {
        val suggestion = lastSuggestion ?: return
        runCatching {
            wifiManager.removeNetworkSuggestions(listOf(suggestion))
        }
        lastSuggestion = null
    }
}
