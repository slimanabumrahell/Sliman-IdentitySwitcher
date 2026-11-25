package com.sliman.identity

enum class Tab(val title: String) {
    DEVICE("Device"), IDENTITY("Identity"), SIM("SIM & Net"), SYSTEM("System"), PRIVACY("Privacy"),
}

enum class Kind { TEXT, FLAG }

/**
 * One editable identity field. TEXT fields can be Real / Static / Random-per-launch,
 * FLAG fields are Real / Forced (value "1").
 */
data class FieldDef(
    val key: String,
    val label: String,
    val tab: Tab,
    val section: String,
    val kind: Kind = Kind.TEXT,
    val mono: Boolean = false,
    val flagLabel: String = "Forced",
    val isTime: Boolean = false,
    /** Random generator. Receives the profile so values can stay coherent (carrier, brand...). */
    val gen: ((Profile) -> String)? = null,
)

object Fields {
    private fun t(key: String, label: String, tab: Tab, section: String, mono: Boolean = false,
                  isTime: Boolean = false, gen: ((Profile) -> String)? = null) =
        FieldDef(key, label, tab, section, Kind.TEXT, mono, isTime = isTime, gen = gen)

    private fun f(key: String, label: String, tab: Tab, section: String, flagLabel: String) =
        FieldDef(key, label, tab, section, Kind.FLAG, flagLabel = flagLabel)

