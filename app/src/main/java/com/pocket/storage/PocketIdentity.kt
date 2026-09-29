package com.pocket.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64

internal object PocketIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pocket-device-identity-v1"

    fun ensure(context: Context): KeyPair {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getEntry(ALIAS, null)
        if (existing is KeyStore.PrivateKeyEntry) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey)
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE
        )

        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA512
                )
                .build()
        )

        return generator.generateKeyPair()
    }

    fun publicKeyBase64(context: Context): String =
        Base64.getEncoder().encodeToString(ensure(context).public.encoded)

    fun sign(context: Context, message: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(ensure(context).private)
        signature.update(message)
        return signature.sign()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signatureBytes: ByteArray): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(message)
            }.verify(signatureBytes)
        }.getOrDefault(false)
}
