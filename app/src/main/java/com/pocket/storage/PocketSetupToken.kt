package com.pocket.storage

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

internal class PocketSetupToken(context: Context) {
    companion object {
        private const val PREFS = "pocket_setup_token"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES = "expires_at"
        private const val TTL_MILLIS = 60_000L
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun issue(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + TTL_MILLIS)
            .apply()
        return token
    }

    @Synchronized
    fun consume(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false

        val expected = prefs.getString(KEY_TOKEN, null)
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        val valid = expected != null &&
            expires > System.currentTimeMillis() &&
            java.security.MessageDigest.isEqual(
                expected.encodeToByteArray(),
                candidate.encodeToByteArray()
            )

        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
        return valid
    }
}
