package com.sliman.identity.model

import org.json.JSONObject

data class DeviceProfile(
    val id: String,
    val profileName: String,
    val targetPackage: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val hardware: String,
    val fingerprint: String,
    val androidId: String,
    val imei: String,
    val simCountryIso: String,
    val networkOperator: String,
    val networkOperatorName: String,
    val localeLanguage: String,
    val localeCountry: String,
    val timeZoneId: String,
    val gpuRenderer: String,
    val gpuVendor: String
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("profileName", profileName)
        put("targetPackage", targetPackage)
        put("manufacturer", manufacturer)
        put("brand", brand)
        put("model", model)
        put("device", device)
        put("hardware", hardware)
        put("fingerprint", fingerprint)
        put("androidId", androidId)
        put("imei", imei)
        put("simCountryIso", simCountryIso)
        put("networkOperator", networkOperator)
        put("networkOperatorName", networkOperatorName)
        put("localeLanguage", localeLanguage)
        put("localeCountry", localeCountry)
        put("timeZoneId", timeZoneId)
        put("gpuRenderer", gpuRenderer)
        put("gpuVendor", gpuVendor)
    }.toString()

    companion object {
        fun fromJson(raw: String): DeviceProfile? = try {
            val obj = JSONObject(raw)
            DeviceProfile(
                id = obj.optString("id", "default"),
                profileName = obj.optString("profileName", "Profile 1"),
                targetPackage = obj.optString("targetPackage", ""),
                manufacturer = obj.optString("manufacturer", ""),
                brand = obj.optString("brand", ""),
                model = obj.optString("model", ""),
                device = obj.optString("device", ""),
                hardware = obj.optString("hardware", ""),
                fingerprint = obj.optString("fingerprint", ""),
                androidId = obj.optString("androidId", ""),
                imei = obj.optString("imei", ""),
                simCountryIso = obj.optString("simCountryIso", ""),
                networkOperator = obj.optString("networkOperator", ""),
                networkOperatorName = obj.optString("networkOperatorName", ""),
                localeLanguage = obj.optString("localeLanguage", ""),
                localeCountry = obj.optString("localeCountry", ""),
                timeZoneId = obj.optString("timeZoneId", ""),
                gpuRenderer = obj.optString("gpuRenderer", ""),
                gpuVendor = obj.optString("gpuVendor", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}
