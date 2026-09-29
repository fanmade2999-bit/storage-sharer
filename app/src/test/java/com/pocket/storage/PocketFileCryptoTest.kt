package com.pocket.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PocketFileCryptoTest {

    private fun store() = PocketFileCrypto(
        SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    )

    @Test
    fun roundTripLargePayload() {
        val root = Files.createTempDirectory("pocket-test").toFile()
        val file = root.resolve("large.bin")
        val source = ByteArray(2_500_000) { (it * 31).toByte() }

        val crypto = store()
        crypto.writeAtomic(file, ByteArrayInputStream(source))

        val restored = ByteArrayOutputStream()
        crypto.readTo(file, restored)

        assertArrayEquals(source, restored.toByteArray())
        assertEquals(source.size.toLong(), crypto.plaintextSize(file))
    }

    @Test
    fun appendPreservesOriginalAndAddsNewBytes() {
        val root = Files.createTempDirectory("pocket-test").toFile()
        val file = root.resolve("append.bin")
        val first = "first".encodeToByteArray()
        val second = "second".encodeToByteArray()

        val crypto = store()
        crypto.writeAtomic(file, ByteArrayInputStream(first))
        crypto.appendAtomic(file, ByteArrayInputStream(second))

        val restored = ByteArrayOutputStream()
        crypto.readTo(file, restored)

        assertArrayEquals(first + second, restored.toByteArray())
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val root = Files.createTempDirectory("pocket-test").toFile()
        val file = root.resolve("tamper.bin")
        val crypto = store()

        crypto.writeAtomic(
            file,
            ByteArrayInputStream("secret".encodeToByteArray())
        )

        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertThrows(Exception::class.java) {
            crypto.readTo(file, ByteArrayOutputStream())
        }
    }

    @Test
    fun emptyFileRoundTripWorks() {
        val root = Files.createTempDirectory("pocket-test").toFile()
        val file = root.resolve("empty.bin")
        val crypto = store()

        crypto.createEmpty(file)

        val restored = ByteArrayOutputStream()
        crypto.readTo(file, restored)

        assertEquals(0, restored.size())
        assertEquals(0L, crypto.plaintextSize(file))
    }
}
