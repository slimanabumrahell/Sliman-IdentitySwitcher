package com.sliman.identity

import android.accounts.Account
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.pm.PackageInfo
import android.database.MatrixCursor
import android.media.MediaDrm
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.graphics.Point
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.Display
import android.webkit.WebView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone

/** Resolves a field of the profile: null = Real, RANDOM = generated once per process, else the static value. */
private class Resolver(private val p: Profile) {
    private val cache = HashMap<String, String?>()

    operator fun get(key: String): String? {
        if (cache.containsKey(key)) return cache[key]
        val raw = p[key]
        val v = when {
            raw.isBlank() -> null
            raw == Profile.RANDOM -> Fields.byKey[key]?.gen?.invoke(p)
            else -> raw
        }
        cache[key] = v
        return v
    }

    fun flag(key: String) = p[key] == "1"
}

/** Vector / LSPosed entry point: applies the saved profile inside the selected app's process. */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == BuildConfig.APPLICATION_ID || pkg == "android") return

        val profile = runCatching {
            val prefs = XSharedPreferences(BuildConfig.APPLICATION_ID, ProfileStore.PREFS)
            prefs.makeWorldReadable()
            ProfileStore.parseFor(prefs.getString(ProfileStore.KEY, null), pkg)
        }.getOrNull() ?: return
        if (!profile.enabled) return

        XposedBridge.log("Sliman-Identity: applying profile '${profile.name}' to $pkg")
        val r = Resolver(profile)
        val cl = lpparam.classLoader

        guard("build") { spoofBuild(r) }
        guard("sysprops") { spoofSystemProperties(r) }
        guard("settings") { spoofSettings(r) }
        guard("telephony") { spoofTelephony(r) }
        guard("network") { spoofNetwork(r) }
        guard("drm") { spoofDrm(r) }
        guard("google-ids") { spoofGoogleIds(r, cl) }
        guard("accounts") { spoofAccounts(r) }
        guard("providers") { spoofProviders(r) }
        guard("privacy-lists") { blockLists(r) }
        guard("time") { spoofTime(r, pkg) }
        guard("locale") { spoofLocale(r) }
        guard("kernel") { spoofKernel(r) }
        guard("hardware") { spoofHardware(r) }
        guard("display") { spoofDisplay(r) }
        guard("webview-ua") { spoofUserAgent(r) }
    }

    private inline fun guard(name: String, block: () -> Unit) {
        runCatching(block).onFailure { XposedBridge.log("Sliman-Identity: $name failed: $it") }
    }

    // ---------------------------------------------------------------- Build

    private fun spoofBuild(r: Resolver) {
        val buildFields = mapOf(
            "manufacturer" to "MANUFACTURER", "brand" to "BRAND", "model" to "MODEL", "device" to "DEVICE",
            "product" to "PRODUCT", "board" to "BOARD", "hardware" to "HARDWARE", "fingerprint" to "FINGERPRINT",
            "serial" to "SERIAL", "buildId" to "ID", "buildDisplay" to "DISPLAY", "buildType" to "TYPE",
            "buildTags" to "TAGS", "bootloader" to "BOOTLOADER", "radio" to "RADIO",
            "socManufacturer" to "SOC_MANUFACTURER", "socModel" to "SOC_MODEL",
        )
        buildFields.forEach { (key, field) ->
            val v = r[key] ?: return@forEach
            if (field.startsWith("SOC_") && REAL_SDK < 31) return@forEach
            runCatching { XposedHelpers.setStaticObjectField(Build::class.java, field, v) }
                .onFailure { XposedBridge.log("Sliman-Identity: Build.$field not settable on API $REAL_SDK") }
        }
        val versionFields = mapOf(
            "androidRelease" to "RELEASE", "securityPatch" to "SECURITY_PATCH", "buildIncremental" to "INCREMENTAL",
        )
        versionFields.forEach { (key, field) ->
            val v = r[key] ?: return@forEach
            runCatching { XposedHelpers.setStaticObjectField(Build.VERSION::class.java, field, v) }
        }
        r["sdkInt"]?.toIntOrNull()?.let {
            runCatching { XposedHelpers.setStaticIntField(Build.VERSION::class.java, "SDK_INT", it) }
        }
        r["serial"]?.let { replace(Build::class.java, "getSerial", it) }
        r["radio"]?.let { replace(Build::class.java, "getRadioVersion", it) }
    }

    /** Apps that read ro.* properties directly (SystemProperties / getprop through reflection). */
    private fun spoofSystemProperties(r: Resolver) {
        val props = HashMap<String, String>()
        fun put(key: String, vararg names: String) { r[key]?.let { v -> names.forEach { props[it] = v } } }

        put("manufacturer", "ro.product.manufacturer", "ro.product.system.manufacturer", "ro.product.vendor.manufacturer")
        put("brand", "ro.product.brand", "ro.product.system.brand", "ro.product.vendor.brand")
        put("model", "ro.product.model", "ro.product.system.model", "ro.product.vendor.model")
        put("device", "ro.product.device", "ro.product.system.device", "ro.product.vendor.device")
        put("product", "ro.product.name", "ro.product.system.name", "ro.product.vendor.name")
        put("board", "ro.product.board")
        put("hardware", "ro.hardware", "ro.boot.hardware")
        put("fingerprint", "ro.build.fingerprint", "ro.system.build.fingerprint", "ro.vendor.build.fingerprint")
        put("buildId", "ro.build.id")
        put("buildDisplay", "ro.build.display.id")
        put("buildIncremental", "ro.build.version.incremental")
        put("buildType", "ro.build.type")
        put("buildTags", "ro.build.tags")
        put("bootloader", "ro.bootloader", "ro.boot.bootloader")
        put("radio", "gsm.version.baseband")
        put("serial", "ro.serialno", "ro.boot.serialno")
        put("socManufacturer", "ro.soc.manufacturer")
        put("socModel", "ro.soc.model")
        put("androidRelease", "ro.build.version.release")
        put("securityPatch", "ro.build.version.security_patch")
        put("sdkInt", "ro.build.version.sdk")
        put("hostname", "net.hostname")
        if (props.isEmpty()) return

        val sp = findCls("android.os.SystemProperties")
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args.firstOrNull() as? String ?: return
                val v = props[key] ?: return
                param.result = if (param.method.name == "getInt" || param.method.name == "getLong") {
                    v.toLongOrNull()?.let { if (param.method.name == "getInt") it.toInt() else it } ?: return
                } else v
            }
        }
        listOf("get", "getInt", "getLong").forEach { hookAll(sp, it, hook) }
    }

    // ---------------------------------------------------------------- Settings

    private fun spoofSettings(r: Resolver) {
        val secureStr = HashMap<String, String>()
        r["androidId"]?.let { secureStr[Settings.Secure.ANDROID_ID] = it }
        r["advertisingId"]?.let { secureStr["advertising_id"] = it }
        r["btMac"]?.let { secureStr["bluetooth_address"] = it }

        val globalStr = HashMap<String, String>()
        r["hostname"]?.let { globalStr["device_name"] = it }

        val globalInt = HashMap<String, Int>()
        if (r.flag("adbFlag")) { globalInt["adb_enabled"] = 0; globalInt["adb_wifi_enabled"] = 0 }
        if (r.flag("devOptionsFlag")) globalInt["development_settings_enabled"] = 0

        hookSettings(Settings.Secure::class.java, secureStr, emptyMap())
        hookSettings(Settings.Global::class.java, globalStr, globalInt)
        // Some apps read these through Settings.Secure too.
        if (globalInt.isNotEmpty()) hookSettings(Settings.Secure::class.java, emptyMap(), globalInt)
    }

    private fun hookSettings(cls: Class<*>, strings: Map<String, String>, ints: Map<String, Int>) {
        if (strings.isNotEmpty()) {
            hookAll(cls, "getString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(1) as? String ?: return
                    strings[name]?.let { param.result = it }
                }
            })
        }
        if (ints.isNotEmpty()) {
            hookAll(cls, "getInt", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(1) as? String ?: return
                    ints[name]?.let { param.result = it }
                }
            })
        }
    }

    // ---------------------------------------------------------------- Telephony / SIM

    private fun spoofTelephony(r: Resolver) {
        val tm = TelephonyManager::class.java
        r["imei"]?.let { replace(tm, "getDeviceId", it); replace(tm, "getImei", it) }
        r["meid"]?.let { replace(tm, "getMeid", it) }
        r["imsi"]?.let { replace(tm, "getSubscriberId", it) }
        r["simSerial"]?.let { replace(tm, "getSimSerialNumber", it) }
        r["phoneNumber"]?.let { replace(tm, "getLine1Number", it) }
        r["simOperator"]?.let { replace(tm, "getSimOperator", it) }
        r["simOperatorName"]?.let { replace(tm, "getSimOperatorName", it) }
        r["simCountry"]?.let { replace(tm, "getSimCountryIso", it) }
        r["netOperator"]?.let { replace(tm, "getNetworkOperator", it) }
        r["netOperatorName"]?.let { replace(tm, "getNetworkOperatorName", it) }
        r["netCountry"]?.let { replace(tm, "getNetworkCountryIso", it) }
        if (REAL_SDK >= 28) r["simCarrierId"]?.toIntOrNull()?.let { replaceAny(tm, "getSimCarrierId", it) }

        runCatching {
            val si = XposedHelpers.findClass("android.telephony.SubscriptionInfo", null)
            r["simSerial"]?.let { replace(si, "getIccId", it) }
            r["phoneNumber"]?.let { replace(si, "getNumber", it) }
            r["simCountry"]?.let { replace(si, "getCountryIso", it) }
        }
    }

    // ---------------------------------------------------------------- Wi-Fi / Bluetooth

    private fun spoofNetwork(r: Resolver) {
        r["wifiMac"]?.let { mac ->
            replace(WifiInfo::class.java, "getMacAddress", mac)
            macBytes(mac)?.let { bytes ->
                hookAll(NetworkInterface::class.java, "getHardwareAddress", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if ((param.thisObject as NetworkInterface).name == "wlan0") param.result = bytes
                    }
                })
            }
        }
        r["bssid"]?.let { replace(WifiInfo::class.java, "getBSSID", it) }
        r["ssid"]?.let { replace(WifiInfo::class.java, "getSSID", "\"$it\"") }
        r["btMac"]?.let { replace(android.bluetooth.BluetoothAdapter::class.java, "getAddress", it) }
    }

    private fun macBytes(mac: String): ByteArray? = runCatching {
        mac.split(":").map { it.toInt(16).toByte() }.toByteArray().takeIf { it.size == 6 }
    }.getOrNull()

    // ---------------------------------------------------------------- Widevine

    private fun spoofDrm(r: Resolver) {
        val id = r["widevineId"]?.let(::hexBytes)
        val level = r["widevineLevel"]
        val sysId = r["widevineSystemId"]
        if (id == null && level == null && sysId == null) return

        if (id != null) {
            hookAll(MediaDrm::class.java, "getPropertyByteArray", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() == "deviceUniqueId") param.result = id
                }
            })
        }
        if (level != null || sysId != null) {
            hookAll(MediaDrm::class.java, "getPropertyString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    when (param.args.firstOrNull()) {
                        "securityLevel" -> level?.let { param.result = it }
                        "systemId" -> sysId?.let { param.result = it }
                    }
                }
            })
        }
    }

    private fun hexBytes(h: String): ByteArray? = runCatching {
        require(h.length % 2 == 0)
        ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    // ---------------------------------------------------------------- Google / Firebase IDs (SDK classes bundled in the app)

    private fun spoofGoogleIds(r: Resolver, cl: ClassLoader) {
        r["advertisingId"]?.let {
            replaceByName(cl, "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info", "getId", it)
        }
        r["appSetId"]?.let { replaceByName(cl, "com.google.android.gms.appset.AppSetIdInfo", "getId", it) }
        r["firebaseInstallId"]?.let {
            taskResult(cl, "com.google.firebase.installations.FirebaseInstallations", "getId", it)
        }
        r["appInstanceId"]?.let {
            taskResult(cl, "com.google.firebase.analytics.FirebaseAnalytics", "getAppInstanceId", it)
        }
    }

    private fun replaceByName(cl: ClassLoader, cls: String, method: String, value: Any) {
        runCatching {
            hookAll(XposedHelpers.findClass(cls, cl), method, XC_MethodReplacement.returnConstant(value))
        }
    }

    /** For methods returning Task<String>: swap the result for an already-completed task. */
    private fun taskResult(cl: ClassLoader, cls: String, method: String, value: String) {
        runCatching {
            val tasks = XposedHelpers.findClass("com.google.android.gms.tasks.Tasks", cl)
            hookAll(XposedHelpers.findClass(cls, cl), method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = XposedHelpers.callStaticMethod(tasks, "forResult", value)
                }
            })
        }
    }

    // ---------------------------------------------------------------- Accounts

    private fun spoofAccounts(r: Resolver) {
        val block = r.flag("blockAccounts")
        val email = r["googleEmail"]
        if (!block && email == null) return
        val fake = email?.let { Account(it, "com.google") }

        hookAll(android.accounts.AccountManager::class.java, "getAccounts", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = if (fake != null) arrayOf(fake) else emptyArray<Account>()
            }
        })
        hookAll(android.accounts.AccountManager::class.java, "getAccountsByType", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val type = param.args.firstOrNull() as? String
                when {
                    type == "com.google" && fake != null -> param.result = arrayOf(fake)
                    block -> param.result = emptyArray<Account>()
                }
            }
        })
    }

    // ---------------------------------------------------------------- Content providers (GSF ID + blocked data)

    private fun spoofProviders(r: Resolver) {
        val gsfDecimal = r["gsfId"]?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16).toString() }.getOrNull() }
        val blocked = HashSet<String>().apply {
            if (r.flag("blockContacts")) add("com.android.contacts")
            if (r.flag("blockCallLog")) add("call_log")
            if (r.flag("blockSms")) addAll(listOf("sms", "mms", "mms-sms", "telephony"))
            if (r.flag("blockCalendar")) add("com.android.calendar")
        }
        if (gsfDecimal == null && blocked.isEmpty()) return

        hookAll(ContentResolver::class.java, "query", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args.firstOrNull() as? Uri ?: return
                val auth = uri.authority ?: return
                if (auth in blocked) {
                    val cols = param.args.getOrNull(1) as? Array<*>
                    val names = cols?.filterIsInstance<String>()?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: arrayOf("_id")
                    param.result = MatrixCursor(names)
                } else if (gsfDecimal != null && auth == "com.google.android.gsf.gservices" && asksAndroidId(param.args)) {
                    param.result = MatrixCursor(arrayOf("key", "value")).apply { addRow(arrayOf("android_id", gsfDecimal)) }
                }
            }
        })
    }

    private fun asksAndroidId(args: Array<Any?>): Boolean = args.any { a ->
        (a is Array<*> && a.any { it == "android_id" }) || (a is Bundle && a.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.contains("android_id") == true)
    }

    // ---------------------------------------------------------------- Privacy lists

    private fun blockLists(r: Resolver) {
        if (r.flag("blockBtBonded")) {
            hookAll(android.bluetooth.BluetoothAdapter::class.java, "getBondedDevices",
                XC_MethodReplacement.returnConstant(HashSet<Any>()))
        }
        val wm = android.net.wifi.WifiManager::class.java
        if (r.flag("blockWifiScan")) hookAll(wm, "getScanResults", XC_MethodReplacement.returnConstant(ArrayList<Any>()))
        if (r.flag("blockWifiConfigured")) hookAll(wm, "getConfiguredNetworks", XC_MethodReplacement.returnConstant(ArrayList<Any>()))
        if (r.flag("blockCellInfo")) {
            val tm = TelephonyManager::class.java
            hookAll(tm, "getAllCellInfo", XC_MethodReplacement.returnConstant(ArrayList<Any>()))
            hookAll(tm, "getCellLocation", XC_MethodReplacement.returnConstant(null))
        }
    }

    // ---------------------------------------------------------------- Timestamps / locale / kernel

    private fun spoofTime(r: Resolver, pkg: String) {
        r["installTime"]?.toLongOrNull()?.let { t ->
            val cls = findCls("android.app.ApplicationPackageManager")
            hookAll(cls, "getPackageInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pi = param.result as? PackageInfo ?: return
                    if (pi.packageName == pkg) { pi.firstInstallTime = t; pi.lastUpdateTime = t }
                }
            })
        }
        r["lastBoot"]?.toLongOrNull()?.takeIf { it < System.currentTimeMillis() }?.let { boot ->
            hookAll(SystemClock::class.java, "elapsedRealtime", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = System.currentTimeMillis() - boot
            })
            hookAll(SystemClock::class.java, "elapsedRealtimeNanos", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = (System.currentTimeMillis() - boot) * 1_000_000L
            })
        }
    }

    private fun spoofLocale(r: Resolver) {
        r["timezone"]?.let { TimeZone.setDefault(TimeZone.getTimeZone(it)) }
        r["locale"]?.let { Locale.setDefault(Locale.forLanguageTag(it)) }
    }

    private fun spoofKernel(r: Resolver) {
        val k = r["kernel"] ?: return
        hookAll(System::class.java, "getProperty", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.firstOrNull() == "os.version") param.result = k
            }
        })
    }


    // ---------------------------------------------------------------- Hardware specs (cores / RAM / storage)

    private fun spoofHardware(r: Resolver) {
        r["cpuCores"]?.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
            hookAll(Runtime::class.java, "availableProcessors", XC_MethodReplacement.returnConstant(n))
        }

        r["ramMb"]?.toLongOrNull()?.takeIf { it > 0 }?.let { mb ->
            val total = mb * 1024L * 1024L
            hookAll(ActivityManager::class.java, "getMemoryInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val mi = param.args.firstOrNull() as? ActivityManager.MemoryInfo ?: return
                    val real = mi.totalMem
                    if (real <= 0) return
                    val ratio = mi.availMem.toDouble() / real
                    mi.threshold = (mi.threshold.toDouble() * total / real).toLong()
                    mi.totalMem = total
                    mi.availMem = (total * ratio).toLong()
                }
            })
        }

        r["storageGb"]?.toLongOrNull()?.takeIf { it > 0 }?.let { gb ->
            val target = gb * 1_000_000_000.0 * 0.93
            // Measured BEFORE the File hooks below are installed.
            val real = runCatching { File("/data").totalSpace }.getOrDefault(0L)
            if (real <= 0) return@let
            val ratio = target / real
            val scale = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = when (val v = param.result) {
                        is Long -> (v * ratio).toLong()
                        is Int -> (v * ratio).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        else -> v
                    }
                }
            }
            listOf(
                "getTotalBytes", "getFreeBytes", "getAvailableBytes",
                "getBlockCountLong", "getFreeBlocksLong", "getAvailableBlocksLong",
                "getBlockCount", "getFreeBlocks", "getAvailableBlocks",
            ).forEach { hookAll(StatFs::class.java, it, scale) }
            listOf("getTotalSpace", "getFreeSpace", "getUsableSpace").forEach {
                hookAll(File::class.java, it, scale)
            }
        }
    }

    // ---------------------------------------------------------------- Screen

    private fun spoofDisplay(r: Resolver) {
        val w = r["screenWidth"]?.toIntOrNull()?.takeIf { it > 0 }
        val h = r["screenHeight"]?.toIntOrNull()?.takeIf { it > 0 }
        val dpi = r["densityDpi"]?.toIntOrNull()?.takeIf { it > 0 }
        if (w == null && h == null && dpi == null) return
        val shortSide = if (w != null && h != null) minOf(w, h) else null
        val longSide = if (w != null && h != null) maxOf(w, h) else null

        fun fixMetrics(dm: DisplayMetrics) {
            if (shortSide != null && longSide != null) {
                val landscape = dm.widthPixels > dm.heightPixels
                dm.widthPixels = if (landscape) longSide else shortSide
                dm.heightPixels = if (landscape) shortSide else longSide
            }
            if (dpi != null) {
                dm.densityDpi = dpi
                dm.density = dpi / 160f
                dm.scaledDensity = dm.density
                dm.xdpi = dpi.toFloat()
                dm.ydpi = dpi.toFloat()
            }
        }
        fun fixPoint(pt: Point) {
            if (shortSide != null && longSide != null) {
                val landscape = pt.x > pt.y
                pt.x = if (landscape) longSide else shortSide
                pt.y = if (landscape) shortSide else longSide
            }
        }
        fun hook(method: String) {
            hookAll(Display::class.java, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    when (val a = param.args.firstOrNull()) {
                        is DisplayMetrics -> fixMetrics(a)
                        is Point -> fixPoint(a)
                    }
                }
            })
        }
        hook("getRealMetrics"); hook("getRealSize")
        // Layout-affecting variants: only on request.
        if (r.flag("screenAlsoApp")) { hook("getMetrics"); hook("getSize") }
    }

    // ---------------------------------------------------------------- User-Agent (WebView + HTTP stack)

    private val UA_RE = Regex("""\(Linux; (U; )?Android ([^;)]+); ([^;)]*?)( Build/[^;)]*)?(; wv)?\)""")

    /** Replaces the Android version / model / build id tokens of a UA, keeping the real browser engine version. */
    private fun rewriteUa(ua: String?, r: Resolver): String? {
        if (ua == null) return null
        r["userAgent"]?.let { return it }
        if (!r.flag("uaAuto")) return ua
        return UA_RE.replace(ua) { m ->
            val rel = r["androidRelease"] ?: m.groupValues[2]
            val model = r["model"] ?: m.groupValues[3]
            val build = if (m.groupValues[4].isNotEmpty()) " Build/" + (r["buildId"] ?: m.groupValues[4].removePrefix(" Build/")) else ""
            "(Linux; ${m.groupValues[1]}Android $rel; $model$build${m.groupValues[5]})"
        }
    }

    private fun spoofUserAgent(r: Resolver) {
        if (r["userAgent"] == null && !r.flag("uaAuto")) return

        // Default UA getters (static) and the Java HTTP stack's UA.
        runCatching {
            hookAll(findCls("android.webkit.WebSettings"), "getDefaultUserAgent",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { param.result = rewriteUa(param.result as? String, r) }
                })
        }
        hookAll(System::class.java, "getProperty", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.firstOrNull() == "http.agent") param.result = rewriteUa(param.result as? String, r)
            }
        })

        // Per-WebView: rewrite the effective UA at creation and before every load (covers apps that set their own).
        val apply = { wv: WebView ->
            runCatching {
                val st = wv.settings
                val cur = st.userAgentString
                val next = rewriteUa(cur, r)
                if (next != null && next != cur) st.userAgentString = next
            }
        }
        XposedBridge.hookAllConstructors(WebView::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { apply(param.thisObject as WebView) }
        })
        listOf("loadUrl", "postUrl", "loadData", "loadDataWithBaseURL", "reload").forEach { name ->
            hookAll(WebView::class.java, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { apply(param.thisObject as WebView) }
            })
        }
    }


    // ---------------------------------------------------------------- version-robust hooking

    /** Real API level, captured before any SDK_INT spoof so version checks in this module stay truthful. */
    private val REAL_SDK = Build.VERSION.SDK_INT

    private fun findCls(name: String, cl: ClassLoader? = null): Class<*>? =
        runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()

    /**
     * Hooks every overload of [name]. A missing class/method (older or newer Android) or a failing hook only skips
     * that single hook and is logged, instead of aborting a whole group.
     */
    private fun hookAll(cls: Class<*>?, name: String, cb: XC_MethodHook) {
        if (cls == null) return
        try {
            if (XposedBridge.hookAllMethods(cls, name, cb).isEmpty()) {
                XposedBridge.log("Sliman-Identity: ${cls.simpleName}.$name not present on API $REAL_SDK (skipped)")
            }
        } catch (t: Throwable) {
            XposedBridge.log("Sliman-Identity: hook ${cls.simpleName}.$name failed on API $REAL_SDK: $t")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun replace(cls: Class<*>, method: String, value: String) = replaceAny(cls, method, value)

    private fun replaceAny(cls: Class<*>, method: String, value: Any) {
        hookAll(cls, method, XC_MethodReplacement.returnConstant(value))
    }
}
