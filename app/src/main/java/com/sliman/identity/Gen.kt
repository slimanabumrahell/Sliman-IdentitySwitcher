package com.sliman.identity

import java.security.SecureRandom
import java.util.UUID

/** A coherent SIM/location pack: operator codes, country, phone prefix, timezone and locale. */
data class Carrier(
    val name: String,
    val mccMnc: String,
    val iso: String,
    val phoneCode: String,
    val tz: String,
    val locale: String,
)

object Carriers {
    val all = listOf(
        Carrier("T-Mobile", "310260", "us", "1", "America/New_York", "en-US"),
        Carrier("AT&T", "310410", "us", "1", "America/Chicago", "en-US"),
        Carrier("Verizon", "311480", "us", "1", "America/Los_Angeles", "en-US"),
        Carrier("Vodafone UK", "23415", "gb", "44", "Europe/London", "en-GB"),
        Carrier("O2 - UK", "23410", "gb", "44", "Europe/London", "en-GB"),
        Carrier("Orange F", "20801", "fr", "33", "Europe/Paris", "fr-FR"),
        Carrier("Telekom.de", "26201", "de", "49", "Europe/Berlin", "de-DE"),
        Carrier("Vodafone.de", "26202", "de", "49", "Europe/Berlin", "de-DE"),
        Carrier("stc", "42001", "sa", "966", "Asia/Riyadh", "ar-SA"),
        Carrier("Etisalat", "42402", "ae", "971", "Asia/Dubai", "ar-AE"),
        Carrier("Turkcell", "28601", "tr", "90", "Europe/Istanbul", "tr-TR"),
        Carrier("Airtel", "40410", "in", "91", "Asia/Kolkata", "en-IN"),
    )

    fun byCode(code: String) = all.firstOrNull { it.mccMnc == code }

    /** Sets every operator/country field of [p] from [c], and re-derives IMSI / ICCID / number / tz / locale. */
    fun applyTo(p: Profile, c: Carrier): Profile = p.setAll(
        mapOf(
            "simOperator" to c.mccMnc, "simOperatorName" to c.name, "simCountry" to c.iso,
            "netOperator" to c.mccMnc, "netOperatorName" to c.name, "netCountry" to c.iso,
            "timezone" to c.tz, "locale" to c.locale,
        ),
    ).let { q ->
        q.setAll(
            mapOf(
                "imsi" to Gen.imsi(q), "simSerial" to Gen.iccid(q), "phoneNumber" to Gen.phone(q),
            ),
        )
    }
}

/** Random identifier generators (format-valid: Luhn IMEI/ICCID, locally-administered MACs, ...). */
object Gen {
    private val rnd = SecureRandom()

    fun digits(n: Int) = buildString { repeat(n) { append(rnd.nextInt(10)) } }
    fun hex(n: Int) = buildString { repeat(n) { append("0123456789abcdef"[rnd.nextInt(16)]) } }
    private fun pick(chars: String, n: Int) = buildString { repeat(n) { append(chars[rnd.nextInt(chars.length)]) } }
    private fun between(a: Long, b: Long) = a + (rnd.nextDouble() * (b - a)).toLong()

    private fun luhnCheck(payload: String): Int {
        var sum = 0
        payload.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 0) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return (10 - sum % 10) % 10
    }

    fun androidId(): String = hex(16)
    fun uuid(): String = UUID.randomUUID().toString()

    fun firebaseInstallId(): String =
        pick("cdef", 1) + pick("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_", 21)

    fun email(): String {
        val names = listOf("alex", "sam", "mike", "lena", "omar", "nora", "john", "maya", "adam", "sara", "ryan", "lina")
        return names[rnd.nextInt(names.size)] + "." + pick("abcdefghijklmnopqrstuvwxyz", 3) + digits(3 + rnd.nextInt(3)) + "@gmail.com"
    }

    fun imei(): String {
        val payload = "35" + digits(12)
        return payload + luhnCheck(payload)
    }

    fun meid(): String = "A" + pick("0123456789ABCDEF", 13)

    fun carrier(p: Profile): Carrier =
        Carriers.byCode(p["simOperator"]) ?: Carriers.all[rnd.nextInt(Carriers.all.size)]

    fun imsi(p: Profile): String {
        val code = p["simOperator"].takeIf { it.length in 5..6 && it.all(Char::isDigit) } ?: "310260"
        return code + digits(15 - code.length)
    }

    fun iccid(p: Profile): String {
        val cc = Carriers.byCode(p["simOperator"])?.phoneCode ?: "1"
        val payload = "89" + cc + digits(19 - 2 - cc.length)
        return payload + luhnCheck(payload)
    }

    fun phone(p: Profile): String {
        val c = Carriers.byCode(p["simOperator"])
        val cc = c?.phoneCode ?: "1"
        return "+" + cc + (2 + rnd.nextInt(8)) + digits(9)
    }

    /** Brand-aware serial format (approximate, matches typical length/charset per vendor). */
    fun serial(p: Profile): String {
        val up = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"
        return when (p["manufacturer"].lowercase()) {
            "samsung" -> "R" + pick("58", 1) + pick(up, 9)
            "google" -> pick("0123456789", 1) + pick(up, 13)
            "xiaomi", "oneplus" -> pick("0123456789abcdef", 8)
            else -> pick(up, 11)
        }
    }

    fun mac(): String {
        val b = ByteArray(6).also { rnd.nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xFE) or 0x02).toByte() // unicast, locally administered
        return b.joinToString(":") { "%02x".format(it) }
    }

    /** Router BSSID: globally-unique style (not locally administered). */
    fun bssid(): String {
        val b = ByteArray(6).also { rnd.nextBytes(it) }
        b[0] = (b[0].toInt() and 0xFC).toByte()
        return b.joinToString(":") { "%02x".format(it) }
    }

    fun ssid(): String {
        val prefixes = listOf("TP-Link_", "HUAWEI-", "NETGEAR", "Orange-", "Home_", "DIRECT-", "Linksys")
        return prefixes[rnd.nextInt(prefixes.size)] + pick("0123456789ABCDEF", 4)
    }

    fun userAgent(p: Profile): String {
        val rel = p["androidRelease"].ifBlank { "14" }
        val model = p["model"].ifBlank { "Pixel 7 Pro" }
        return "Mozilla/5.0 (Linux; Android $rel; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    fun hostname(): String = "android-" + hex(16)

    fun installTime(): String = (System.currentTimeMillis() - between(7L * DAY, 400L * DAY)).toString()
    fun lastBoot(): String = (System.currentTimeMillis() - between(1L * HOUR, 96L * HOUR)).toString()

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
}

/**
 * Re-rolls every generator-backed field with a coherent carrier/location, as static values.
 * Device-model fields (and the preset-owned ones) are never touched.
 */
fun Profile.withRandomIds(): Profile {
    val c = Carriers.all.random()
    var q = Carriers.applyTo(this, c)
    Fields.all.filter { it.gen != null && it.key !in COHERENT }.forEach { d -> q = q.set(d.key, d.gen!!(q)) }
    return q
}

private val COHERENT = setOf("imsi", "simSerial", "phoneNumber", "timezone", "locale")
