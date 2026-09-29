package com.pocket.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class PocketFellowService : Service() {

    companion object {
        const val ACTION_START = "com.pocket.storage.action.FELLOW_START"
        const val ACTION_STOP = "com.pocket.storage.action.FELLOW_STOP"

        private const val CHANNEL_ID = "pocket_fellow"
        private const val NOTIFICATION_ID = 8788

        fun start(context: Context) {
            val intent = Intent(context, PocketFellowService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PocketFellowService::class.java))
        }
    }

    private var gattServer: PocketGattServer? = null
    private lateinit var wifiJoin: PocketWifiJoinController

    override fun onCreate() {
        super.onCreate()
        wifiJoin = PocketWifiJoinController(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFellowMode()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFellowMode() {
        startForegroundCompat(
            buildNotification("Waiting for Pocket owner connection")
        )

        gattServer?.stop()
        gattServer = PocketGattServer(
            applicationContext,
            onAuthenticated = { _, _ ->
                updateNotification("Pocket owner authenticated")
            },
            onNetworkCredentials = { ssid, password ->
                val accepted = wifiJoin.suggestAndConnect(ssid, password)
                if (accepted) {
                    updateNotification("Wi-Fi network provisioned for Pocket connection")
                }
                accepted
            }
        )

        val serverStarted = gattServer?.start() == true
        val advertiserStarted =
            PocketBle.startAdvertising(
                applicationContext,
                PocketBle.newLoggingCallback()
            )

        if (!serverStarted || !advertiserStarted) {
            updateNotification("Pocket BLE could not start")
            stopSelf()
        } else {
            updateNotification("Pocket fellow mode active")
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pocket Storage")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return

        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Pocket fellow mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    override fun onDestroy() {
        gattServer?.stop()
        gattServer = null
        PocketBle.stopAdvertising(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
