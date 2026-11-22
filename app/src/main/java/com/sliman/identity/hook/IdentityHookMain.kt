package com.sliman.identity.hook

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.sliman.identity.model.DeviceProfile
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Locale
import java.util.TimeZone

class IdentityHookMain : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // تجاهل حزمة وحدة الإدارة نفسها لتفادي أي حلقات أو تعارض
        if (lpparam.packageName == "com.sliman.identity") return

        // انتظر تهيئة Application context لقراءة البروفايل المخصص لهذا التطبيق
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation",
            lpparam.classLoader,
            "callApplicationOnCreate",
            android.app.Application::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.args[0] as? android.app.Application ?: return
                    val profile = loadProfileForPackage(app.applicationContext, lpparam.packageName) ?: return
                    applyHooks(lpparam.classLoader, profile)
                }
            }
        )
    }

    private fun loadProfileForPackage(context: Context, packageName: String): DeviceProfile? {
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
            XposedBridge.log("SlimanIdentity: failed to read profile for $packageName -> ${e.message}")
            null
        }
    }

    private fun applyHooks(classLoader: ClassLoader, profile: DeviceProfile) {
        // 1. تعديل خصائص Build
        setStaticFieldIfNotBlank(Build::class.java, "MANUFACTURER", profile.manufacturer)
        setStaticFieldIfNotBlank(Build::class.java, "BRAND", profile.brand)
        setStaticFieldIfNotBlank(Build::class.java, "MODEL", profile.model)
        setStaticFieldIfNotBlank(Build::class.java, "DEVICE", profile.device)
        setStaticFieldIfNotBlank(Build::class.java, "HARDWARE", profile.hardware)
        setStaticFieldIfNotBlank(Build::class.java, "FINGERPRINT", profile.fingerprint)

        // 2. تعديل ANDROID_ID
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

        // 3. تعديل المنطقة الزمنية واللغة داخل نطاق التطبيق
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

        // 4. تعديل بيانات الشبكة والشريحة عبر TelephonyManager
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
            // تجاهل إذا اختلفت التواقيع أو الدالة غير موجودة في نسخة أندرويد الحالية
        }
    }

    private fun setStaticFieldIfNotBlank(clazz: Class<*>, fieldName: String, value: String) {
        if (value.isBlank()) return
        try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, value)
        } catch (_: Throwable) {
            // بعض الحقول على إصدارات أندرويد الحديثة قد تكون مقيدة
        }
    }
}
