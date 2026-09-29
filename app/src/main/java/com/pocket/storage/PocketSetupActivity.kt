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

        private const val SELECT_DEVICE_REQUEST_CODE = 4101
        private const val REQUEST_BLUETOOTH_CONNECT = 4102
        private const val REQUEST_BLUETOOTH_ADVERTISE = 4103
    }

    private lateinit var labelInput: EditText
    private lateinit var status: TextView
    private lateinit var registry: FellowSharerRegistry
    private lateinit var companionManager: CompanionDeviceManager
    private val executor: Executor = Executor { it.run() }
    private var pendingDevice: BluetoothDevice? = null

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
                    val id = associationInfo.id
                    val mac = associationInfo.deviceMacAddress?.toString()
                    registry.register(id, labelInput.text.toString(), mac)
                    startPresenceObservation(associationInfo)
                    status.text = "Registered fellow sharer #$id"
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
            device.createBond()
        }
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
        }

        if (requestCode == REQUEST_BLUETOOTH_ADVERTISE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startAdvertising()
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
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                REQUEST_BLUETOOTH_ADVERTISE
            )
            return
        }

        val started = PocketBle.startAdvertising(
            this,
            PocketBle.newLoggingCallback()
        )

        status.text = when {
            started -> "Advertising Pocket BLE until this setup screen closes."
            else -> "Could not start BLE advertising. Check Bluetooth and permissions."
        }
    }

    override fun onDestroy() {
        PocketBle.stopAdvertising(this)
        super.onDestroy()
    }
}
