package com.sliman.identity

import java.security.SecureRandom

/** Random identifier generators (format-valid, e.g. Luhn-correct IMEI). */
object Gen {
    private val rnd = SecureRandom()

    private fun digits(n: Int) = buildString { repeat(n) { append(rnd.nextInt(10)) } }

    fun androidId(): String = buildString { repeat(16) { append("0123456789abcdef"[rnd.nextInt(16)]) } }

    fun imei(): String {
        val payload = "35" + digits(12) // 14 digits
        var sum = 0
        payload.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 0) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return payload + ((10 - sum % 10) % 10)
    }

    fun imsi(): String = "310260" + digits(9)

    fun simSerial(): String = "89012" + digits(15)

    fun serial(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"
        return buildString { repeat(11) { append(chars[rnd.nextInt(chars.length)]) } }
    }

    fun mac(): String {
        val b = ByteArray(6).also { rnd.nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xFE) or 0x02).toByte() // unicast, locally administered
        return b.joinToString(":") { "%02x".format(it) }
    }
}

fun Profile.withRandomIds() = copy(
    androidId = Gen.androidId(),
    imei = Gen.imei(),
    serial = Gen.serial(),
    imsi = Gen.imsi(),
    simSerial = Gen.simSerial(),
    wifiMac = Gen.mac(),
)
