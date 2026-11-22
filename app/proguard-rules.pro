# الحفاظ على كلاس الدخول لـ Xposed / LSPosed
-keep class com.sliman.identity.hook.IdentityHookMain { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage

# الحفاظ على حقول ومُنشئ نموذج البروفايل
-keep class com.sliman.identity.model.DeviceProfile { *; }
-keepclassmembers class com.sliman.identity.model.DeviceProfile {
    <init>(...);
}

# الحفاظ على Provider و Receiver
-keep class com.sliman.identity.provider.ProfileProvider { *; }
-keep class com.sliman.identity.receiver.TermuxCommandReceiver { *; }
