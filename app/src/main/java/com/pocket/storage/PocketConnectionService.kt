package com.pocket.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class PocketConnectionService : Service() {

    companion object {
        const val ACTION_START = "com.pocket.storage.action.CONNECTION_START"
        const val ACTION_STOP = "com.pocket.storage.action.CONNECTION_STOP"
        const val ACTION_FELLOW_AWAY = "com.pocket.storage.action.CONNECTION_FELLOW_AWAY"
        const val ACTION_FELLOW_NEAR = "com.pocket.storage.action.CONNECTION_FELLOW_NEAR"
        const val EXTRA_ASSOCIATION_ID = "association_id"

        @Volatile
        var activeAssociationId: Int? = null

        private const val CHANNEL_ID = "pocket_connection"
        private const val NOTIFICATION_ID = 8787
        private const val AWAY_GRACE_MILLIS = 30_000L

        fun start(context: Context, associationId: Int) {
            val intent = Intent(context, PocketConnectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ASSOCIATION_ID, associationId)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PocketConnectionService::class.java))
        }

        fun notifyFellowPresence(context: Context, associationId: Int, nearby: Boolean) {
            if (activeAssociationId != associationId) return

            val intent = Intent(context, PocketConnectionService::class.java).apply {
                action = if (nearby) ACTION_FELLOW_NEAR else ACTION_FELLOW_AWAY
                putExtra(EXTRA_ASSOCIATION_ID, associationId)
            }
            context.startService(intent)
        }
    }

    private lateinit var state: PocketConnectionState
    private lateinit var registry: FellowSharerRegistry
    private lateinit var hotspot: PocketHotspotController
    private lateinit var termux: PocketTermuxBridge
    private var gattClient: PocketGattClient? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAwayId: Int? = null

    private val awayStop = Runnable {
        val id = pendingAwayId ?: return@Runnable
        pendingAwayId = null

        if (registry.get(id)?.nearby == false &&
            state.activeAssociationId() == id
        ) {
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        state = PocketConnectionState(applicationContext)
        registry = FellowSharerRegistry(applicationContext)
        hotspot = PocketHotspotController(applicationContext)
        termux = PocketTermuxBridge(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getIntExtra(EXTRA_ASSOCIATION_ID, -1)
                if (id >= 0) startConnection(id) else stopSelf()
            }

            ACTION_STOP -> stopSelf()

            ACTION_FELLOW_AWAY -> {
                scheduleAwayStop(
                    intent.getIntExtra(EXTRA_ASSOCIATION_ID, -1)
                )
            }

            ACTION_FELLOW_NEAR -> {
                cancelAwayStop(
                    intent.getIntExtra(EXTRA_ASSOCIATION_ID, -1)
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun startConnection(associationId: Int) {
        val fellow = registry.get(associationId)
        if (fellow == null || !fellow.nearby) {
            stopSelf()
            return
        }

        val mac = fellow.macAddress
        if (mac.isNullOrBlank()) {
            stopSelf()
            return
        }

        val manager =
            getSystemService(android.bluetooth.BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            stopSelf()
            return
        }

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        if (device == null) {
            stopSelf()
            return
        }

        activeAssociationId = associationId
        state.setActive(associationId)
        startForegroundCompat(buildNotification("Authenticating fellow over BLE"))

        gattClient?.close()
        gattClient = PocketGattClient(
            context = applicationContext,
            device = device,
            associationId = associationId,
            registry = registry,
            onAuthenticated = {
                startLocalNetwork(associationId)
            },
            onFailure = {
                updateNotification("BLE authentication failed")
                stopSelf()
            },
            onNetworkDelivered = {
                updateNotification("Pocket Web :8787 • fellow connected")
            }
        )

        if (gattClient?.connect() != true) {
            stopSelf()
        }
    }

    private fun startLocalNetwork(associationId: Int) {
        val started = hotspot.start(
            onStarted = { credentials ->
                hotspot.findHotspotHostAddress(
                    onFound = { host ->
                        val webStarted = termux.startPocketWeb(
                            host = host,
                            port = 8787
                        )

                        if (!webStarted) {
                            stopSelf()
                            return@findHotspotHostAddress
                        }

                        if (gattClient?.sendNetworkCredentials(
                                credentials.ssid,
                                credentials.password
                            ) != true
                        ) {
                            stopSelf()
                            return@findHotspotHostAddress
                        }

                        updateNotification("Wi-Fi $host:8787 ready")
                    },
                    onFailed = {
                        updateNotification("Could not isolate Pocket Web to hotspot")
                        stopSelf()
                    }
                )
            },
            onFailed = {
                stopSelf()
            }
        )

        if (!started) stopSelf()
    }

    private fun scheduleAwayStop(associationId: Int) {
        if (state.activeAssociationId() != associationId) return
        pendingAwayId = associationId
        handler.removeCallbacks(awayStop)
        handler.postDelayed(awayStop, AWAY_GRACE_MILLIS)
    }

    private fun cancelAwayStop(associationId: Int) {
        if (pendingAwayId == associationId) {
            pendingAwayId = null
            handler.removeCallbacks(awayStop)
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pocket Storage")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pocket connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hotspot.stop()
        termux.stopPocketWeb()
        activeAssociationId = null
        state.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
