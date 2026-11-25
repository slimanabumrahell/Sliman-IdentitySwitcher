package com.sliman.identity

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Links the app with the optional Zygisk native module ("Sliman Native Hooks").
 * Profiles are written as a plain text file into the module's folder (needs root), where the native
 * module reads it while a target app is being forked.
 *
 *   [com.example.app]
 *   enabled=1
 *   model=Pixel 7 Pro
 *   ...
 */
object NativeBridge {
    const val MODULE_DIR = "/data/adb/modules/sliman_native"

    enum class Result { OK, NOT_INSTALLED, NO_ROOT }

    /** Values only the native side can generate per launch. */
    private val NATIVE_RANDOM_KEYS = setOf("bootId", "wifiMac")

    fun toConf(map: Map<String, Profile>): String = buildString {
        map.values.sortedBy { it.packageName }.forEach { p ->
            append('[').append(p.packageName).append("]\n")
            append("enabled=").append(if (p.enabled) 1 else 0).append('\n')
            p.values.forEach { (k, v) ->
                if (v == Profile.RANDOM && k !in NATIVE_RANDOM_KEYS) return@forEach
                append(k).append('=').append(v.replace('\n', ' ').replace('\r', ' ')).append('\n')
            }
            append('\n')
        }
    }

    /** Fire and forget (used on every save). */
    fun sync(ctx: Context, map: Map<String, Profile>) = sync(ctx, map, null)

    fun sync(ctx: Context, map: Map<String, Profile>, onDone: ((Result) -> Unit)?) {
        val app = ctx.applicationContext
        val conf = toConf(map)
        Thread {
            val res = runCatching {
                val tmp = File(app.cacheDir, "profiles.conf").apply { writeText(conf) }
                val cmd = "[ -d $MODULE_DIR ] || exit 3; " +
                    "cp '${tmp.absolutePath}' $MODULE_DIR/profiles.conf && chmod 644 $MODULE_DIR/profiles.conf"
                val pr = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                pr.inputStream.readBytes()
                when (pr.waitFor()) {
                    0 -> Result.OK
                    3 -> Result.NOT_INSTALLED
                    else -> Result.NO_ROOT
                }
            }.getOrDefault(Result.NO_ROOT)
            if (onDone != null) Handler(Looper.getMainLooper()).post { onDone(res) }
        }.start()
    }
}
