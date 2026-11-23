package com.sliman.identity

import android.content.ContentResolver
import android.net.wifi.WifiInfo
import android.os.Build
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

        XposedBridge.log("Sliman-Identity: applying profile to $pkg")
        spoofBuild(profile)
        spoofSettings(profile)
        spoofTelephony(profile)
        spoofNetwork(profile)
    }

    private fun spoofBuild(p: Profile) {
        mapOf(
            "MANUFACTURER" to p.manufacturer, "BRAND" to p.brand, "MODEL" to p.model,
            "DEVICE" to p.device, "PRODUCT" to p.product, "BOARD" to p.board,
            "HARDWARE" to p.hardware, "FINGERPRINT" to p.fingerprint, "SERIAL" to p.serial,
        ).forEach { (field, value) ->
            if (value.isNotBlank()) {
                runCatching { XposedHelpers.setStaticObjectField(Build::class.java, field, value) }
            }
        }
        if (p.serial.isNotBlank()) replace(Build::class.java, "getSerial", p.serial)
    }

    private fun spoofSettings(p: Profile) {
        if (p.androidId.isBlank()) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java, "getString",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[1] == Settings.Secure.ANDROID_ID) param.result = p.androidId
                    }
                },
            )
        }.onFailure { XposedBridge.log("Sliman-Identity: android_id hook failed: $it") }
    }

    private fun spoofTelephony(p: Profile) {
        val tm = TelephonyManager::class.java
        if (p.imei.isNotBlank()) {
            replace(tm, "getDeviceId", p.imei)
            replace(tm, "getImei", p.imei)
        }
        if (p.imsi.isNotBlank()) replace(tm, "getSubscriberId", p.imsi)
        if (p.simSerial.isNotBlank()) replace(tm, "getSimSerialNumber", p.simSerial)
    }

    private fun spoofNetwork(p: Profile) {
        if (p.wifiMac.isBlank()) return
        replace(WifiInfo::class.java, "getMacAddress", p.wifiMac)

        val bytes = runCatching {
            p.wifiMac.split(":").map { it.toInt(16).toByte() }.toByteArray().takeIf { it.size == 6 }
        }.getOrNull() ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                NetworkInterface::class.java, "getHardwareAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if ((param.thisObject as NetworkInterface).name == "wlan0") param.result = bytes
                    }
                },
            )
        }
    }

    private fun replace(cls: Class<*>, method: String, value: String) {
        runCatching { XposedBridge.hookAllMethods(cls, method, XC_MethodReplacement.returnConstant(value)) }
            .onFailure { XposedBridge.log("Sliman-Identity: hook ${cls.simpleName}.$method failed: $it") }
    }
}
