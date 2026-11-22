package com.sliman.identity

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sliman.identity.model.DeviceProfile
import com.sliman.identity.provider.ProfileProvider
import java.util.UUID
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var etPackage: EditText
    private lateinit var spCountry: Spinner
    private lateinit var tvProfilePreview: TextView
    private var currentGeneratedProfile: DeviceProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPackage = findViewById(R.id.etTargetPackage)
        spCountry = findViewById(R.id.spCountry)
        tvProfilePreview = findViewById(R.id.tvProfilePreview)

        val btnGenerate: Button = findViewById(R.id.btnGenerate)
        val btnSave: Button = findViewById(R.id.btnSave)

        etPackage.setText("com.sliman.browser")

        val countries = listOf("US", "DE", "GB", "JP")
        spCountry.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, countries)

        btnGenerate.setOnClickListener {
            val pkg = etPackage.text.toString().trim().ifEmpty { "com.sliman.browser" }
            val country = spCountry.selectedItem?.toString() ?: "US"
            val profile = createConsistentProfile(pkg, country)
            currentGeneratedProfile = profile
            tvProfilePreview.text = formatProfileSummary(profile)
        }

        btnSave.setOnClickListener {
            val profile = currentGeneratedProfile ?: run {
                val pkg = etPackage.text.toString().trim().ifEmpty { "com.sliman.browser" }
                val country = spCountry.selectedItem?.toString() ?: "US"
                createConsistentProfile(pkg, country).also { currentGeneratedProfile = it }
            }

            val updatedProfile = profile.copy(
                targetPackage = etPackage.text.toString().trim().ifEmpty { "com.sliman.browser" }
            )
            currentGeneratedProfile = updatedProfile
            ProfileProvider.saveProfile(this, updatedProfile, setActive = true)
            tvProfilePreview.text = formatProfileSummary(updatedProfile)
            Toast.makeText(this, "تم حفظ وتفعيل البروفايل للتطبيق: ${updatedProfile.targetPackage}", Toast.LENGTH_SHORT).show()
        }

        // توليد بروفايل أولي عند فتح التطبيق
        btnGenerate.performClick()
    }

    private fun createConsistentProfile(targetPackage: String, countryIso: String): DeviceProfile {
        val presets = listOf(
            Triple("Google", "Pixel 7", "google/panther/panther:14/UQ1A.240205.004/11269751:user/release-keys"),
            Triple("Samsung", "SM-S911B", "samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXU3CXB6:user/release-keys"),
            Triple("OnePlus", "CPH2449", "OnePlus/CPH2449/OP5913L1:14/UKQ1.230924.001/T.1422284_2466:user/release-keys")
        )
        val chosen = presets[Random.nextInt(presets.size)]

        val (tz, lang, locCountry, mccMnc, opName) = when (countryIso.uppercase()) {
            "DE" -> listOf("Europe/Berlin", "de", "DE", "26201", "Telekom.de")
            "GB" -> listOf("Europe/London", "en", "GB", "23415", "Vodafone UK")
            "JP" -> listOf("Asia/Tokyo", "ja", "JP", "44010", "NTT DOCOMO")
            else -> listOf("America/New_York", "en", "US", "310260", "T-Mobile")
        }

        val androidId = (1..16).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
        val imei = "35" + (1..13).map { Random.nextInt(10) }.joinToString("")

        return DeviceProfile(
            id = "prof_${UUID.randomUUID().toString().take(8)}",
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

    private fun formatProfileSummary(p: DeviceProfile): String = """
        Profile: ${p.profileName} (${p.id})
        Target: ${p.targetPackage}
        Device: ${p.manufacturer} ${p.model} (${p.device})
        Android ID: ${p.androidId}
        IMEI: ${p.imei}
        Operator: ${p.networkOperatorName} [${p.networkOperator}] (${p.simCountryIso})
        Locale / TZ: ${p.localeLanguage}_${p.localeCountry} | ${p.timeZoneId}
    """.trimIndent()
}
