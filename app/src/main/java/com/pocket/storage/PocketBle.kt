package com.pocket.storage

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

internal object PocketBle {
    private const val LOG_TAG = "PocketBle"

    val SERVICE_UUID: UUID =
        UUID.fromString("6f9e7a31-2e8b-4b2e-a65d-0f8d9a4c4d01")

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    fun startAdvertising(context: Context, callback: AdvertiseCallback): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter: BluetoothAdapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false

        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiser = leAdvertiser
        advertiseCallback = callback
        leAdvertiser.startAdvertising(settings, data, callback)
        return true
    }

    fun stopAdvertising(context: Context) {
        val current = advertiser ?: return
        if (android.os.Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            advertiseCallback?.let { current.stopAdvertising(it) }
        }
        advertiser = null
        advertiseCallback = null
    }

    fun newLoggingCallback(): AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(LOG_TAG, "Pocket BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(LOG_TAG, "Pocket BLE advertising failed: $errorCode")
        }
    }
}
