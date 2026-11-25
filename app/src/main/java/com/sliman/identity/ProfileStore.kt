package com.sliman.identity

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Stores all profiles as a single JSON object (package -> profile) in a SharedPreferences file
 * that the Vector/LSPosed hook reads through XSharedPreferences.
 */
class ProfileStore(ctx: Context) {

    private val appCtx = ctx.applicationContext

    @SuppressLint("WorldReadableFiles")
    private val prefs: SharedPreferences = try {
        @Suppress("DEPRECATION")
        ctx.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE)
    } catch (_: SecurityException) {
        // Module is not active in Vector yet: fall back to private storage.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun loadAll(): Map<String, Profile> = runCatching {
        val root = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        root.keys().asSequence().associateWith { Profile.fromJson(it, root.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    fun saveAll(map: Map<String, Profile>) {
        val root = JSONObject()
        map.forEach { (pkg, p) -> root.put(pkg, p.toJson()) }
        prefs.edit().putString(KEY, root.toString()).apply()
        // Keep the optional Zygisk native module in sync (silent; no-op when it isn't installed).
        NativeBridge.sync(appCtx, map)
    }

    companion object {
        const val PREFS = "identity_profiles"
        const val KEY = "profiles"

        /** Used by the hook (runs inside the target app's process). */
        fun parseFor(json: String?, pkg: String): Profile? {
            if (json.isNullOrBlank()) return null
            val o = JSONObject(json).optJSONObject(pkg) ?: return null
            return Profile.fromJson(pkg, o)
        }
    }
}
