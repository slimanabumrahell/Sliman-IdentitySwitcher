package com.sliman.identity

import org.json.JSONObject

/**
 * Import / export of profiles as .json files.
 *
 *   {"format":"sliman-identity","version":2,"type":"profile","package":"...","name":"...","profile":{...}}
 *   {"format":"sliman-identity","version":2,"type":"bundle","profiles":{"pkg":{...}}}
 *
 * The old (v1) store layout {"pkg":{...}} is accepted as a bundle too.
 */
object Transfer {
    private const val FORMAT = "sliman-identity"
    private const val VERSION = 2

    fun exportProfile(p: Profile): String = JSONObject().apply {
        put("format", FORMAT); put("version", VERSION); put("type", "profile")
        put("package", p.packageName)
        put("name", p.name)
        put("profile", p.toJson())
    }.toString(2)

    fun exportAll(map: Map<String, Profile>): String = JSONObject().apply {
        put("format", FORMAT); put("version", VERSION); put("type", "bundle")
        put("profiles", JSONObject().also { o -> map.forEach { (k, v) -> o.put(k, v.toJson()) } })
    }.toString(2)

    /** Single profile for the editor of [targetPkg]. A bundle is accepted if it holds [targetPkg] or just one profile. */
    fun parseForEditor(text: String, targetPkg: String): Profile? = runCatching {
        val root = JSONObject(text)
        when {
            root.optString("type") == "profile" ->
                Profile.fromJson(targetPkg, root.getJSONObject("profile")).let { it.copy(name = root.optString("name", it.name)) }
            else -> {
                val bundle = bundleOf(root)
                (bundle[targetPkg] ?: bundle.values.singleOrNull())?.copy(packageName = targetPkg)
            }
        }
    }.getOrNull()

    /** Everything importable from a file, keyed by package. Empty map when the file isn't ours. */
    fun parseAll(text: String): Map<String, Profile> = runCatching {
        val root = JSONObject(text)
        if (root.optString("type") == "profile") {
            val pkg = root.optString("package")
            if (pkg.isBlank()) emptyMap()
            else mapOf(pkg to Profile.fromJson(pkg, root.getJSONObject("profile")).copy(name = root.optString("name")))
        } else bundleOf(root)
    }.getOrDefault(emptyMap())

    private fun bundleOf(root: JSONObject): Map<String, Profile> {
        val src = if (root.has("profiles")) root.getJSONObject("profiles") else root
        val out = LinkedHashMap<String, Profile>()
        src.keys().forEach { k -> src.optJSONObject(k)?.let { out[k] = Profile.fromJson(k, it) } }
        return out
    }

    fun fileName(p: Profile): String =
        (p.name.ifBlank { p.packageName }).replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "profile" } + ".json"
}
