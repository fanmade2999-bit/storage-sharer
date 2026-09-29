package com.pocket.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

internal data class FellowSharer(
    val associationId: Int,
    val label: String,
    val macAddress: String?,
    val registeredAt: Long,
    val nearby: Boolean,
    val lastSeenAt: Long?
)

internal class FellowSharerRegistry(context: Context) {
    companion object {
        private const val PREFS = "fellow_sharers"
        private const val KEY_IDS = "association_ids"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun register(
        associationId: Int,
        label: String,
        macAddress: String?
    ): FellowSharer {
        val record = FellowSharer(
            associationId = associationId,
            label = label.ifBlank { "Fellow $associationId" },
            macAddress = macAddress,
            registeredAt = System.currentTimeMillis(),
            nearby = false,
            lastSeenAt = null
        )
        save(record)

        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        ids.add(associationId.toString())
        prefs.edit().putStringSet(KEY_IDS, ids).apply()

        return record
    }

    @Synchronized
    fun markNearby(associationId: Int, nearby: Boolean) {
        val current = get(associationId) ?: return
        save(
            current.copy(
                nearby = nearby,
                lastSeenAt = if (nearby) System.currentTimeMillis() else current.lastSeenAt
            )
        )
    }

    @Synchronized
    fun get(associationId: Int): FellowSharer? =
        prefs.getString(keyFor(associationId), null)?.let(::decode)

    @Synchronized
    fun list(): List<FellowSharer> =
        prefs.getStringSet(KEY_IDS, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .mapNotNull(::get)
            .sortedBy { it.label.lowercase() }

    @Synchronized
    fun remove(associationId: Int) {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        ids.remove(associationId.toString())
        prefs.edit()
            .putStringSet(KEY_IDS, ids)
            .remove(keyFor(associationId))
            .apply()
    }

    private fun save(record: FellowSharer) {
        prefs.edit().putString(keyFor(record.associationId), encode(record)).apply()
    }

    private fun keyFor(id: Int) = "fellow_$id"

    private fun encode(record: FellowSharer): String =
        JSONObject()
            .put("association_id", record.associationId)
            .put("label", record.label)
            .put("mac_address", record.macAddress)
            .put("registered_at", record.registeredAt)
            .put("nearby", record.nearby)
            .put("last_seen_at", record.lastSeenAt)
            .toString()

    private fun decode(raw: String): FellowSharer? =
        runCatching {
            val json = JSONObject(raw)
            FellowSharer(
                associationId = json.getInt("association_id"),
                label = json.getString("label"),
                macAddress = if (json.isNull("mac_address")) null else json.getString("mac_address"),
                registeredAt = json.getLong("registered_at"),
                nearby = json.getBoolean("nearby"),
                lastSeenAt = if (json.isNull("last_seen_at")) null else json.getLong("last_seen_at")
            )
        }.getOrNull()
}
