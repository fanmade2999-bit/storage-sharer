package com.pocket.storage

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log

class PocketCompanionService : CompanionDeviceService() {
    private lateinit var registry: FellowSharerRegistry

    override fun onCreate() {
        super.onCreate()
        registry = FellowSharerRegistry(applicationContext)
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return

        val id = event.associationId
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                registry.markNearby(id, true)
                Log.i("PocketBle", "Fellow association $id is nearby")
            }

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> {
                registry.markNearby(id, false)
                Log.i("PocketBle", "Fellow association $id is no longer nearby")
            }
        }

        // Presence never starts Wi-Fi, Pocket Web, or file access.
    }

    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        registry.markNearby(associationInfo.id, true)
    }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        registry.markNearby(associationInfo.id, false)
    }
}
