package com.sliman.identity

import org.json.JSONObject

/**
 * Identity values applied to one target app.
 *
 * [values] maps a field key (see [Fields]) to:
 *  - missing / blank  -> "Real": the value is left untouched
 *  - [RANDOM]         -> a fresh random value is generated every time the target app starts
 *  - anything else    -> static custom value
 */
data class Profile(
    val packageName: String,
    val enabled: Boolean = true,
    val name: String = "",
    val values: Map<String, String> = emptyMap(),
) {
    operator fun get(key: String): String = values[key].orEmpty()

    fun set(key: String, value: String): Profile =
        copy(values = if (value.isBlank()) values - key else values + (key to value))

    fun setAll(map: Map<String, String>): Profile = map.entries.fold(this) { acc, (k, v) -> acc.set(k, v) }

    fun without(keys: Collection<String>): Profile = copy(values = values - keys.toSet())

    /** Short text for the app list ("Google Pixel 7 Pro"). */
    val deviceLabel: String
        get() = listOf(this["manufacturer"], this["model"]).filter { it.isNotBlank() }.joinToString(" ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        if (name.isNotBlank()) put("name", name)
        values.forEach { (k, v) -> put(k, v) }
    }

    companion object {
        const val RANDOM = "@random"
        private val RESERVED = setOf("enabled", "name", "packageName")

        /** Reads both the new and the old (v1) flat format. */
        fun fromJson(pkg: String, o: JSONObject): Profile {
            val map = LinkedHashMap<String, String>()
            o.keys().forEach { k ->
                if (k !in RESERVED) {
                    val v = o.optString(k)
                    if (v.isNotBlank()) map[k] = v
                }
            }
            return Profile(pkg, o.optBoolean("enabled", true), o.optString("name"), map)
        }
    }
}
