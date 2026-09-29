package com.pocket.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID

internal object PocketGattProtocol {

    val CHALLENGE_UUID: UUID =
        UUID.fromString("6f9e7a31-2e8b-4b2e-a65d-0f8d9a4c4d02")

    val RESPONSE_UUID: UUID =
        UUID.fromString("6f9e7a31-2e8b-4b2e-a65d-0f8d9a4c4d03")

    val CONFIRM_UUID: UUID =
        UUID.fromString("6f9e7a31-2e8b-4b2e-a65d-0f8d9a4c4d04")

    val NETWORK_UUID: UUID =
        UUID.fromString("6f9e7a31-2e8b-4b2e-a65d-0f8d9a4c4d05")

    private val HANDSHAKE_MAGIC = byteArrayOf(0x50, 0x4b, 0x31)
    private val NETWORK_MAGIC = byteArrayOf(0x50, 0x4b, 0x57, 0x31)

    private const val MAX_KEY_BYTES = 512
    private const val MAX_NONCE_BYTES = 64
    private const val MAX_SIGNATURE_BYTES = 512

    data class Challenge(
        val ownerPublicKey: ByteArray,
        val nonce: ByteArray
    )

    data class Response(
        val fellowPublicKey: ByteArray,
        val signature: ByteArray
    )

    data class NetworkCredentials(
        val ssid: String,
        val password: String,
        val host: String,
        val port: Int
    )

    fun encodeChallenge(
        ownerPublicKey: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        require(ownerPublicKey.size in 1..MAX_KEY_BYTES)
        require(nonce.size in 16..MAX_NONCE_BYTES)

        return ByteBuffer
            .allocate(HANDSHAKE_MAGIC.size + 2 + ownerPublicKey.size + 2 + nonce.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HANDSHAKE_MAGIC)
            .putShort(ownerPublicKey.size.toShort())
            .put(ownerPublicKey)
            .putShort(nonce.size.toShort())
            .put(nonce)
            .array()
    }

    fun decodeChallenge(bytes: ByteArray): Challenge {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= HANDSHAKE_MAGIC.size + 4)

        val magic = ByteArray(HANDSHAKE_MAGIC.size)
        buffer.get(magic)
        require(magic.contentEquals(HANDSHAKE_MAGIC)) {
            "invalid Pocket handshake version"
        }

        val keyLength = buffer.short.toInt()
        require(keyLength in 1..MAX_KEY_BYTES)
        require(buffer.remaining() >= keyLength + 2)

        val ownerKey = ByteArray(keyLength)
        buffer.get(ownerKey)

        val nonceLength = buffer.short.toInt()
        require(nonceLength in 16..MAX_NONCE_BYTES)
        require(buffer.remaining() == nonceLength)

        val nonce = ByteArray(nonceLength)
        buffer.get(nonce)

