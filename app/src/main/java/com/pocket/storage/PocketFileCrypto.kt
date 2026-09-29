package com.pocket.storage

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class PocketFileCrypto(
    private val key: SecretKey
) {
    companion object {
        private val MAGIC = byteArrayOf(0x50, 0x4b, 0x46, 0x32)
        private const val VERSION: Byte = 1
        private const val HEADER_BYTES = 9
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val CHUNK_BYTES = 1024 * 1024
        private const val LENGTH_BYTES = 4
    }

    private val locks = ConcurrentHashMap<String, Any>()

    fun createEmpty(target: File) {
        writeAtomic(target, java.io.ByteArrayInputStream(ByteArray(0)))
    }

    fun writeAtomic(target: File, input: InputStream) {
        withLock(target) {
            writeAtomicLocked(target, input)
        }
    }

    fun appendAtomic(target: File, input: InputStream) {
        withLock(target) {
            if (!target.exists()) {
                writeAtomicLocked(target, input)
                return@withLock
            }

            target.parentFile?.mkdirs()
            val temp = tempFile(target, "enc")

            try {
                var nextIndex = 0L

                FileOutputStream(temp).use { output ->
                    writeHeader(output)

                    if (isEncrypted(target)) {
                        FileInputStream(target).use { existing ->
                            nextIndex = reencryptChunks(existing, output)
                        }
                    } else {
                        FileInputStream(target).use { existing ->
                            nextIndex = encryptChunks(existing, output)
                        }
                    }

                    encryptChunks(input, output, nextIndex)
                    output.flush()
                    output.fd.sync()
                }

                atomicReplace(temp, target)
            } finally {
                temp.delete()
            }
        }
    }

    fun readTo(source: File, output: OutputStream) {
        FileInputStream(source).use { input ->
            if (isEncrypted(source)) {
                decryptChunks(input, output)
            } else {
                // Compatibility path for files created before encryption was enabled.
                input.copyTo(output)
            }
        }
    }

    fun plaintextSize(source: File): Long {
        if (!isEncrypted(source)) return source.length()

        var total = 0L
        FileInputStream(source).use { input ->
            readHeader(input)

            while (true) {
                val lengthBytes = input.readFullyOrNull(LENGTH_BYTES) ?: break
                val length = ByteBuffer.wrap(lengthBytes)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int

                require(length in 0..CHUNK_BYTES) {
                    "invalid Pocket encrypted chunk length"
                }

                input.skipFully(NONCE_BYTES.toLong())
                input.skipFully((length + TAG_BYTES).toLong())
                total += length
            }
        }
        return total
    }

    private fun writeAtomicLocked(target: File, input: InputStream) {
        target.parentFile?.mkdirs()
        val temp = tempFile(target, "enc")

        try {
            FileOutputStream(temp).use { output ->
                writeHeader(output)
                encryptChunks(input, output)
                output.flush()
                output.fd.sync()
            }
            atomicReplace(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun tempFile(target: File, kind: String): File =
        File(
            target.parentFile,
            ".pocket-" + target.name + "-" + kind + "-" + System.nanoTime() + ".tmp"
        )

    private fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        FileInputStream(file).use { input ->
            val header = input.readFully(MAGIC.size)
            return header.contentEquals(MAGIC)
        }
    }

    private fun encryptChunks(
        input: InputStream,
        output: OutputStream,
        startingIndex: Long = 0L
    ): Long {
        val buffer = ByteArray(CHUNK_BYTES)
        var index = startingIndex

        while (true) {
            val count = readChunk(input, buffer)
            if (count <= 0) break

            val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            val ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce, index)
                .doFinal(buffer, 0, count)

            writeInt(output, count)
            output.write(nonce)
            output.write(ciphertext)

            index++
            if (count < CHUNK_BYTES) break
        }

        return index
    }

    private fun reencryptChunks(input: InputStream, output: OutputStream): Long {
        readHeader(input)
        var index = 0L

        while (true) {
            val lengthBytes = input.readFullyOrNull(LENGTH_BYTES) ?: break
            val length = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .int

            require(length in 1..CHUNK_BYTES) {
                "invalid Pocket encrypted chunk length"
            }

            val oldNonce = input.readFully(NONCE_BYTES)
            val ciphertext = input.readFully(length + TAG_BYTES)
            val plaintext = cipher(Cipher.DECRYPT_MODE, oldNonce, index)
                .doFinal(ciphertext)

            val newNonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            val reencrypted = cipher(Cipher.ENCRYPT_MODE, newNonce, index)
                .doFinal(plaintext)

            writeInt(output, length)
            output.write(newNonce)
            output.write(reencrypted)

            index++
        }

        return index
    }

    private fun decryptChunks(input: InputStream, output: OutputStream) {
        readHeader(input)
        var index = 0L

        while (true) {
            val lengthBytes = input.readFullyOrNull(LENGTH_BYTES) ?: break
            val length = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .int

            require(length in 0..CHUNK_BYTES) {
                "invalid Pocket encrypted chunk length"
            }

            val nonce = input.readFully(NONCE_BYTES)
            val ciphertext = input.readFully(length + TAG_BYTES)
            val plaintext = cipher(Cipher.DECRYPT_MODE, nonce, index)
                .doFinal(ciphertext)

            require(plaintext.size == length) {
                "invalid Pocket encrypted chunk"
            }

            output.write(plaintext)
            index++
        }
    }

    private fun cipher(mode: Int, nonce: ByteArray, index: Long): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, nonce))

        val aad = ByteBuffer.allocate(4 + 1 + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION)
            .putLong(index)
            .array()
        cipher.updateAAD(aad)
        return cipher
    }

    private fun writeHeader(output: OutputStream) {
        output.write(MAGIC)
        output.write(VERSION.toInt())
        writeInt(output, CHUNK_BYTES)
    }

    private fun readHeader(input: InputStream) {
        val header = input.readFully(HEADER_BYTES)
        require(header.copyOfRange(0, 4).contentEquals(MAGIC)) {
            "not a Pocket encrypted file"
        }
        require(header[4] == VERSION) {
            "unsupported Pocket encrypted file version"
        }
        val chunkSize = ByteBuffer.wrap(header, 5, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        require(chunkSize == CHUNK_BYTES) {
            "unsupported Pocket encrypted chunk size"
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            if (n == 0) continue
            offset += n
        }
        return offset
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write(
            ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(value)
                .array()
        )
    }

    private fun atomicReplace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private inline fun <T> withLock(target: File, block: () -> T): T {
        val lock = locks.computeIfAbsent(target.canonicalPath) { Any() }
        return synchronized(lock) { block() }
    }
}

private fun InputStream.readFully(length: Int): ByteArray {
    val data = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val n = read(data, offset, length - offset)
        if (n < 0) throw EOFException("unexpected end of Pocket file")
        offset += n
    }
    return data
}

private fun InputStream.readFullyOrNull(length: Int): ByteArray? {
    val first = read()
    if (first < 0) return null

    val data = ByteArray(length)
    data[0] = first.toByte()

    var offset = 1
    while (offset < length) {
        val n = read(data, offset, length - offset)
        if (n < 0) throw EOFException("unexpected end of Pocket file")
        offset += n
    }
    return data
}

private fun InputStream.skipFully(amount: Long) {
    var remaining = amount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }

        if (read() < 0) throw EOFException("unexpected end of Pocket file")
        remaining--
    }
}
