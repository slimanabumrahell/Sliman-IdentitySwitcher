package com.sliman.identity

import android.accounts.Account
import android.content.ContentResolver
import android.content.pm.PackageInfo
import android.database.MatrixCursor
import android.media.MediaDrm
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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
            runCatching { XposedHelpers.setStaticObjectField(Build::class.java, field, v) }
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

        val sp = XposedHelpers.findClass("android.os.SystemProperties", null)
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args.firstOrNull() as? String ?: return
                val v = props[key] ?: return
                param.result = if (param.method.name == "getInt" || param.method.name == "getLong") {
                    v.toLongOrNull()?.let { if (param.method.name == "getInt") it.toInt() else it } ?: return
                } else v
            }
        }
        listOf("get", "getInt", "getLong").forEach { XposedBridge.hookAllMethods(sp, it, hook) }
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
            XposedBridge.hookAllMethods(cls, "getString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(1) as? String ?: return
                    strings[name]?.let { param.result = it }
                }
            })
        }
        if (ints.isNotEmpty()) {
            XposedBridge.hookAllMethods(cls, "getInt", object : XC_MethodHook() {
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
        r["simCarrierId"]?.toIntOrNull()?.let { replaceAny(tm, "getSimCarrierId", it) }

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
                XposedHelpers.findAndHookMethod(NetworkInterface::class.java, "getHardwareAddress", object : XC_MethodHook() {
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
            XposedBridge.hookAllMethods(MediaDrm::class.java, "getPropertyByteArray", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() == "deviceUniqueId") param.result = id
                }
            })
        }
        if (level != null || sysId != null) {
            XposedBridge.hookAllMethods(MediaDrm::class.java, "getPropertyString", object : XC_MethodHook() {
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
            XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, cl), method, XC_MethodReplacement.returnConstant(value))
        }
    }

    /** For methods returning Task<String>: swap the result for an already-completed task. */
    private fun taskResult(cl: ClassLoader, cls: String, method: String, value: String) {
        runCatching {
            val tasks = XposedHelpers.findClass("com.google.android.gms.tasks.Tasks", cl)
            XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, cl), method, object : XC_MethodHook() {
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

        XposedBridge.hookAllMethods(android.accounts.AccountManager::class.java, "getAccounts", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = if (fake != null) arrayOf(fake) else emptyArray<Account>()
            }
        })
        XposedBridge.hookAllMethods(android.accounts.AccountManager::class.java, "getAccountsByType", object : XC_MethodHook() {
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

        XposedBridge.hookAllMethods(ContentResolver::class.java, "query", object : XC_MethodHook() {
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
            XposedBridge.hookAllMethods(android.bluetooth.BluetoothAdapter::class.java, "getBondedDevices",
                XC_MethodReplacement.returnConstant(HashSet<Any>()))
        }
        val wm = android.net.wifi.WifiManager::class.java
        if (r.flag("blockWifiScan")) XposedBridge.hookAllMethods(wm, "getScanResults", XC_MethodReplacement.returnConstant(ArrayList<Any>()))
        if (r.flag("blockWifiConfigured")) XposedBridge.hookAllMethods(wm, "getConfiguredNetworks", XC_MethodReplacement.returnConstant(ArrayList<Any>()))
        if (r.flag("blockCellInfo")) {
            val tm = TelephonyManager::class.java
            XposedBridge.hookAllMethods(tm, "getAllCellInfo", XC_MethodReplacement.returnConstant(ArrayList<Any>()))
            XposedBridge.hookAllMethods(tm, "getCellLocation", XC_MethodReplacement.returnConstant(null))
        }
    }

    // ---------------------------------------------------------------- Timestamps / locale / kernel

    private fun spoofTime(r: Resolver, pkg: String) {
        r["installTime"]?.toLongOrNull()?.let { t ->
            val cls = XposedHelpers.findClass("android.app.ApplicationPackageManager", null)
            XposedBridge.hookAllMethods(cls, "getPackageInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pi = param.result as? PackageInfo ?: return
                    if (pi.packageName == pkg) { pi.firstInstallTime = t; pi.lastUpdateTime = t }
                }
            })
        }
        r["lastBoot"]?.toLongOrNull()?.takeIf { it < System.currentTimeMillis() }?.let { boot ->
            XposedBridge.hookAllMethods(SystemClock::class.java, "elapsedRealtime", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = System.currentTimeMillis() - boot
            })
            XposedBridge.hookAllMethods(SystemClock::class.java, "elapsedRealtimeNanos", object : XC_MethodReplacement() {
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
        XposedBridge.hookAllMethods(System::class.java, "getProperty", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.firstOrNull() == "os.version") param.result = k
            }
        })
    }

    // ---------------------------------------------------------------- helpers

    private fun replace(cls: Class<*>, method: String, value: String) = replaceAny(cls, method, value)

    private fun replaceAny(cls: Class<*>, method: String, value: Any) {
        runCatching { XposedBridge.hookAllMethods(cls, method, XC_MethodReplacement.returnConstant(value)) }
            .onFailure { XposedBridge.log("Sliman-Identity: hook ${cls.simpleName}.$method failed: $it") }
    }
}