        return Challenge(ownerKey, nonce)
    }

    fun encodeResponse(
        fellowPublicKey: ByteArray,
        signature: ByteArray
    ): ByteArray {
        require(fellowPublicKey.size in 1..MAX_KEY_BYTES)
        require(signature.size in 1..MAX_SIGNATURE_BYTES)

        return ByteBuffer
            .allocate(HANDSHAKE_MAGIC.size + 2 + fellowPublicKey.size + 2 + signature.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HANDSHAKE_MAGIC)
            .putShort(fellowPublicKey.size.toShort())
            .put(fellowPublicKey)
            .putShort(signature.size.toShort())
            .put(signature)
            .array()
    }

    fun decodeResponse(bytes: ByteArray): Response {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= HANDSHAKE_MAGIC.size + 4)

        val magic = ByteArray(HANDSHAKE_MAGIC.size)
        buffer.get(magic)
        require(magic.contentEquals(HANDSHAKE_MAGIC)) {
            "invalid Pocket handshake version"
        }

        val keyLength = buffer.short.toInt()
        require(keyLength in 1..MAX_KEY_BYTES)
        require(buffer.remaining() >= keyLength + 2)

        val fellowKey = ByteArray(keyLength)
        buffer.get(fellowKey)

        val signatureLength = buffer.short.toInt()
        require(signatureLength in 1..MAX_SIGNATURE_BYTES)
        require(buffer.remaining() == signatureLength)

        val signature = ByteArray(signatureLength)
        buffer.get(signature)

        return Response(fellowKey, signature)
    }

    fun signedTranscript(
        ownerPublicKey: ByteArray,
        nonce: ByteArray,
        fellowPublicKey: ByteArray
    ): ByteArray {
        val prefix = "POCKET-HANDSHAKE-V1".encodeToByteArray()

        return ByteBuffer
            .allocate(
                prefix.size +
                    2 + ownerPublicKey.size +
                    2 + nonce.size +
                    2 + fellowPublicKey.size
            )
            .order(ByteOrder.BIG_ENDIAN)
            .put(prefix)
            .putShort(ownerPublicKey.size.toShort())
            .put(ownerPublicKey)
            .putShort(nonce.size.toShort())
            .put(nonce)
            .putShort(fellowPublicKey.size.toShort())
            .put(fellowPublicKey)
            .array()
    }

    fun randomNonce(): ByteArray =
        ByteArray(32).also(SecureRandom()::nextBytes)

    fun encodeNetworkCredentials(
        ssid: String,
        password: String,
        host: String,
        port: Int
    ): ByteArray {
        val ssidBytes = ssid.encodeToByteArray()
        val passwordBytes = password.encodeToByteArray()
        val hostBytes = host.encodeToByteArray()

        require(ssidBytes.size in 1..32) { "SSID is invalid" }
        require(passwordBytes.size in 8..63) { "Wi-Fi password is invalid" }
        require(hostBytes.size in 1..64) { "Web host is invalid" }
        require(port in 1..65535) { "Web port is invalid" }

        return ByteBuffer
            .allocate(
                NETWORK_MAGIC.size +
                    2 + ssidBytes.size +
                    2 + passwordBytes.size +
                    2 + hostBytes.size +
                    2
            )
            .order(ByteOrder.BIG_ENDIAN)
            .put(NETWORK_MAGIC)
            .putShort(ssidBytes.size.toShort())
            .put(ssidBytes)
            .putShort(passwordBytes.size.toShort())
            .put(passwordBytes)
            .putShort(hostBytes.size.toShort())
            .put(hostBytes)
            .putShort(port.toShort())
            .array()
    }

    fun decodeNetworkCredentials(bytes: ByteArray): NetworkCredentials {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= NETWORK_MAGIC.size + 6) {
            "network frame too short"
        }

        val magic = ByteArray(NETWORK_MAGIC.size)
        buffer.get(magic)
        require(magic.contentEquals(NETWORK_MAGIC)) {
            "invalid Pocket network frame"
        }

        val ssidLength = buffer.short.toInt()
        require(ssidLength in 1..32)
        require(buffer.remaining() >= ssidLength + 2)
        val ssidBytes = ByteArray(ssidLength)
        buffer.get(ssidBytes)

        val passwordLength = buffer.short.toInt()
        require(passwordLength in 8..63)
        require(buffer.remaining() >= passwordLength + 2)
        val passwordBytes = ByteArray(passwordLength)
        buffer.get(passwordBytes)

        val hostLength = buffer.short.toInt()
        require(hostLength in 1..64)
        require(buffer.remaining() == hostLength + 2)
        val hostBytes = ByteArray(hostLength)
        buffer.get(hostBytes)

        val port = buffer.short.toInt() and 0xffff

        return NetworkCredentials(
            ssid = ssidBytes.decodeToString(),
            password = passwordBytes.decodeToString(),
            host = hostBytes.decodeToString(),
            port = port
        )
    }
}
