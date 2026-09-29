package com.pocket.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.SecretKey

internal object PocketDataKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pocket-data-key-v1"

    fun get(context: Context): SecretKey {
        val store = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = javax.crypto.KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )

        return generator.generateKey()
    }
}
