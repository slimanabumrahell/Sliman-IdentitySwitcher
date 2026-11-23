# Sliman-IdentitySwitcher

**Sliman-Identity** is a clean, dark-themed Android app + Vector (LSPosed) module that lets you give each selected app its own device identity profile: model, fingerprint, Android ID, IMEI, serial, IMSI, ICCID and Wi-Fi MAC.

> Intended for privacy, development and QA testing on devices you own. Don't use it to violate the terms of services or to fake traffic/installs.

## Features
- Per-app profiles: pick any installed app, edit its identity, toggle it on/off.
- Organized editor with three tabs: **Device**, **Identity**, **SIM & Net**.
- One-tap device presets (Pixel 8, Pixel 7 Pro, Galaxy S24, OnePlus 12, Xiaomi 14), every value editable.
- Random generators: Android ID, Luhn-valid IMEI, IMSI, ICCID, serial, locally-administered MAC.
- Blank fields are left untouched.

## What is hooked
| Area | APIs |
|---|---|
| Build | `MANUFACTURER, BRAND, MODEL, DEVICE, PRODUCT, BOARD, HARDWARE, FINGERPRINT, SERIAL`, `Build.getSerial()` |
| Settings | `Settings.Secure.getString(ANDROID_ID)` |
| Telephony | `getDeviceId`, `getImei`, `getSubscriberId`, `getSimSerialNumber` |
| Network | `WifiInfo.getMacAddress`, `NetworkInterface.getHardwareAddress` (wlan0) |

## Requirements
- Rooted device with **Vector** (LSPosed fork) installed. Android 8.1+ (minSdk 27).

## Build
Open in Android Studio (JDK 17) and run `assembleDebug`, or push to GitHub: the included workflow (`.github/workflows/build.yml`) builds the APK and uploads it as an artifact.
No Gradle wrapper is committed; run `gradle wrapper --gradle-version 8.9` once if you want one.

## Use
1. Install the APK, open **Vector → Modules**, enable *Sliman-Identity*.
2. In the module's scope, tick every app you want to change.
3. Open Sliman-Identity, choose the app, edit/randomize, **Save profile**.
4. Force-stop the target app and reopen it.

## Limitations
- Does not touch Google Advertising ID, Play Integrity / hardware-backed key attestation, or values read from native code / syscalls.
- Presets are examples; keep fingerprint, model and device consistent with each other.

## Project layout
```
app/src/main/java/com/sliman/identity/
  HookEntry.kt       Vector entry point (hooks)
  Profile*.kt        Model + JSON storage shared with the hook
  Gen.kt, Presets.kt Generators and device presets
  MainActivity.kt    Navigation
  ui/                Theme, app list, editor
```

## بالعربي
تطبيق + موديول Vector: تختار تطبيقاً وتعطيه هوية جهاز خاصة (موديل، Android ID، IMEI، MAC...) بواجهة مرتبة ومقسّمة. فعّل الموديول في Vector، حدّد التطبيقات في النطاق، احفظ البروفايل ثم أوقف التطبيق قسراً وافتحه.

## License
MIT
