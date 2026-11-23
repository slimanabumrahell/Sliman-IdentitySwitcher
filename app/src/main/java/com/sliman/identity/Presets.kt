package com.sliman.identity

/** Sample device presets. Every value stays editable in the UI. */
data class Preset(
    val name: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val fingerprint: String,
) {
    fun applyTo(p: Profile) = p.copy(
        manufacturer = manufacturer, brand = brand, model = model, device = device,
        product = product, board = board, hardware = hardware, fingerprint = fingerprint,
    )
}

object Presets {
    val all = listOf(
        Preset("Pixel 8", "Google", "google", "Pixel 8", "shiba", "shiba", "shiba", "shiba",
            "google/shiba/shiba:14/AP2A.240805.005/12025142:user/release-keys"),
        Preset("Pixel 7 Pro", "Google", "google", "Pixel 7 Pro", "cheetah", "cheetah", "cheetah", "cheetah",
            "google/cheetah/cheetah:14/AP2A.240805.005/12025142:user/release-keys"),
        Preset("Galaxy S24", "samsung", "samsung", "SM-S921B", "e1s", "e1sxx", "s5e9945", "s5e9945",
            "samsung/e1sxx/e1s:14/UP1A.231005.007/S921BXXU1AXBD:user/release-keys"),
        Preset("OnePlus 12", "OnePlus", "OnePlus", "CPH2581", "OP5D0DL1", "CPH2581", "pineapple", "qcom",
            "OnePlus/CPH2581/OP5D0DL1:14/UKQ1.230924.001/T.R4T3.1234:user/release-keys"),
        Preset("Xiaomi 14", "Xiaomi", "Xiaomi", "23127PN0CG", "houji", "houji_global", "kalama", "qcom",
            "Xiaomi/houji_global/houji:14/UKQ1.230804.001/V816.0.5.0.UNCMIXM:user/release-keys"),
    )
}
