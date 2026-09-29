package com.pocket.storage

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64

internal class PocketGattClient(
    context: Context,
    private val device: BluetoothDevice,
    private val associationId: Int,
    private val registry: FellowSharerRegistry,
    private val onAuthenticated: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onNetworkDelivered: () -> Unit = {}
) {

    private val appContext = context.applicationContext
    private var gatt: BluetoothGatt? = null
    private var nonce: ByteArray? = null
    private var ownerPublicKey: ByteArray? = null
    private var fellowPublicKey: ByteArray? = null
    private var transcript: ByteArray? = null
    private var responseHandled = false
    private var closing = false

    fun connect(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onFailure("Bluetooth connect permission is required")
            return false
        }

        gatt =
            if (Build.VERSION.SDK_INT >= 23) {
                @Suppress("MissingPermission")
                device.connectGatt(
                    appContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                @Suppress("MissingPermission")
                device.connectGatt(appContext, false, callback)
            }

        return gatt != null
    }

    fun close() {
        closing = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE connection failed: $status")
                close()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (Build.VERSION.SDK_INT >= 21) {
                    @Suppress("MissingPermission")
                    gatt.requestMtu(247)
                } else {
                    @Suppress("MissingPermission")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!closing) fail("BLE device disconnected")
                gatt.close()
            }
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            @Suppress("MissingPermission")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE service discovery failed: $status")
                return
            }

            val service = gatt.getService(PocketBle.SERVICE_UUID)
            if (service == null) {
                fail("Pocket BLE service not found")
                return
            }

            val challenge =
                service.getCharacteristic(PocketGattProtocol.CHALLENGE_UUID)
            val response =
                service.getCharacteristic(PocketGattProtocol.RESPONSE_UUID)

            if (challenge == null || response == null) {
                fail("Pocket handshake characteristics missing")
                return
            }

            nonce = PocketGattProtocol.randomNonce()
            ownerPublicKey =
                PocketIdentity.ensure(appContext).public.encoded

            val payload = PocketGattProtocol.encodeChallenge(
                ownerPublicKey!!,
                nonce!!
            )

            writeCharacteristic(gatt, challenge, payload)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE challenge write failed: $status")
                return
            }

            if (characteristic.uuid == PocketGattProtocol.CHALLENGE_UUID) {
                val service = gatt.getService(PocketBle.SERVICE_UUID) ?: run {
                    fail("Pocket BLE service disappeared")
                    return
                }
                val response =
                    service.getCharacteristic(PocketGattProtocol.RESPONSE_UUID) ?: run {
                        fail("Pocket response characteristic missing")
                        return
                    }

                @Suppress("MissingPermission")
                if (!gatt.readCharacteristic(response)) {
                    fail("Unable to read Pocket handshake response")
                }
            } else if (characteristic.uuid == PocketGattProtocol.CONFIRM_UUID) {
                onAuthenticated(
                    Base64.encodeToString(
                        requireNotNull(fellowPublicKey),
                        Base64.NO_WRAP
                    )
                )
            } else if (characteristic.uuid == PocketGattProtocol.NETWORK_UUID) {
                onNetworkDelivered()
                close()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (status == BluetoothGatt.GATT_SUCCESS &&
                characteristic.uuid == PocketGattProtocol.RESPONSE_UUID
            ) {
                handleResponse(gatt, characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                characteristic.uuid == PocketGattProtocol.RESPONSE_UUID
            ) {
                handleResponse(gatt, value)
            }
        }
    }

    fun sendNetworkCredentials(
        ssid: String,
        password: String,
        host: String,
        port: Int
    ): Boolean {
        val currentGatt = gatt ?: return false
        val service = currentGatt.getService(PocketBle.SERVICE_UUID) ?: return false
        val characteristic =
            service.getCharacteristic(PocketGattProtocol.NETWORK_UUID) ?: return false

        val frame = runCatching {
            PocketGattProtocol.encodeNetworkCredentials(ssid, password, host, port)
        }.getOrElse {
            onFailure(it.message ?: "invalid Wi-Fi credentials")
            return false
        }

        return writeCharacteristic(currentGatt, characteristic, frame)
    }

    private fun handleResponse(gatt: BluetoothGatt, bytes: ByteArray) {
        if (responseHandled) return
        responseHandled = true

        try {
            val response = PocketGattProtocol.decodeResponse(bytes)
            val ownerKey = requireNotNull(ownerPublicKey)
            val currentNonce = requireNotNull(nonce)

            val transcriptBytes = PocketGattProtocol.signedTranscript(
                ownerKey,
                currentNonce,
                response.fellowPublicKey
            )

            require(
                PocketIdentity.verify(
                    response.fellowPublicKey,
                    transcriptBytes,
                    response.signature
                )
            ) { "fellow signature verification failed" }

            val newPublicKeyBase64 =
                Base64.encodeToString(response.fellowPublicKey, Base64.NO_WRAP)
            val existing = registry.get(associationId)?.publicKeyBase64
            require(existing == null || existing == newPublicKeyBase64) {
                "fellow public identity changed"
            }

            fellowPublicKey = response.fellowPublicKey
            transcript = transcriptBytes

            registry.setPublicKey(
                associationId,
                newPublicKeyBase64
            )

            val confirm =
                PocketIdentity.sign(appContext, transcriptBytes)

            val service = gatt.getService(PocketBle.SERVICE_UUID)
                ?: error("Pocket BLE service disappeared")
            val characteristic =
                service.getCharacteristic(PocketGattProtocol.CONFIRM_UUID)
                    ?: error("Pocket confirmation characteristic missing")

            writeCharacteristic(gatt, characteristic, confirm)
        } catch (error: Exception) {
            fail(error.message ?: "invalid Pocket handshake response")
        }
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val status = gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (status != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                fail("BLE write rejected: $status")
                return false
            }
            return true
        }

        @Suppress("DEPRECATION", "MissingPermission")
        run {
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            if (!gatt.writeCharacteristic(characteristic)) {
                fail("BLE write rejected")
                return false
            }
            return true
        }
    }

    private fun fail(message: String) {
        onFailure(message)
    }
}
