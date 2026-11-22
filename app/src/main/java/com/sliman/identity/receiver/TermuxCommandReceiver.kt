package com.sliman.identity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sliman.identity.model.DeviceProfile
import com.sliman.identity.provider.ProfileProvider
import java.util.UUID
import kotlin.random.Random

class TermuxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pkg = intent.getStringExtra("package") ?: "com.sliman.browser"

        when (action) {
            "com.sliman.identity.ACTION_SET_PROFILE" -> {
                val profileId = intent.getStringExtra("profile_id") ?: return
                val sp = context.getSharedPreferences("sliman_identity_profiles", Context.MODE_PRIVATE)
                if (sp.contains("profile_data_$profileId")) {
                    sp.edit().putString("active_profile_$pkg", profileId).apply()
                }
            }

            "com.sliman.identity.ACTION_RANDOMIZE" -> {
                val country = (intent.getStringExtra("country") ?: "US").uppercase()
                val generated = generatePresetProfile(pkg, country)
                ProfileProvider.saveProfile(context, generated, setActive = true)
            }
        }
    }

    private fun generatePresetProfile(targetPackage: String, countryIso: String): DeviceProfile {
        val presets = listOf(
            Triple("Google", "Pixel 7", "google/panther/panther:14/UQ1A.240205.004/11269751:user/release-keys"),
            Triple("Samsung", "SM-S911B", "samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXU3CXB6:user/release-keys"),
            Triple("OnePlus", "CPH2449", "OnePlus/CPH2449/OP5913L1:14/UKQ1.230924.001/T.1422284_2466:user/release-keys")
        )
        val chosen = presets[Random.nextInt(presets.size)]

        val (tz, lang, locCountry, mccMnc, opName) = when (countryIso) {
            "DE" -> listOf("Europe/Berlin", "de", "DE", "26201", "Telekom.de")
            "GB" -> listOf("Europe/London", "en", "GB", "23415", "Vodafone UK")
            "JP" -> listOf("Asia/Tokyo", "ja", "JP", "44010", "NTT DOCOMO")
            else -> listOf("America/New_York", "en", "US", "310260", "T-Mobile")
        }

        val androidId = (1..16).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
        val imei = "35" + (1..13).map { Random.nextInt(10) }.joinToString("")

        return DeviceProfile(
            id = "auto_${UUID.randomUUID().toString().take(8)}",
            profileName = "${chosen.second} ($locCountry)",
            targetPackage = targetPackage,
            manufacturer = chosen.first,
            brand = chosen.first.lowercase(),
            model = chosen.second,
            device = chosen.second.lowercase().replace(" ", "_"),
            hardware = "qcom",
            fingerprint = chosen.third,
            androidId = androidId,
            imei = imei,
            simCountryIso = locCountry.lowercase(),
            networkOperator = mccMnc,
            networkOperatorName = opName,
            localeLanguage = lang,
            localeCountry = locCountry,
            timeZoneId = tz,
            gpuRenderer = "Adreno (TM) 740",
            gpuVendor = "Qualcomm"
        )
    }
}
