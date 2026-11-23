package com.sliman.identity

/**
 * A device model with all of its fixed (non-random) properties.
 * Applying one clears the previous preset's fields first, so nothing from the old model survives.
 * [os] (Android release / SDK / patch) is applied only when the user opts in.
 */
data class Preset(
    val name: String,
    val group: String,
    val values: Map<String, String>,
    val os: Map<String, String>,
) {
    fun applyTo(p: Profile, includeOs: Boolean = false): Profile {
        var q = p.without(Fields.presetKeys + if (includeOs) Fields.osKeys else emptySet())
        q = q.setAll(values)
        if (includeOs) q = q.setAll(os)
        return q
    }

    val model: String get() = values["model"].orEmpty()
}

object Presets {

    /**
     * Builds a preset from a fingerprint. Build ID / incremental / display / type / tags are derived from it so
     * they can never disagree with each other. Samsung: display = id.incremental, bootloader = radio = incremental
     * (same convention as a real Galaxy, e.g. A217FXXSCDXE2).
     */
    private fun dev(
        name: String, group: String, mf: String, brand: String, model: String,
        device: String, product: String, board: String, hw: String, fp: String,
        socMf: String, socModel: String, patch: String, samsung: Boolean = false,
    ): Preset {
        val parts = fp.split(":")
        val ver = parts[1].split("/")           // release / buildId / incremental
        val tail = parts[2].split("/")          // type / tags
        val release = ver[0]; val buildId = ver[1]; val inc = ver[2]
        val sdk = when (release) { "15" -> "35"; "14" -> "34"; "13" -> "33"; else -> "34" }
        val m = linkedMapOf(
            "manufacturer" to mf, "brand" to brand, "model" to model, "device" to device,
            "product" to product, "board" to board, "hardware" to hw, "fingerprint" to fp,
            "buildId" to buildId, "buildIncremental" to inc, "buildType" to tail[0], "buildTags" to tail[1],
            "buildDisplay" to if (samsung) "$buildId.$inc" else buildId,
            "socManufacturer" to socMf, "socModel" to socModel, "widevineLevel" to "L1",
        )
        if (samsung) { m["bootloader"] = inc; m["radio"] = inc }
        return Preset(name, group, m, mapOf("androidRelease" to release, "sdkInt" to sdk, "securityPatch" to patch))
    }

    private const val PIXEL_BUILD = "AP2A.240805.005/12025142"

    private fun pixel(name: String, code: String, soc: String) = dev(
        name, "Google", "Google", "google", name, code, code, code, code,
        "google/$code/$code:14/$PIXEL_BUILD:user/release-keys", "Google", soc, "2024-08-05",
    )

    private fun galaxy(
        name: String, model: String, code: String, product: String, board: String, hw: String,
        socMf: String, socModel: String, inc: String,
    ) = dev(
        name, "Samsung", "samsung", "samsung", model, code, product, board, hw,
        "samsung/$product/$code:14/UP1A.231005.007/$inc:user/release-keys", socMf, socModel, "2024-06-01", samsung = true,
    )

    val all: List<Preset> = listOf(
        pixel("Pixel 6", "oriole", "Tensor"),
        pixel("Pixel 6 Pro", "raven", "Tensor"),
        pixel("Pixel 7", "panther", "Tensor G2"),
        pixel("Pixel 7 Pro", "cheetah", "Tensor G2"),
        pixel("Pixel 7a", "lynx", "Tensor G2"),
        pixel("Pixel 8", "shiba", "Tensor G3"),
        pixel("Pixel 8 Pro", "husky", "Tensor G3"),
        pixel("Pixel 8a", "akita", "Tensor G3"),

        galaxy("Galaxy S24", "SM-S921B", "e1s", "e1sxx", "s5e9945", "s5e9945", "Samsung", "S5E9945", "S921BXXU1AXBD"),
        galaxy("Galaxy S24+", "SM-S926B", "e2s", "e2sxx", "s5e9945", "s5e9945", "Samsung", "S5E9945", "S926BXXU1AXBD"),
        galaxy("Galaxy S24 Ultra", "SM-S928B", "e3s", "e3sxx", "pineapple", "qcom", "QTI", "SM8650", "S928BXXU1AXBD"),
        galaxy("Galaxy S23", "SM-S911B", "dm1q", "dm1qxxx", "kalama", "qcom", "QTI", "SM8550", "S911BXXU1AWBB"),
        galaxy("Galaxy S23 Ultra", "SM-S918B", "dm3q", "dm3qxxx", "kalama", "qcom", "QTI", "SM8550", "S918BXXU1AWBB"),
        galaxy("Galaxy Z Flip5", "SM-F731B", "b5q", "b5qxxx", "kalama", "qcom", "QTI", "SM8550", "F731BXXU1AWBB"),
        galaxy("Galaxy A54", "SM-A546B", "a54x", "a54xxx", "s5e8835", "s5e8835", "Samsung", "S5E8835", "A546BXXU5CXA1"),

        dev("OnePlus 12", "OnePlus", "OnePlus", "OnePlus", "CPH2581", "OP5D0DL1", "CPH2581", "pineapple", "qcom",
            "OnePlus/CPH2581/OP5D0DL1:14/UKQ1.230924.001/T.R4T3.1234:user/release-keys", "QTI", "SM8650", "2024-05-01"),
        dev("OnePlus 11", "OnePlus", "OnePlus", "OnePlus", "CPH2449", "OP594DL1", "CPH2449", "kalama", "qcom",
            "OnePlus/CPH2449/OP594DL1:14/UP1A.231005.007/T.R4T3.1234:user/release-keys", "QTI", "SM8550", "2024-05-01"),

        dev("Xiaomi 14", "Xiaomi", "Xiaomi", "Xiaomi", "23127PN0CG", "houji", "houji_global", "pineapple", "qcom",
            "Xiaomi/houji_global/houji:14/UKQ1.230804.001/V816.0.5.0.UNCMIXM:user/release-keys", "QTI", "SM8650", "2024-05-01"),
        dev("Xiaomi 13", "Xiaomi", "Xiaomi", "Xiaomi", "2211133G", "fuxi", "fuxi_global", "kalama", "qcom",
            "Xiaomi/fuxi_global/fuxi:14/UKQ1.230804.001/V816.0.3.0.ULCMIXM:user/release-keys", "QTI", "SM8550", "2024-05-01"),
    )

    val groups: List<String> = all.map { it.group }.distinct()

    /** Preset whose model matches the profile (used to highlight the selected chip). */
    fun matching(p: Profile): Preset? = all.firstOrNull { it.model == p["model"] && it.values["device"] == p["device"] }
}