    val all: List<FieldDef> = listOf(
        // ---------------- Device ----------------
        t("manufacturer", "Manufacturer", Tab.DEVICE, "Build properties"),
        t("brand", "Brand", Tab.DEVICE, "Build properties"),
        t("model", "Model", Tab.DEVICE, "Build properties"),
        t("device", "Device", Tab.DEVICE, "Build properties"),
        t("product", "Product", Tab.DEVICE, "Build properties"),
        t("board", "Board", Tab.DEVICE, "Build properties"),
        t("hardware", "Hardware", Tab.DEVICE, "Build properties"),
        t("fingerprint", "Fingerprint", Tab.DEVICE, "Build properties"),
        t("buildId", "Build ID", Tab.DEVICE, "Build details"),
        t("buildDisplay", "Build Display", Tab.DEVICE, "Build details"),
        t("buildIncremental", "Build Incremental", Tab.DEVICE, "Build details"),
        t("buildType", "Build Type", Tab.DEVICE, "Build details"),
        t("buildTags", "Build Tags", Tab.DEVICE, "Build details"),
        t("bootloader", "Build Bootloader", Tab.DEVICE, "Build details"),
        t("radio", "Build Radio Version", Tab.DEVICE, "Build details"),
        t("socManufacturer", "SoC Manufacturer", Tab.DEVICE, "SoC"),
        t("socModel", "SoC Model", Tab.DEVICE, "SoC"),
        t("cpuCores", "CPU cores (availableProcessors)", Tab.DEVICE, "Hardware"),
        t("ramMb", "Total RAM (MB)", Tab.DEVICE, "Hardware"),
        t("storageGb", "Internal storage (marketed GB, apps see ~93%)", Tab.DEVICE, "Hardware"),
        t("screenWidth", "Screen width (px)", Tab.DEVICE, "Hardware"),
        t("screenHeight", "Screen height (px)", Tab.DEVICE, "Hardware"),
        t("densityDpi", "Density (dpi)", Tab.DEVICE, "Hardware"),
        t("glVendor", "GPU vendor (GL_VENDOR) - needs native module", Tab.DEVICE, "GPU / WebGL"),
        t("glRenderer", "GPU renderer (GL_RENDERER) - needs native module", Tab.DEVICE, "GPU / WebGL"),
        f("screenAlsoApp", "Also spoof getMetrics / getSize", Tab.DEVICE, "Hardware", "On - can distort app layout"),

        // ---------------- Identity ----------------
        t("androidId", "Android ID", Tab.IDENTITY, "Device identifiers", true) { Gen.androidId() },
        t("gsfId", "Google Services Framework ID", Tab.IDENTITY, "Device identifiers", true) { Gen.androidId() },
        t("firebaseInstallId", "Firebase Installation ID", Tab.IDENTITY, "Device identifiers", true) { Gen.firebaseInstallId() },
        t("appInstanceId", "Firebase App Instance ID", Tab.IDENTITY, "Device identifiers", true) { Gen.hex(32) },
        t("advertisingId", "Advertising ID", Tab.IDENTITY, "Device identifiers", true) { Gen.uuid() },
        t("appSetId", "Google App Set ID", Tab.IDENTITY, "Device identifiers", true) { Gen.uuid() },
        t("googleEmail", "Google Account (fake email)", Tab.IDENTITY, "Device identifiers") { Gen.email() },
        t("serial", "Hardware Serial", Tab.IDENTITY, "Device identifiers", true) { Gen.serial(it) },
        t("imei", "IMEI", Tab.IDENTITY, "Device identifiers", true) { Gen.imei() },
        t("meid", "MEID", Tab.IDENTITY, "Device identifiers", true) { Gen.meid() },
        t("widevineId", "Media DRM ID (Widevine)", Tab.IDENTITY, "DRM", true) { Gen.hex(64) },
        t("widevineLevel", "Widevine Security Level (L1 / L3)", Tab.IDENTITY, "DRM"),
        t("widevineSystemId", "Widevine System ID", Tab.IDENTITY, "DRM", true),

        // ---------------- SIM & Net ----------------
        t("imsi", "Subscriber ID (IMSI)", Tab.SIM, "SIM", true) { Gen.imsi(it) },
        t("simSerial", "ICCID (SIM serial)", Tab.SIM, "SIM", true) { Gen.iccid(it) },
        t("phoneNumber", "Phone Number", Tab.SIM, "SIM", true) { Gen.phone(it) },
        t("simOperator", "SIM Operator (MCC+MNC)", Tab.SIM, "Operator", true),
        t("simOperatorName", "SIM Operator Name", Tab.SIM, "Operator"),
        t("simCarrierId", "SIM Carrier ID", Tab.SIM, "Operator", true),
        t("simCountry", "SIM Country ISO", Tab.SIM, "Operator"),
        t("netOperator", "Network Operator (MCC+MNC)", Tab.SIM, "Operator", true),
        t("netOperatorName", "Network Operator Name", Tab.SIM, "Operator"),
        t("netCountry", "Network Country ISO", Tab.SIM, "Operator"),
        t("wifiMac", "Wi-Fi MAC", Tab.SIM, "Network", true) { Gen.mac() },
        t("btMac", "Bluetooth MAC", Tab.SIM, "Network", true) { Gen.mac() },
        t("bssid", "Wi-Fi BSSID", Tab.SIM, "Network", true) { Gen.bssid() },
        t("ssid", "Wi-Fi SSID", Tab.SIM, "Network") { Gen.ssid() },
        t("hostname", "Hostname / device name", Tab.SIM, "Network", true) { Gen.hostname() },

        // ---------------- System ----------------
        t("androidRelease", "Android Version (RELEASE)", Tab.SYSTEM, "OS version"),
        t("sdkInt", "SDK / API Level (SDK_INT) - risky", Tab.SYSTEM, "OS version"),
        t("securityPatch", "Security Patch Level", Tab.SYSTEM, "OS version"),
        t("kernel", "Kernel Version (os.version)", Tab.SYSTEM, "OS version"),
        t("installTime", "App Install Time (epoch ms)", Tab.SYSTEM, "Timestamps", isTime = true) { Gen.installTime() },
        t("lastBoot", "Last Boot Time (epoch ms)", Tab.SYSTEM, "Timestamps", isTime = true) { Gen.lastBoot() },
        f("uaAuto", "Rewrite WebView / HTTP User-Agent from profile", Tab.SYSTEM, "Browser / WebView", "On (model + Android version)"),
        t("userAgent", "Custom User-Agent (overrides the rewrite)", Tab.SYSTEM, "Browser / WebView") { Gen.userAgent(it) },
        f("adbFlag", "ADB Debugging Flag", Tab.SYSTEM, "Device trust signals", "Reports OFF"),
        f("devOptionsFlag", "Developer Options Flag", Tab.SYSTEM, "Device trust signals", "Reports OFF"),
        t("bootId", "Boot ID (/proc/.../boot_id) - needs native module", Tab.SYSTEM, "Native only", true) { Gen.uuid() },
        t("timezone", "Timezone", Tab.SYSTEM, "Location consistency") { Gen.carrier(it).tz },
        t("locale", "Locale (e.g. en-US)", Tab.SYSTEM, "Location consistency") { Gen.carrier(it).locale },

        // ---------------- Privacy ----------------
        f("blockAccounts", "Device Accounts", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty list)"),
        f("blockBtBonded", "Bluetooth Bonded Devices", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty list)"),
        f("blockWifiScan", "WiFi Scan Results", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty list)"),
        f("blockWifiConfigured", "WiFi Configured Networks", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty list)"),
        f("blockCellInfo", "Cell Info / Tower Location", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty)"),
        f("blockContacts", "Contacts", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty)"),
        f("blockCallLog", "Call Log", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty)"),
        f("blockSms", "SMS / MMS Messages", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty)"),
        f("blockCalendar", "Calendar", Tab.PRIVACY, "Privacy restrictions", "Blocked (empty)"),
    )

    val byKey: Map<String, FieldDef> = all.associateBy { it.key }

    /** Keys that a device preset owns: switching preset clears them first so nothing stale survives. */
    val presetKeys: Set<String> = all.filter { it.tab == Tab.DEVICE }.map { it.key }.toSet() +
        setOf("widevineLevel", "widevineSystemId")

    /** OS-version keys, only touched by a preset when the user opts in. */
    val osKeys = setOf("androidRelease", "securityPatch", "sdkInt")
}
