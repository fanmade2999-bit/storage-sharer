package com.pocket.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
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
}
