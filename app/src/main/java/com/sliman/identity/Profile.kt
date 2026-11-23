package com.sliman.identity

import org.json.JSONObject

/** Identity values applied to one target app. Blank fields are left untouched. */
data class Profile(
    val packageName: String,
    val enabled: Boolean = true,
    // Device
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val device: String = "",
    val product: String = "",
    val board: String = "",
    val hardware: String = "",
    val fingerprint: String = "",
    // Identity
    val androidId: String = "",
    val imei: String = "",
    val serial: String = "",
    // SIM & network
    val imsi: String = "",
    val simSerial: String = "",
    val wifiMac: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("manufacturer", manufacturer)
        put("brand", brand)
        put("model", model)
        put("device", device)
        put("product", product)
        put("board", board)
        put("hardware", hardware)
        put("fingerprint", fingerprint)
        put("androidId", androidId)
        put("imei", imei)
        put("serial", serial)
        put("imsi", imsi)
        put("simSerial", simSerial)
        put("wifiMac", wifiMac)
    }

    companion object {
        fun fromJson(pkg: String, o: JSONObject) = Profile(
            packageName = pkg,
            enabled = o.optBoolean("enabled", true),
            manufacturer = o.optString("manufacturer"),
            brand = o.optString("brand"),
            model = o.optString("model"),
            device = o.optString("device"),
            product = o.optString("product"),
            board = o.optString("board"),
            hardware = o.optString("hardware"),
            fingerprint = o.optString("fingerprint"),
            androidId = o.optString("androidId"),
            imei = o.optString("imei"),
            serial = o.optString("serial"),
            imsi = o.optString("imsi"),
            simSerial = o.optString("simSerial"),
            wifiMac = o.optString("wifiMac"),
        )
    }
}
