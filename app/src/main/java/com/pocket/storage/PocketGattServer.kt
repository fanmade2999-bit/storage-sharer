package com.pocket.storage

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

internal class PocketGattServer(
    context: Context,
    private val onAuthenticated: (BluetoothDevice, ByteArray) -> Unit = { _, _ -> }
) {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)

    private var server: BluetoothGattServer? = null
    private val sessions = ConcurrentHashMap<String, HandshakeSession>()

    private data class HandshakeSession(
        val ownerPublicKey: ByteArray,
        val nonce: ByteArray,
        val response: ByteArray,
        var authenticated: Boolean = false
    )

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val manager = bluetoothManager ?: return false
        server = manager.openGattServer(appContext, callback) ?: return false

        val service = BluetoothGattService(
            PocketBle.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val challenge = BluetoothGattCharacteristic(
            PocketGattProtocol.CHALLENGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val response = BluetoothGattCharacteristic(
            PocketGattProtocol.RESPONSE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val confirm = BluetoothGattCharacteristic(
            PocketGattProtocol.CONFIRM_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(challenge)
        service.addCharacteristic(response)
        service.addCharacteristic(confirm)

        return server?.addService(service) == true
    }

    fun stop() {
        server?.close()
        server = null
        sessions.clear()
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (newState != BluetoothGatt.STATE_CONNECTED) {
                sessions.remove(deviceKey(device))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (preparedWrite || offset != 0) {
                if (responseNeeded) {
                    server?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null
                    )
                }
                return
            }

            try {
                when (characteristic.uuid) {
                    PocketGattProtocol.CHALLENGE_UUID -> {
                        val challenge = PocketGattProtocol.decodeChallenge(value)
                        val fellowPublicKey = PocketIdentity.ensure(appContext).public.encoded
                        val transcript = PocketGattProtocol.signedTranscript(
                            challenge.ownerPublicKey,
                            challenge.nonce,
                            fellowPublicKey
                        )
                        val signature = PocketIdentity.sign(appContext, transcript)
                        val responseBytes =
                            PocketGattProtocol.encodeResponse(
                                fellowPublicKey,
                                signature
                            )

                        sessions[deviceKey(device)] = HandshakeSession(
                            ownerPublicKey = challenge.ownerPublicKey,
                            nonce = challenge.nonce,
                            response = responseBytes
                        )

                        sendResult(
                            device,
                            requestId,
                            responseNeeded,
                            BluetoothGatt.GATT_SUCCESS
                        )
                    }

                    PocketGattProtocol.CONFIRM_UUID -> {
                        val session = sessions[deviceKey(device)]
                            ?: error("no handshake in progress")

                        val transcript = PocketGattProtocol.decodeResponse(
                            session.response
                        )
                        val signed = PocketGattProtocol.signedTranscript(
                            session.ownerPublicKey,
                            session.nonce,
                            transcript.fellowPublicKey
                        )

                        require(
                            PocketIdentity.verify(
                                session.ownerPublicKey,
                                signed,
                                value
                            )
                        ) { "owner signature verification failed" }

                        session.authenticated = true
                        sessions[deviceKey(device)] = session
                        sendResult(
                            device,
                            requestId,
                            responseNeeded,
                            BluetoothGatt.GATT_SUCCESS
                        )
                        onAuthenticated(device, transcript.fellowPublicKey)
                    }

                    else -> sendResult(
                        device,
                        requestId,
                        responseNeeded,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    )
                }
            } catch (_: Exception) {
                sendResult(
                    device,
                    requestId,
                    responseNeeded,
                    BluetoothGatt.GATT_FAILURE
                )
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != PocketGattProtocol.RESPONSE_UUID) {
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null
                )
                return
            }

            val response = sessions[deviceKey(device)]?.response
            if (response == null || offset > response.size) {
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_INVALID_OFFSET,
                    offset,
                    null
                )
                return
            }

            val end = minOf(response.size, offset + maxPayload())
            server?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                response.copyOfRange(offset, end)
            )
        }
    }

    private fun maxPayload(): Int = 180

    private fun sendResult(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        status: Int
    ) {
        if (responseNeeded) {
            server?.sendResponse(device, requestId, status, 0, null)
        }
    }

    private fun deviceKey(device: BluetoothDevice): String =
        if (Build.VERSION.SDK_INT >= 31 &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            device.toString()
        } else {
            @Suppress("MissingPermission")
            device.address
        }
}
