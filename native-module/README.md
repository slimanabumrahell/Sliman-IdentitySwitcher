# Sliman Native Hooks (Zygisk)

Companion of **Sliman-Identity**. The LSPosed/Vector module changes what Java code sees; this module changes what
**native code** sees inside the selected app's process, using the same profile.

## What it changes (per profile / per app)
| Native call | Spoofed from profile |
|---|---|
| `__system_property_get/find/read*` | `ro.product.*`, `ro.build.*` (fingerprint, id, display, type, tags, incremental, security patch, release), bootloader, baseband, serial, SoC, hostname |
| `uname`, `/proc/version` | kernel release (`kernel`) |
| `sysconf`, `get_nprocs`, `/sys/devices/system/cpu/*`, `/proc/cpuinfo` | CPU core count, `Hardware` line (SoC model) |
| `sysconf(_SC_PHYS_PAGES)`, `sysinfo`, `/proc/meminfo` | total RAM (`ramMb`), free/available scaled |
| `statfs/statvfs` family | data-partition size (`storageGb`, apps see ~93%) |
| `/proc/sys/kernel/random/boot_id` | `bootId` (static or `@random` per launch) |
| `ioctl(SIOCGIFHWADDR)`, `getifaddrs`, `/sys/class/net/wlan0/address` | `wifiMac` |
| `glGetString(GL_VENDOR / GL_RENDERER)` via GOT, `dlsym`, `eglGetProcAddress` | `glVendor`, `glRenderer` (WebGL unmasked renderer) |

Deliberately **not** spoofed natively: `ro.hardware` (selects GPU/HAL drivers) and `ro.build.version.sdk`
(native libraries use it for compatibility decisions).

## Install
1. Push to GitHub; the workflow *Build native Zygisk module* produces `Sliman-Native-Zygisk.zip`
   (or run `native-module/build.sh` with `ANDROID_NDK_HOME` set; Ninja + curl required).
2. Flash in Magisk (enable **Zygisk**) / KernelSU / APatch + a Zygisk provider. Reboot.
3. Open Sliman-Identity, create/save a profile for the app (it syncs automatically), force-stop the app, start it.
4. `adb logcat -s SlimanNative` shows `profile matched`, `patched N slot(s) in <lib>` and write failures.

## How the hooks are applied (and limits)
GOT slots of every loaded library are scanned for the real address of the target function and overwritten with the wrapper;
libraries loaded later are caught by hooking `dlopen` / `android_dlopen_ext`. A slot is written only if it already holds the
real function's address, so a wrong guess can't corrupt anything - it just won't take effect.

* Libraries that call libc through **direct syscalls** or have **read-only shared RELRO** mappings (some Chromium/WebView builds)
  can't be patched; those show up as `write failure(s)` in logcat.
* The GL hook only affects processes that actually run the GL driver (browser GPU thread/process). Isolated WebView renderers spawned
  by `webview_zygote` are not matched (no package name).
* Reading the config requires the profile's process name to equal the package or start with `package:`.
* Spoofing RAM/CPU higher than real makes apps size caches/thread pools for hardware you don't have.
* Not tested on-device by the author of this skeleton: expect to iterate using logcat.
