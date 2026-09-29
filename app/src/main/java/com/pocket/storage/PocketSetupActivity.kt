package com.pocket.storage

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.bluetooth.le.ScanFilter
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor

class PocketSetupActivity : Activity() {

    companion object {
        const val ACTION_REGISTER = "com.pocket.storage.action.REGISTER"
        const val ACTION_ADVERTISE = "com.pocket.storage.action.ADVERTISE"
        const val ACTION_CONNECT = "com.pocket.storage.action.CONNECT"
        const val ACTION_DISCONNECT = "com.pocket.storage.action.DISCONNECT"

        private const val SELECT_DEVICE_REQUEST_CODE = 4101
        private const val REQUEST_BLUETOOTH_CONNECT = 4102
        private const val REQUEST_BLUETOOTH_ADVERTISE = 4103
        private const val REQUEST_FELLOW_BLE = 4104
        private const val REQUEST_CONNECTION_PERMISSIONS = 4105
    }

    private lateinit var labelInput: EditText
    private lateinit var status: TextView
    private lateinit var registry: FellowSharerRegistry
    private lateinit var companionManager: CompanionDeviceManager
    private val executor: Executor = Executor { it.run() }
    private var pendingDevice: BluetoothDevice? = null
    private var pendingAssociation: AssociationInfo? = null
    private var gattClient: PocketGattClient? = null
    private var gattServer: PocketGattServer? = null
    private var pendingConnectAssociationId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registry = FellowSharerRegistry(applicationContext)
        companionManager = getSystemService(CompanionDeviceManager::class.java)
        PocketIdentity.ensure(applicationContext)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Pocket Storage setup"
            textSize = 24f
        }

        labelInput = EditText(this).apply {
            hint = "Fellow sharer name"
            setSingleLine(true)
            setText("Fellow Sharer")
        }

        val register = Button(this).apply {
            text = "Register fellow sharer"
            setOnClickListener { beginRegistration() }
        }

        val advertise = Button(this).apply {
            text = "Advertise as fellow sharer"
            setOnClickListener { startAdvertising() }
        }

        status = TextView(this).apply {
            text = "Ready"
        }

        layout.addView(title)
        layout.addView(labelInput)
        layout.addView(register)
        layout.addView(advertise)
        layout.addView(status)

        setContentView(
            layout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        when (intent.action) {
            ACTION_REGISTER -> beginRegistration()
            ACTION_ADVERTISE -> startAdvertising()
            ACTION_CONNECT -> connectRegistered(intent.getIntExtra("association_id", -1))
            ACTION_DISCONNECT -> {
                PocketConnectionService.stop(this)
                finish()
            }
        }
    }

    private fun beginRegistration() {
        if (!packageManager.hasSystemFeature(
                PackageManager.FEATURE_COMPANION_DEVICE_SETUP
            )
        ) {
            status.text = "Companion device setup is not supported on this phone."
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PocketBle.SERVICE_UUID))
            .build()

        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()

        status.text = "Looking for a Pocket fellow sharer…"

        companionManager.associate(
            request,
            executor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    startIntentSenderForResult(
                        intentSender,
                        SELECT_DEVICE_REQUEST_CODE,
                        null,
                        0,
                        0,
                        0
                    )
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    pendingAssociation = associationInfo
                    val id = associationInfo.id
                    val mac = runCatching {
                        associationInfo.deviceMacAddress?.toString()
                    }.getOrNull()
                    registry.register(id, labelInput.text.toString(), mac)
                    startPresenceObservation(associationInfo)
                    status.text = "Registered fellow sharer #$id"
                    connectIfReady()
                }

                override fun onFailure(errorMessage: CharSequence?) {
                    status.text = "Registration failed: ${errorMessage ?: "unknown error"}"
                }

                @Suppress("DEPRECATION")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    startIntentSenderForResult(
                        chooserLauncher,
                        SELECT_DEVICE_REQUEST_CODE,
                        null,
                        0,
                        0,
                        0
                    )
                }
            }
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SELECT_DEVICE_REQUEST_CODE || resultCode != RESULT_OK) return

        pendingDevice =
            data?.getParcelableExtra<BluetoothDevice>(
                CompanionDeviceManager.EXTRA_DEVICE
            )

        val device = pendingDevice ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT
            )
        } else {
            if (device != null) {
                device.createBond()
                connectIfReady()
            }
        }
    }


    private fun connectRegistered(associationId: Int) {
        if (associationId < 0) {
            status.text = "association_id is required."
            return
        }

        val fellow = registry.get(associationId)
        if (fellow == null) {
            status.text = "Fellow sharer #$associationId is not registered."
            return
        }

        if (!fellow.nearby) {
            status.text = "Fellow sharer is not currently nearby."
            return
        }

        pendingConnectAssociationId = associationId
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }

        val wifiPermission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (checkSelfPermission(wifiPermission) != PackageManager.PERMISSION_GRANTED) {
            missing += wifiPermission
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_CONNECTION_PERMISSIONS)
            return
        }

        connectRegisteredNow(associationId)
    }

    private fun connectRegisteredNow(associationId: Int) {
        val fellow = registry.get(associationId)
        val mac = fellow?.macAddress
        if (fellow == null || mac.isNullOrBlank()) {
            status.text = "Fellow BLE address is unavailable."
            return
        }

        val bluetoothManager =
            getSystemService(android.bluetooth.BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Bluetooth is disabled."
            return
        }

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        if (device == null) {
            status.text = "Could not resolve fellow BLE device."
            return
        }

        gattClient?.close()
        gattClient = PocketGattClient(
            context = this,
            device = device,
            associationId = associationId,
            registry = registry,
            onAuthenticated = {
                status.text = "Fellow authenticated. Starting local network…"
                PocketConnectionService.start(this, associationId)
            },
            onFailure = {
                status.text = "BLE connection failed: $it"
            }
        )

        status.text = "Re-authenticating fellow sharer over BLE…"
        gattClient?.connect()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_BLUETOOTH_CONNECT &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            pendingDevice?.createBond()
            pendingAssociation?.let(::startPresenceObservation)
            connectIfReady()
        }

        if (requestCode == REQUEST_BLUETOOTH_ADVERTISE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startAdvertising()
        }

        if (requestCode == REQUEST_FELLOW_BLE &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startFellowBle()
        }

        if (requestCode == REQUEST_CONNECTION_PERMISSIONS &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            pendingConnectAssociationId?.let(::connectRegisteredNow)
        }
    }

    private fun startPresenceObservation(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 36) {
            val request =
                android.companion.ObservingDevicePresenceRequest.Builder()
                    .setAssociationId(associationInfo.id)
                    .build()
            companionManager.startObservingDevicePresence(request)
        } else {
            @Suppress("DEPRECATION")
            associationInfo.deviceMacAddress?.toString()?.let {
                companionManager.startObservingDevicePresence(it)
            }
        }
    }

    private fun startAdvertising() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_ADVERTISE
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), REQUEST_FELLOW_BLE)
                return
            }
        }

        startFellowBle()
    }

    private fun startFellowBle() {
        gattServer?.stop()
        gattServer = PocketGattServer(this) { _, _ ->
            status.text = "Pocket BLE handshake authenticated."
        }

        val serverStarted = gattServer?.start() == true
        val advertisingStarted = PocketBle.startAdvertising(
            this,
            PocketBle.newLoggingCallback()
        )

        status.text = when {
            serverStarted && advertisingStarted ->
                "Advertising Pocket BLE with GATT handshake support."
            serverStarted ->
                "GATT server started, but BLE advertising failed."
            advertisingStarted ->
                "BLE advertising started, but GATT server failed."
            else ->
                "Could not start Pocket BLE. Check Bluetooth and permissions."
        }
    }

    private fun connectIfReady() {
        val device = pendingDevice ?: return
        val association = pendingAssociation ?: return

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        gattClient?.close()
        gattClient = PocketGattClient(
            context = this,
            device = device,
            associationId = association.id,
            registry = registry,
            onAuthenticated = {
                status.text = "Fellow sharer authenticated. Ready for connection."
            },
            onFailure = {
                status.text = "BLE handshake failed: $it"
            }
        )

        status.text = "Authenticating fellow sharer over BLE…"
        gattClient?.connect()
    }

    override fun onDestroy() {
        gattClient?.close()
        gattServer?.stop()
        PocketBle.stopAdvertising(this)
        super.onDestroy()
    }
}
