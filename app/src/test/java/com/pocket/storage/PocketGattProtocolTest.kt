package com.pocket.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketGattProtocolTest {

    @Test
    fun challengeRoundTripPreservesFields() {
        val publicKey = ByteArray(91) { it.toByte() }
        val nonce = ByteArray(32) { (it * 3).toByte() }

        val encoded = PocketGattProtocol.encodeChallenge(publicKey, nonce)
        val decoded = PocketGattProtocol.decodeChallenge(encoded)

        assertArrayEquals(publicKey, decoded.ownerPublicKey)
        assertArrayEquals(nonce, decoded.nonce)
    }

    @Test
    fun responseRoundTripPreservesFields() {
        val publicKey = ByteArray(91) { (it * 2).toByte() }
        val signature = ByteArray(72) { (it * 5).toByte() }

        val encoded = PocketGattProtocol.encodeResponse(publicKey, signature)
        val decoded = PocketGattProtocol.decodeResponse(encoded)

        assertArrayEquals(publicKey, decoded.fellowPublicKey)
        assertArrayEquals(signature, decoded.signature)
    }

    @Test
    fun networkCredentialsRoundTripPreservesUnicode() {
        val ssid = "Pocket-共享"
        val password = "pocket123456"

        val encoded = PocketGattProtocol.encodeNetworkCredentials(ssid, password)
        val decoded = PocketGattProtocol.decodeNetworkCredentials(encoded)

        assertEquals(ssid, decoded.ssid)
        assertEquals(password, decoded.password)
    }

    @Test
    fun transcriptChangesWhenFellowIdentityChanges() {
        val owner = ByteArray(16) { 1 }
        val nonce = ByteArray(32) { 2 }
        val fellowA = ByteArray(16) { 3 }
        val fellowB = ByteArray(16) { 4 }

        val a = PocketGattProtocol.signedTranscript(owner, nonce, fellowA)
        val b = PocketGattProtocol.signedTranscript(owner, nonce, fellowB)

        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun networkCredentialsRoundTripPreservesEndpoint() {
        val encoded = PocketGattProtocol.encodeNetworkCredentials(
            ssid = "Pocket-ABC",
            password = "correct-horse-123",
            host = "192.168.43.1",
            port = 8787
        )

        val decoded = PocketGattProtocol.decodeNetworkCredentials(encoded)

        assertEquals("Pocket-ABC", decoded.ssid)
        assertEquals("correct-horse-123", decoded.password)
        assertEquals("192.168.43.1", decoded.host)
        assertEquals(8787, decoded.port)
    }

}
