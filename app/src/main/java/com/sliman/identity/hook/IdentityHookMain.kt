package com.sliman.identity.hook

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.sliman.identity.model.DeviceProfile
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.Locale
import java.util.TimeZone

class IdentityHookMain : IXposedHookLoadPackage {

    private val hookedPackages = mutableSetOf<String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.sliman.identity") return

        // محاولة مبكرة عبر attachBaseContext قبل تشغيل Application.onCreate
        XposedHelpers.findAndHookMethod(
            ContextWrapper::class.java,
            "attachBaseContext",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (hookedPackages.contains(lpparam.packageName)) return
                    val baseContext = param.args[0] as? Context ?: return
                    val profile = loadProfile(baseContext, lpparam.packageName) ?: return
                    hookedPackages.add(lpparam.packageName)
                    XposedBridge.log("SlimanIdentity: applied early hook for ${lpparam.packageName} -> ${profile.profileName}")
                    applyHooks(lpparam.classLoader, profile)
                }
            }
        )

        // خطاف احتياطي عبر Instrumentation.callApplicationOnCreate (beforeHookedMethod)
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation",
            lpparam.classLoader,
            "callApplicationOnCreate",
            Application::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (hookedPackages.contains(lpparam.packageName)) return
                    val app = param.args[0] as? Application ?: return
                    val profile = loadProfile(app.baseContext ?: app.applicationContext, lpparam.packageName) ?: return
                    hookedPackages.add(lpparam.packageName)
                    XposedBridge.log("SlimanIdentity: applied Instrumentation hook for ${lpparam.packageName} -> ${profile.profileName}")
                    applyHooks(lpparam.classLoader, profile)
                }
            }
        )
    }

    private fun loadProfile(context: Context?, packageName: String): DeviceProfile? {
        // 1. القراءة المباشرة من ملف JSON في /data/local/tmp/sliman_identity
        try {
            val file = File("/data/local/tmp/sliman_identity/$packageName.json")
            if (file.exists() && file.canRead()) {
                val raw = file.readText()
                val parsed = DeviceProfile.fromJson(raw)
                if (parsed != null) return parsed
            }
        } catch (_: Throwable) {
        }

        // 2. Fallback عبر ContentProvider
        if (context == null) return null
        return try {
            val uri = Uri.parse("content://com.sliman.identity.provider/profile/$packageName")
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("json_data")
                    if (idx >= 0) {
                        return DeviceProfile.fromJson(cursor.getString(idx))
                    }
                }
                null
            }
        } catch (e: Throwable) {
            XposedBridge.log("SlimanIdentity: failed to load profile for $packageName -> ${e.message}")
            null
        }
    }

    private fun applyHooks(classLoader: ClassLoader, profile: DeviceProfile) {
        setStaticFieldIfNotBlank(Build::class.java, "MANUFACTURER", profile.manufacturer)
        setStaticFieldIfNotBlank(Build::class.java, "BRAND", profile.brand)
        setStaticFieldIfNotBlank(Build::class.java, "MODEL", profile.model)
        setStaticFieldIfNotBlank(Build::class.java, "DEVICE", profile.device)
        setStaticFieldIfNotBlank(Build::class.java, "HARDWARE", profile.hardware)
        setStaticFieldIfNotBlank(Build::class.java, "FINGERPRINT", profile.fingerprint)

        if (profile.androidId.isNotBlank()) {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java,
                "getString",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[1] == Settings.Secure.ANDROID_ID) {
                            param.result = profile.androidId
                        }
                    }
                }
            )
        }

        if (profile.timeZoneId.isNotBlank()) {
            val customTz = TimeZone.getTimeZone(profile.timeZoneId)
            TimeZone.setDefault(customTz)
            XposedHelpers.findAndHookMethod(
                TimeZone::class.java,
                "getDefault",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = customTz
                    }
                }
            )
        }

        if (profile.localeLanguage.isNotBlank()) {
            val customLocale = if (profile.localeCountry.isNotBlank()) {
                Locale(profile.localeLanguage, profile.localeCountry)
            } else {
                Locale(profile.localeLanguage)
            }
            Locale.setDefault(customLocale)
            XposedHelpers.findAndHookMethod(
                Locale::class.java,
                "getDefault",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = customLocale
                    }
                }
            )
        }

        hookTelephony(classLoader, profile)
    }

    private fun hookTelephony(classLoader: ClassLoader, profile: DeviceProfile) {
        val tmClass = XposedHelpers.findClassIfExists(
            "android.telephony.TelephonyManager",
            classLoader
        ) ?: return

        if (profile.simCountryIso.isNotBlank()) {
            hookMethodIfExists(tmClass, "getSimCountryIso", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = profile.simCountryIso.lowercase(Locale.ROOT)
                }
            })
            hookMethodIfExists(tmClass, "getNetworkCountryIso", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = profile.simCountryIso.lowercase(Locale.ROOT)
                }
            })
        }

        if (profile.networkOperator.isNotBlank()) {
            hookMethodIfExists(tmClass, "getNetworkOperator", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = profile.networkOperator
                }
            })
            hookMethodIfExists(tmClass, "getSimOperator", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = profile.networkOperator
                }
            })
        }

        if (profile.networkOperatorName.isNotBlank()) {
            hookMethodIfExists(tmClass, "getNetworkOperatorName", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = profile.networkOperatorName
                }
            })
            hookMethodIfExists(tmClass, "getSimOperatorName", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = profile.networkOperatorName
                }
            })
        }

        if (profile.imei.isNotBlank()) {
            for (method in listOf("getDeviceId", "getImei")) {
                hookMethodIfExists(tmClass, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = profile.imei
                    }
                })
            }
        }
    }

    private fun hookMethodIfExists(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook
    ) {
        try {
            val hasMethod = clazz.declaredMethods.any { it.name == methodName } ||
                clazz.methods.any { it.name == methodName }
            if (hasMethod) {
                XposedHelpers.findAndHookMethod(clazz, methodName, callback)
            }
        } catch (_: Throwable) {
        }
    }

    private fun setStaticFieldIfNotBlank(clazz: Class<*>, fieldName: String, value: String) {
        if (value.isBlank()) return
        try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, value)
        } catch (_: Throwable) {
        }
    }
}
