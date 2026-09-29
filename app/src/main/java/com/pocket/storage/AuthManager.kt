package com.pocket.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class AuthManager(context: Context) {
    companion object {
        private const val PREFS = "pocket_auth"
        private const val KEY_SALT = "password_salt"
        private const val KEY_HASH = "password_hash"
        private const val KEY_MUST_CHANGE = "must_change"

        private const val SESSION_TTL_MILLIS = 30 * 60 * 1000L
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_MILLIS = 30 * 1000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val sessions = ConcurrentHashMap<String, Long>()

    @Volatile
    private var failedAttempts = 0

    @Volatile
    private var lockedUntil = 0L

    init {
        ensureInitialized()
    }

    private fun ensureInitialized() {
        if (prefs.contains(KEY_HASH)) return

        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash("pocket".toCharArray(), salt)

        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash))
            .putBoolean(KEY_MUST_CHANGE, true)
            .apply()
    }

    @Synchronized
    fun connect(password: String): Bundle {
        require(password.isNotEmpty()) { "password is required" }

        val now = System.currentTimeMillis()
        require(now >= lockedUntil) {
            "authentication temporarily locked; retry later"
        }

        val salt = decode(requireNotNull(prefs.getString(KEY_SALT, null)))
        val expected = decode(requireNotNull(prefs.getString(KEY_HASH, null)))
        val actual = PasswordHasher.hash(password.toCharArray(), salt)

        if (!PasswordHasher.constantTimeEquals(actual, expected)) {
            failedAttempts += 1
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                failedAttempts = 0
                lockedUntil = now + LOCKOUT_MILLIS
            }
            error("authentication failed")
        }

        failedAttempts = 0

        val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        sessions[token] = System.currentTimeMillis() + SESSION_TTL_MILLIS

        return Bundle().apply {
            putString("session", token)
            putBoolean("must_change_password", mustChangePassword)
            putInt(
                "expires_in_seconds",
                TimeUnit.MILLISECONDS.toSeconds(SESSION_TTL_MILLIS).toInt()
            )
        }
    }

    fun requireSession(token: String?) {
        require(!token.isNullOrBlank()) { "session is required" }

        val expiresAt = sessions[token] ?: error("invalid or expired session")
        if (expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token)
            error("invalid or expired session")
        }

        sessions[token] = System.currentTimeMillis() + SESSION_TTL_MILLIS
    }

    @Synchronized
    fun changePassword(session: String, newPassword: String) {
        requireSession(session)
        require(newPassword.length >= 8) { "password must be at least 8 characters" }

        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash(newPassword.toCharArray(), salt)

        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash))
            .putBoolean(KEY_MUST_CHANGE, false)
            .apply()
    }

    fun disconnect(session: String) {
        sessions.remove(session)
    }

    fun lock(session: String) {
        requireSession(session)
        sessions.clear()
    }

    val mustChangePassword: Boolean
        get() = prefs.getBoolean(KEY_MUST_CHANGE, true)

    private fun encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray =
        Base64.getDecoder().decode(value)
}
