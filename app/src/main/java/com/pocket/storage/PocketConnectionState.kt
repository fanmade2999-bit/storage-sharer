package com.pocket.storage

import android.content.Context

internal class PocketConnectionState(context: Context) {
    companion object {
        private const val PREFS = "pocket_connection"
        private const val KEY_ASSOCIATION_ID = "active_association_id"
        private const val KEY_STARTED_AT = "started_at"
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun setActive(associationId: Int) {
        prefs.edit()
            .putInt(KEY_ASSOCIATION_ID, associationId)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    fun activeAssociationId(): Int? =
        prefs.getInt(KEY_ASSOCIATION_ID, -1).takeIf { it >= 0 }

    fun startedAt(): Long =
        prefs.getLong(KEY_STARTED_AT, 0L)
}
