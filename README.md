# Sliman-IdentitySwitcher

**Sliman-Identity** is a clean, dark-themed Android app + Vector (LSPosed) module that lets you give each selected app its own device identity profile: model, fingerprint, Android ID, IMEI, serial, IMSI, ICCID and Wi-Fi MAC.

> Intended for privacy, development and QA testing on devices you own. Don't use it to violate the terms of services or to fake traffic/installs.

## Features
- Per-app profiles: pick any installed app, edit its identity, toggle it on/off, give the profile a **name**.
- **Import / export**: one profile (⋮ in the editor) or all profiles (⋮ on the main screen) as `.json`. Old v1 stores import fine.
- Five tabs: **Device**, **Identity**, **SIM & Net**, **System**, **Privacy**.
- Every text field has three modes (⋮ on the field): **Real** (untouched), **Static** (your value / dice = generate now), **Random every launch**.
- **Randomize all identifiers** keeps SIM, country, phone prefix, timezone and locale coherent (picked from a carrier pack).
- 19 device presets (Pixel 6-8a, Galaxy S23/S24/Z Flip5/A54, OnePlus 11/12, Xiaomi 13/14). Each preset carries the full fixed set:
  model, device, product, board, hardware, fingerprint, build ID/display/incremental/type/tags, bootloader/radio (Samsung), SoC, Widevine level.
  Switching preset clears the previous model's fields. Android version/SDK/patch only when you enable "Also set Android version".
- Carrier packs (12 operators): operator code/name/country, IMSI, ICCID, number, timezone, locale in one tap.

## What is hooked
| Area | APIs |
|---|---|
| Build | `MANUFACTURER, BRAND, MODEL, DEVICE, PRODUCT, BOARD, HARDWARE, FINGERPRINT, SERIAL, ID, DISPLAY, TYPE, TAGS, BOOTLOADER, RADIO, SOC_*`, `Build.VERSION.RELEASE/SECURITY_PATCH/INCREMENTAL/SDK_INT`, `getSerial()`, `getRadioVersion()` |
| System properties | `SystemProperties.get/getInt/getLong` for the matching `ro.*` keys |
| Settings | `ANDROID_ID`, `advertising_id`, `bluetooth_address`, `device_name`, `adb_enabled`, `development_settings_enabled` |
| Telephony | IMEI, MEID, IMSI, ICCID, number, SIM/network operator code+name+country, carrier ID, `SubscriptionInfo` |
| Network | `WifiInfo` MAC/BSSID/SSID, `NetworkInterface(wlan0)`, Bluetooth MAC |
| DRM | `MediaDrm` deviceUniqueId, securityLevel, systemId |
| Google SDKs (if bundled by the app) | Advertising ID, App Set ID, Firebase Installation ID, Firebase App Instance ID |
| Google services | GSF ID via the gservices provider, fake Google account via `AccountManager` |
| Time / locale | package install time, last boot (`SystemClock.elapsedRealtime*`), timezone, locale, `os.version` |
| Privacy blocks | accounts, Bluetooth bonded, Wi-Fi scan/configured, cell info, contacts, call log, SMS/MMS, calendar (empty results) |

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
- Does not touch Play Integrity / hardware-backed key attestation, or values read from native code / syscalls (`uname`, `/proc/sys/kernel/random/boot_id`, `getprop` via shell).
- Google SDK ID hooks only work when the app bundles those classes un-obfuscated.
- Preset build strings (incrementals, SoC names, security patch) are best effort: verify against `getprop` from the real device and edit the fields (or export/import a profile) if they differ.
- Boot ID and supported ABIs are intentionally not spoofed (native-level / crash risk).

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
